/**
 * @file ccnr_init.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */


#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/mman.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/ccn_private.h>
#include <ccn/charbuf.h>
#include <ccn/face_mgmt.h>
#include <ccn/hashtb.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/reg_mgmt.h>
#include <ccn/uri.h>
#include <sync/sync_plumbing.h>
#include <sync/SyncActions.h>

#include "ccnr_private.h"

#include "ccnr_init.h"

#include "ccnr_dispatch.h"
#include "ccnr_forwarding.h"
#include "ccnr_internal_client.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"
#include "ccnr_net.h"
#include "ccnr_proto.h"
#include "ccnr_sendq.h"
#include "ccnr_store.h"
#include "ccnr_sync.h"
#include "ccnr_util.h"

static int load_policy(struct ccnr_handle *h);
static int merge_files(struct ccnr_handle *h);

static struct sync_plumbing_client_methods sync_client_methods = {
    .r_sync_msg = &r_sync_msg,
    .r_sync_fence = &r_sync_fence,
    .r_sync_enumerate = &r_sync_enumerate,
    .r_sync_lookup = &r_sync_lookup,
    .r_sync_local_store = &r_sync_local_store,
    .r_sync_upcall_store = &r_sync_upcall_store
};


/**
 * Read the contents of the repository config file
 *
 * Calls r_init_fail and returns NULL in case of error.
 * @returns unparsed content of config file in a newly allocated charbuf
 */
struct ccn_charbuf *
r_init_read_config(struct ccnr_handle *h)
{
    struct ccn_charbuf *path = NULL;
    struct ccn_charbuf *contents = NULL;
    size_t sz = 800;
    ssize_t sres = -1;
    int fd;
    
    h->directory = getenv("CCNR_DIRECTORY");
    if (h->directory == NULL || h->directory[0] == 0)
        h->directory = ".";
    path = ccn_charbuf_create();
    contents = ccn_charbuf_create();
    if (path == NULL || contents == NULL)
        return(NULL);
    ccn_charbuf_putf(path, "%s/config", h->directory);
    fd = open(ccn_charbuf_as_string(path), O_RDONLY);
    if (fd == -1) {
        if (errno == ENOENT)
            sres = 0;
        else
            r_init_fail(h, __LINE__, ccn_charbuf_as_string(path), errno);
    }
    else {
        for (;;) {
            sres = read(fd, ccn_charbuf_reserve(contents, sz), sz);
            if (sres == 0)
                break;
            if (sres < 0) {
                r_init_fail(h, __LINE__, "Read failed reading config", errno);
                break;
            }
            contents->length += sres;
            if (contents->length > 999999) {
                r_init_fail(h, __LINE__, "config file too large", 0);
                sres = -1;
                break;
            }
        }
        close(fd);
    }
    ccn_charbuf_destroy(&path);
    if (sres < 0)
        ccn_charbuf_destroy(&contents);
    return(contents);
}

static int
r_init_debug_getenv(struct ccnr_handle *h, const char *envname)
{
    const char *debugstr;
    int debugval;
    
    debugstr = getenv(envname);
    debugval = ccnr_msg_level_from_string(debugstr);
    /* Treat 1 and negative specially, for some backward compatibility. */
    if (debugval == 1)
        debugval = CCNL_WARNING;
    if (debugval < 0) {
        debugval = CCNL_FINEST;
        if (h != NULL)
            ccnr_msg(h, "%s='%s' is not valid, using FINEST", envname, debugstr);
    }
    return(debugval);
}

/**
 * Get the specified numerical config value, subject to limits.
 */
intmax_t
r_init_confval(struct ccnr_handle *h, const char *key,
                     intmax_t lo, intmax_t hi, intmax_t deflt) {
    const char *s;
    intmax_t v;
    char *ep;
    
    if (!(lo <= deflt && deflt <= hi))
        abort();
    s = getenv(key);
    if (s != NULL && s[0] != 0) {
        ep = "x";
        v = strtoimax(s, &ep, 10);
        if (v != 0 || ep[0] == 0) {
            if (v > hi)
                v = hi;
            if (v < lo)
                v = lo;
            if (CCNSHOULDLOG(h, mmm, CCNL_FINEST))
                ccnr_msg(h, "Using %s=%jd", key, v);
            return(v);
        }
    }
    return (deflt);
}

#define CCNR_CONFIG_PASSMASK   0x003 /* config pass */
#define CCNR_CONFIG_IGNORELINE 0x100 /* Set if there are prior problems */
#define CCNR_CONFIG_ERR        0x200 /* Report error rather than warning */
/**
 * Message helper for r_init_parse_config()
 */
static void
r_init_config_msg(struct ccnr_handle *h, int flags,
                  int line, int chindex, const char *msg)
{
    const char *problem = "Problem";
    int log_at = CCNL_WARNING;
    
    log_at = CCNL_WARNING;
    if ((flags & CCNR_CONFIG_ERR) != 0) {
        problem = "Error";
        log_at = CCNL_ERROR;
    }
    if ((flags & (CCNR_CONFIG_IGNORELINE|CCNR_CONFIG_PASSMASK)) == 1 &&
        CCNSHOULDLOG(h, mmm, log_at)) {
        ccnr_msg(h, "%s in config file %s/config - line %d column %d: %s",
                 problem, h->directory, line, chindex + 1, msg);
    }
}

/**
 * Parse the buffered configuration found in config
 *
 * The pass argument controls what is done with the result:
 *   0 - silent check for syntax errors;
 *   1 - check for syntax errors and warnings, logging the results,
 *   2 - incorporate settings into environ.
 *
 * @returns -1 if an error is found, otherwise the count of warnings.
 */
int
r_init_parse_config(struct ccnr_handle *h, struct ccn_charbuf *config, int pass)
{
    struct ccn_charbuf *key = NULL;
    struct ccn_charbuf *value = NULL;
    const unsigned char *b;
    int line;
    size_t i;
    size_t sol; /* start of line */
    size_t len; /* config->len */
    size_t ndx; /* temp for column report*/
    int ch;
    int warns = 0;
    int errors = 0;
    int use_it = 0;
    static const char pclegal[] = 
        "~@%-+=:,./[]"
        "abcdefghijklmnopqrstuvwxyz"
        "0123456789"
        "_"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    const char *klegal = strchr(pclegal, 'a');
    int flags; /* for reporting */
    
    b = config->buf;
    len = config->length;
    if (len == 0)
        return(0);
    ccn_charbuf_as_string(config);
    key = ccn_charbuf_create();
    value = ccn_charbuf_create();
    if (key == NULL || value == NULL)
        return(-1);
    /* Ensure there is null termination in the buffered config */
    if (ccn_charbuf_as_string(config) == NULL)
        return(-1);
    for (line = 1, i = 0, ch = b[0], sol = 0; i < len;) {
        flags = pass;
        use_it = 0;
        if (ch > ' ' && ch != '#') {
            key->length = value->length = 0;
            /* parse key */
            while (i < len && ch != '\n' && ch != '=') {
                ccn_charbuf_append_value(key, ch, 1);
                ch = b[++i];
            }
            if (ch == '=')
                ch = b[++i];
            else {
                r_init_config_msg(h, flags, line, key->length, "missing '='");
                flags |= CCNR_CONFIG_IGNORELINE;
                warns++;
                ch = '\n';
            }
            /* parse value */
            while (i < len && ch > ' ') {
                ccn_charbuf_append_value(value, ch, 1);
                ch = b[++i];
            }
            /* See if it might be one of ours */
            if (key->length < 5 || (memcmp(key->buf, "CCNR_", 5) != 0 &&
                                    memcmp(key->buf, "CCNS_", 5) != 0)) {
                r_init_config_msg(h, flags, line, 0,
                                  "ignoring unrecognized key");
                flags |= CCNR_CONFIG_IGNORELINE;
                warns++;
                use_it = 0;
            }
            else
                use_it = 1;

            /* Check charset of key */
            ndx = strspn(ccn_charbuf_as_string(key), klegal);
            if (ndx != key->length) {
                errors += use_it;
                r_init_config_msg(h, (flags | CCNR_CONFIG_ERR), line, ndx,
                                  "unexpected character in key");
                flags |= CCNR_CONFIG_IGNORELINE;
                warns++;
            }
            /* Check charset of value */
            ndx = strspn(ccn_charbuf_as_string(value), pclegal);
            if (ndx != value->length) {
                errors += use_it;
                r_init_config_msg(h, (flags | CCNR_CONFIG_ERR),
                                  line, key->length + 1 + ndx,
                                  "unexpected character in value");
                flags |= CCNR_CONFIG_IGNORELINE;
                warns++;
            }
        }
        if (ch == '#') {
            /* a comment line or error recovery. */
            while (i < len && ch != '\n')
                ch = b[++i];
        }
        while (i < len && ch <= ' ') {
            if (ch == '\n') {
                line++;
                sol = i;
                break;
            }
            if (memchr("\r\t ", ch, 3) == NULL) {
                r_init_config_msg(h, pass, line, i - sol,
                                  "non-whitespace control char at end of line");
                warns++;
            } 
            ch = b[++i];
        }
        if (i == len) {
            r_init_config_msg(h, flags, line, i - sol,
                              "missing newline at end of file");
            warns++;
            ch = '\n';
        }
        else if (ch == '\n')
            ch = b[++i];
        else {
            r_init_config_msg(h, flags, line, i - sol, "junk at end of line");
            flags |= CCNR_CONFIG_IGNORELINE;
            warns++;
            ch = '#';
        }
        if (flags == 0 && strcmp(ccn_charbuf_as_string(key), "CCNR_DEBUG") == 0) {
            /* Set this on pass 0 so that it takes effect sooner. */
            h->debug = 1;
            setenv("CCNR_DEBUG", ccn_charbuf_as_string(value), 1);
            h->debug = r_init_debug_getenv(h, "CCNR_DEBUG");
        }
        if (pass == 2 && use_it) {
            if (CCNSHOULDLOG(h, mmm, CCNL_FINEST))
                ccnr_msg(h, "config: %s=%s",
                        ccn_charbuf_as_string(key),
                        ccn_charbuf_as_string(value));
            setenv(ccn_charbuf_as_string(key), ccn_charbuf_as_string(value), 1);
        }
    }
    ccn_charbuf_destroy(&key);
    ccn_charbuf_destroy(&value);
    return(errors ? -1 : warns);
}

static int
establish_min_send_bufsize(struct ccnr_handle *h, int fd, int minsize)
{
    int res;
    int bufsize;
    int obufsize;
    socklen_t bufsize_sz;

    bufsize_sz = sizeof(bufsize);
    res = getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &bufsize, &bufsize_sz);
    if (res == -1)
        return (res);
    obufsize = bufsize;
    if (bufsize < minsize) {
        bufsize = minsize;
        res = setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &bufsize, sizeof(bufsize));
        if (res == -1)
            return(res);
    }
    if (CCNSHOULDLOG(h, sdfdsf, CCNL_INFO))
        ccnr_msg(h, "SO_SNDBUF for fd %d is %d (was %d)", fd, bufsize, obufsize);
    return(bufsize);
}

/**
 * If so configured, replace fd with a tcp socket
 * @returns new address family
 */
static int
try_tcp_instead(int fd)
{
    struct addrinfo hints = {0};
    struct addrinfo *ai = NULL;
    const char *port = NULL;
    const char *proto = NULL;
    int res;
    int sock;
    int ans = AF_UNIX;
    int yes = 1;
    
    proto = getenv("CCNR_PROTO");
    if (proto == NULL || strcasecmp(proto, "tcp") != 0)
        return(ans);
    port = getenv("CCN_LOCAL_PORT");
    if (port == NULL || port[0] == 0)
        port = "9695";
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = 0;
    hints.ai_protocol = 0;
    res = getaddrinfo(NULL, port, &hints, &ai);
    if (res == 0) {
        sock = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (sock != -1) {
            res = connect(sock, ai->ai_addr, ai->ai_addrlen);
            if (res == 0) {
                setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
                dup2(sock, fd);
                ans = ai->ai_family;
            }
            else
                close(sock);
        }
        freeaddrinfo(ai);
    }
    return(ans);
}

PUBLIC struct ccnr_parsed_policy *
ccnr_parsed_policy_create(void)
{
    struct ccnr_parsed_policy *pp;
    pp = calloc(1, sizeof(struct ccnr_parsed_policy));
    pp->store = ccn_charbuf_create();
    pp->namespaces = ccn_indexbuf_create();
    return(pp);
}

PUBLIC void
ccnr_parsed_policy_destroy(struct ccnr_parsed_policy **ppp)
{
    struct ccnr_parsed_policy *pp;
    
    if (*ppp == NULL)
        return;
    pp = *ppp;
    ccn_charbuf_destroy(&pp->store);
    ccn_indexbuf_destroy(&pp->namespaces);
    free(pp);
    *ppp = NULL;
}

/**
 * Create a new ccnr instance
 * @param progname - name of program binary, used for locating helpers
 * @param logger - logger function
 * @param loggerdata - data to pass to logger function
 */
PUBLIC struct ccnr_handle *
r_init_create(const char *progname, ccnr_logger logger, void *loggerdata)
{
    char *sockname = NULL;
    const char *portstr = NULL;
    const char *listen_on = NULL;
    const char *d = NULL;
    struct ccnr_handle *h = NULL;
    struct hashtb_param param = {0};
    struct ccn_charbuf *config = NULL;
    int res;
    
    h = calloc(1, sizeof(*h));
    if (h == NULL)
        return(h);
    h->notify_after = 0; //CCNR_MAX_ACCESSION;
    h->logger = logger;
    h->loggerdata = loggerdata;
    h->logpid = (int)getpid();
    h->progname = progname;
    h->debug = -1;
    config = r_init_read_config(h);
    if (config == NULL)
        goto Bail;
    r_init_parse_config(h, config, 0); /* silent pass to pick up CCNR_DEBUG */
    h->debug = 1; /* so that we see any complaints */
    h->debug = r_init_debug_getenv(h, "CCNR_DEBUG");
    res = r_init_parse_config(h, config, 1);
    if (res < 0) {
        h->running = -1;
        goto Bail;
    }
    r_init_parse_config(h, config, 2);
    sockname = r_net_get_local_sockname();
    h->skiplinks = ccn_indexbuf_create();
    h->face_limit = 10; /* soft limit */
    h->fdholder_by_fd = calloc(h->face_limit, sizeof(h->fdholder_by_fd[0]));
    param.finalize_data = h;
    param.finalize = &r_fwd_finalize_nameprefix;
    h->nameprefix_tab = hashtb_create(sizeof(struct nameprefix_entry), &param);
    param.finalize = 0; // PRUNED &r_fwd_finalize_propagating;
    h->propagating_tab = hashtb_create(sizeof(struct propagating_entry), &param);
    param.finalize = &r_proto_finalize_enum_state;
    h->enum_state_tab = hashtb_create(sizeof(struct enum_state), &param);
    h->min_stale = ~0;
    h->max_stale = 0;
    h->unsol = ccn_indexbuf_create();
    h->ticktock.descr[0] = 'C';
    h->ticktock.micros_per_base = 1000000;
    h->ticktock.gettime = &r_util_gettime;
    h->ticktock.data = h;
    h->sched = ccn_schedule_create(h, &h->ticktock);
    h->starttime = h->sec;
    h->starttime_usec = h->usec;
    h->oldformatcontentgrumble = 1;
    h->oldformatinterestgrumble = 1;
    h->cob_limit = 4201;
    h->start_write_scope_limit = r_init_confval(h, "CCNR_START_WRITE_SCOPE_LIMIT", 0, 3, 3);
    h->debug = 1; /* so that we see any complaints */
    h->debug = r_init_debug_getenv(h, "CCNR_DEBUG");
    h->syncdebug = r_init_debug_getenv(h, "CCNS_DEBUG");
    portstr = getenv("CCNR_STATUS_PORT");
    if (portstr == NULL || portstr[0] == 0 || strlen(portstr) > 10)
        portstr = "";
    h->portstr = portstr;
    ccnr_msg(h, "CCNR_DEBUG=%d CCNR_DIRECTORY=%s CCNR_STATUS_PORT=%s", h->debug, h->directory, h->portstr);
    listen_on = getenv("CCNR_LISTEN_ON");
    if (listen_on != NULL && listen_on[0] != 0)
        ccnr_msg(h, "CCNR_LISTEN_ON=%s", listen_on);
    
    if (ccnr_init_repo_keystore(h, NULL) < 0) {
        h->running = -1;
        goto Bail;
    }
    r_util_reseed(h);
    r_store_init(h);
    if (h->running == -1) goto Bail;
    while (h->active_in_fd >= 0) {
        r_dispatch_process_input(h, h->active_in_fd);
        r_store_trim(h, h->cob_limit);
        ccn_schedule_run(h->sched);
    }
    ccnr_msg(h, "Repository file is indexed");
    if (h->face0 == NULL) {
        struct fdholder *fdholder;
        fdholder = calloc(1, sizeof(*fdholder));
        if (dup2(open("/dev/null", O_RDONLY), 0) == -1)
            ccnr_msg(h, "stdin: %s", strerror(errno));
        fdholder->filedesc = 0;
        fdholder->flags = (CCNR_FACE_GG | CCNR_FACE_NORECV);
        r_io_enroll_face(h, fdholder);
    }
    ccnr_direct_client_start(h);
    d = getenv("CCNR_SKIP_VERIFY");
#if (CCN_API_VERSION >= 4004)
    if (d != NULL && strcmp(d, "1") == 0) {
        ccnr_msg(h, "CCNR_SKIP_VERIFY=%s", d);
        ccn_defer_verification(h->direct_client, 1);
    }
#endif
    if (ccn_connect(h->direct_client, NULL) != -1) {
        int af = 0;
        int bufsize;
        int flags;
        int fd;
        struct fdholder *fdholder;

        fd = ccn_get_connection_fd(h->direct_client);
        // Play a dirty trick here - if this wins, we can fix it right in the c lib later on...
        af = try_tcp_instead(fd);  
        flags = CCNR_FACE_CCND;
        if (af == AF_INET)
            flags |= CCNR_FACE_INET;
        else if (af == AF_INET6)
            flags |= CCNR_FACE_INET6;
        else
            flags |= CCNR_FACE_LOCAL;
        fdholder = r_io_record_fd(h, fd, "CCND", 5, flags);
        if (fdholder == NULL) abort();
        ccnr_uri_listen(h, h->direct_client, "ccnx:/%C1.M.S.localhost/%C1.M.SRV/repository",
                        &ccnr_answer_req, OP_SERVICE);
        ccnr_uri_listen(h, h->direct_client, "ccnx:/%C1.M.S.neighborhood/%C1.M.SRV/repository",
                        &ccnr_answer_req, OP_SERVICE);
        bufsize = r_init_confval(h, "CCNR_MIN_SEND_BUFSIZE", 1, 2097152, 16384);
        establish_min_send_bufsize(h, fd, bufsize);
    }
    else
        ccn_disconnect(h->direct_client); // Apparently ccn_connect error case needs work.
    if (1 == r_init_confval(h, "CCNS_ENABLE", 0, 1, 1)) {
        h->sync_plumbing = calloc(1, sizeof(struct sync_plumbing));
        h->sync_plumbing->ccn = h->direct_client;
        h->sync_plumbing->sched = h->sched;
        h->sync_plumbing->client_methods = &sync_client_methods;
        h->sync_plumbing->client_data = h;
        h->sync_base = SyncNewBaseForActions(h->sync_plumbing);
    }
    if (-1 == load_policy(h))
        goto Bail;
    r_net_listen_on(h, listen_on);
    ccnr_internal_client_start(h);
    r_proto_init(h);
    r_proto_activate_policy(h, h->parsed_policy);
    if (merge_files(h) == -1)
        r_init_fail(h, __LINE__, "Unable to merge additional repository data files.", errno);
    if (h->running == -1) goto Bail;
    if (h->sync_plumbing) {
        // Start sync running
        // returns < 0 if a failure occurred
        // returns 0 if the name updates should fully restart
        // returns > 0 if the name updates should restart at last fence
        res = h->sync_plumbing->sync_methods->sync_start(h->sync_plumbing, NULL);
        if (res < 0) {
            r_init_fail(h, __LINE__, "starting sync", res);
            abort();
        }
        else if (res > 0) {
            // XXX: need to work out details of starting from last fence.
            // By examination of code, SyncActions won't take this path
        }
    }
Bail:
    if (sockname)
        free(sockname);
    sockname = NULL;
    ccn_charbuf_destroy(&config);
    if (h->running == -1)
        r_init_destroy(&h);
    return(h);
}

void
r_init_fail(struct ccnr_handle *ccnr, int line, const char *culprit, int err)
{
    if (err > 0)
        ccnr_msg(ccnr, "Startup failure %d %s - %s", line, culprit,
                 strerror(err));
    else {
        ccnr_msg(ccnr, "Startup failure %d %s - error %d", line, culprit, err);
    }
    ccnr->running = -1;
}

/**
 * Destroy the ccnr instance, releasing all associated resources.
 */
PUBLIC void
r_init_destroy(struct ccnr_handle **pccnr)
{
    struct ccnr_handle *h = *pccnr;
    int stable;
    if (h == NULL)
        return;
    stable = h->active_in_fd == -1 ? 1 : 0;
    r_io_shutdown_all(h);
    ccnr_direct_client_stop(h);
    ccn_schedule_destroy(&h->sched);
    hashtb_destroy(&h->propagating_tab);
    hashtb_destroy(&h->nameprefix_tab);
    hashtb_destroy(&h->enum_state_tab);
    hashtb_destroy(&h->content_by_accession_tab);

    // SyncActions sync_stop method should be shutting down heartbeat
    if (h->sync_plumbing) {
        h->sync_plumbing->sync_methods->sync_stop(h->sync_plumbing, NULL);
        free(h->sync_plumbing);
        h->sync_plumbing = NULL;
        h->sync_base = NULL; // freed by sync_stop ?
    }
    
    r_store_final(h, stable);
    
    if (h->fds != NULL) {
        free(h->fds);
        h->fds = NULL;
        h->nfds = 0;
    }
    if (h->fdholder_by_fd != NULL) {
        free(h->fdholder_by_fd);
        h->fdholder_by_fd = NULL;
        h->face_limit = h->face_gen = 0;
    }
    if (h->content_by_cookie != NULL) {
        free(h->content_by_cookie);
        h->content_by_cookie = NULL;
        h->cookie_limit = 1;
    }
    ccn_charbuf_destroy(&h->scratch_charbuf);
    ccn_indexbuf_destroy(&h->skiplinks);
    ccn_indexbuf_destroy(&h->scratch_indexbuf);
    ccn_indexbuf_destroy(&h->unsol);
    if (h->parsed_policy != NULL) {
        ccn_indexbuf_destroy(&h->parsed_policy->namespaces);
        ccn_charbuf_destroy(&h->parsed_policy->store);
        free(h->parsed_policy);
        h->parsed_policy = NULL;
    }
    ccn_charbuf_destroy(&h->policy_name);
    ccn_charbuf_destroy(&h->policy_link_cob);
    ccn_charbuf_destroy(&h->ccnr_keyid);
    free(h);
    *pccnr = NULL;
}

int
r_init_map_and_process_file(struct ccnr_handle *h, struct ccn_charbuf *filename, int add_content)
{
    int res = 0;
    int dres;
    struct stat statbuf;
    unsigned char *mapped_file = MAP_FAILED;
    unsigned char *msg;
    size_t size;
    int fd = -1;
    struct content_entry *content;
    struct ccn_skeleton_decoder *d;
    struct fdholder *fdholder;
    
    fd = r_io_open_repo_data_file(h, ccn_charbuf_as_string(filename), 0);
    if (fd == -1)   // Normal exit
        return(1);
    
    res = fstat(fd, &statbuf);
    if (res != 0) {
        ccnr_msg(h, "stat failed for %s (fd=%d), %s (errno=%d)",
                 ccn_charbuf_as_string(filename), fd, strerror(errno), errno);
        res = -errno;
        goto Bail;
    }
    if (statbuf.st_size == 0)
        goto Bail;
    
    mapped_file = mmap(NULL, statbuf.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped_file == MAP_FAILED) {
        ccnr_msg(h, "mmap failed for %s (fd=%d), %s (errno=%d)",
                 ccn_charbuf_as_string(filename), fd, strerror(errno), errno);
        res = -errno;
        goto Bail;
    }
    fdholder = r_io_fdholder_from_fd(h, fd);
    d = &fdholder->decoder;
    msg = mapped_file;
    size = statbuf.st_size;
    while (d->index < size) {
        dres = ccn_skeleton_decode(d, msg + d->index, size - d->index);
        if (!CCN_FINAL_DSTATE(d->state))
            break;
        if (add_content) {
            content = process_incoming_content(h, fdholder, msg + d->index - dres, dres, NULL);
            if (content != NULL)
                r_store_commit_content(h, content);
        }
    }
    
    if (d->index != size || !CCN_FINAL_DSTATE(d->state)) {
        ccnr_msg(h, "protocol error on fdholder %u (state %d), discarding %d bytes",
                 fdholder->filedesc, d->state, (int)(size - d->index));
        res = -1;
        goto Bail;
    }
    
Bail:
    if (mapped_file != MAP_FAILED)
        munmap(mapped_file, statbuf.st_size);
    r_io_shutdown_client_fd(h, fd);
    return (res);
}

static int
merge_files(struct ccnr_handle *h)
{
    int i, last_file;
    int res;
    struct ccn_charbuf *filename = ccn_charbuf_create();
    
    // first parse the file(s) making sure there are no errors
    for (i = 2;; i++) {
        filename->length = 0;
        ccn_charbuf_putf(filename, "repoFile%d", i);
        res = r_init_map_and_process_file(h, filename, 0);
        if (res == 1)
            break;
        if (res < 0) {
            ccnr_msg(h, "Error parsing repository file %s", ccn_charbuf_as_string(filename));
            return (-1);
        }
    }
    last_file = i - 1;
    
    for (i = 2; i <= last_file; i++) {
        filename->length = 0;
        ccn_charbuf_putf(filename, "repoFile%d", i);
        res = r_init_map_and_process_file(h, filename, 1);
        if (res < 0) {
            ccnr_msg(h, "Error in phase 2 incorporating repository file %s", ccn_charbuf_as_string(filename));
            return (-1);
        }
    }
    
    for (i = last_file; i > 1; --i) {
        filename->length = 0;
        ccn_charbuf_putf(filename, "%s/repoFile%d", h->directory, i);
        if (CCNSHOULDLOG(h, LM_128, CCNL_INFO))
            ccnr_msg(h, "unlinking %s", ccn_charbuf_as_string(filename));   
        unlink(ccn_charbuf_as_string(filename));
    }
    ccn_charbuf_destroy(&filename);
    return (0);
}

static struct ccn_charbuf *
ccnr_init_policy_cob(struct ccnr_handle *ccnr, struct ccn *h,
                     struct ccn_charbuf *basename,
                     int freshness, struct ccn_charbuf *content)
{
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    struct ccn_charbuf *name = ccn_charbuf_create();
    struct ccn_charbuf *pubid = ccn_charbuf_create();
    struct ccn_charbuf *pubkey = ccn_charbuf_create();
    struct ccn_charbuf *keyid = ccn_charbuf_create();
    struct ccn_charbuf *tcob = ccn_charbuf_create();
    struct ccn_charbuf *cob = NULL;          // result
    int res;
    
    res = ccn_get_public_key(h, NULL, pubid, pubkey);
    if (res < 0) 
        goto Leave;
    res = ccn_charbuf_append_charbuf(name, basename);
    if (ccn_name_from_uri(name, "%00") < 0)
        goto Leave;
    sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    sp.type = CCN_CONTENT_DATA;
    sp.freshness = freshness;
    res |= ccn_sign_content(h, tcob, name, &sp, content->buf, content->length);
    if (res == 0) {
        cob = tcob;
        tcob = NULL;
    }
    
Leave:
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&pubid);
    ccn_charbuf_destroy(&pubkey);
    ccn_charbuf_destroy(&keyid);
    ccn_charbuf_destroy(&tcob);
    return (cob);
}
/**
 * should probably return a new cob, rather than reusing one.
 * should publish link as:
 *    CCNRID_POLICY_URI("ccnx:/%C1.M.S.localhost/%C1.M.SRV/repository/POLICY)/%C1.M.K--pubid--/--version--/%00
 * should have key locator which is the key name of the repository
 */
PUBLIC struct ccn_charbuf *
ccnr_init_policy_link_cob(struct ccnr_handle *ccnr, struct ccn *h,
                          struct ccn_charbuf *targetname)
{
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    struct ccn_charbuf *name = ccn_charbuf_create();
    struct ccn_charbuf *pubid = ccn_charbuf_create();
    struct ccn_charbuf *pubkey = ccn_charbuf_create();
    struct ccn_charbuf *keyid = ccn_charbuf_create();
    struct ccn_charbuf *content = ccn_charbuf_create();
    struct ccn_charbuf *cob = ccn_charbuf_create();
    struct ccn_charbuf *answer = NULL;
    int res;
    
    res = ccn_get_public_key(h, NULL, pubid, pubkey);
    if (res < 0)
        goto Bail;
    if (ccn_name_from_uri(name, CCNRID_POLICY_URI) < 0)
        goto Bail;
    res |= ccn_charbuf_append_value(keyid, CCN_MARKER_CONTROL, 1);
    res |= ccn_charbuf_append_string(keyid, ".M.K");
    res |= ccn_charbuf_append_value(keyid, 0, 1);
    res |= ccn_charbuf_append_charbuf(keyid, pubid);
    res |= ccn_name_append(name, keyid->buf, keyid->length);
    res |= ccn_create_version(h, name, CCN_V_NOW, 0, 0);
    if (ccn_name_from_uri(name, "%00") < 0)
        goto Bail;
    sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    sp.type = CCN_CONTENT_LINK;
    res |= ccnb_append_Link(content, targetname, "Repository Policy", NULL);
    if (res != 0)
        goto Bail;
    res |= ccn_sign_content(h, cob, name, &sp, content->buf, content->length);
    if (res != 0)
        goto Bail;
    answer = cob;
    cob = NULL;
    
Bail:
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&pubid);
    ccn_charbuf_destroy(&pubkey);
    ccn_charbuf_destroy(&keyid);
    ccn_charbuf_destroy(&content);
    ccn_charbuf_destroy(&cob);
    return (answer);
}


/**
 * Load a link to the repo policy from the repoPolicy file and load the link
 * target to extract the actual policy.
 * If a policy file does not exist a new one is created, with a link to a policy
 * based either on the environment variable CCNR_GLOBAL_PREFIX or the system
 * default value of ccnx:/parc.com/csl/ccn/Repos, plus the system defaults for
 * other fields.
 * This routine must be called after the btree code is initialized and capable
 * of returning content objects.
 * Sets the parsed_policy field of the handle to be the new policy.
 */
static int
load_policy(struct ccnr_handle *ccnr)
{
    int fd;
    ssize_t res;
    struct content_entry *content = NULL;
    const unsigned char *content_msg = NULL;
    struct ccn_parsed_ContentObject pco = {0};
    struct ccn_parsed_Link pl = {0};
    struct ccn_indexbuf *nc = NULL;
    struct ccn_charbuf *basename = NULL;
    struct ccn_charbuf *policy = NULL;
    struct ccn_charbuf *policy_cob = NULL;
    struct ccn_charbuf *policyFileName;
    const char *global_prefix;
    const unsigned char *buf = NULL;
    size_t length = 0;
    int segment = 0;
    int final = 0;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    
    policyFileName = ccn_charbuf_create();
    ccn_charbuf_putf(policyFileName, "%s/repoPolicy", ccnr->directory);
    ccnr->parsed_policy = ccnr_parsed_policy_create();
    fd = open(ccn_charbuf_as_string(policyFileName), O_RDONLY);
    if (fd >= 0) {
        ccnr->policy_link_cob = ccn_charbuf_create();
        ccn_charbuf_reserve(ccnr->policy_link_cob, 4096);   // limits the size of the policy link
        ccnr->policy_link_cob->length = 0;    // clear the buffer
        res = read(fd, ccnr->policy_link_cob->buf, ccnr->policy_link_cob->limit - ccnr->policy_link_cob->length);
        close(fd);
        if (res == -1) {
            r_init_fail(ccnr, __LINE__, "Error reading repoPolicy file.", errno);
            ccn_charbuf_destroy(&ccnr->policy_link_cob);
            ccn_charbuf_destroy(&policyFileName);
            return(-1);
        }
        ccnr->policy_link_cob->length = res;
        nc = ccn_indexbuf_create();
        res = ccn_parse_ContentObject(ccnr->policy_link_cob->buf,
                                      ccnr->policy_link_cob->length, &pco, nc);
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, ccnr->policy_link_cob->buf,
                                  pco.offset[CCN_PCO_B_Content],
                                  pco.offset[CCN_PCO_E_Content],
                                  &buf, &length);
        d = ccn_buf_decoder_start(&decoder, buf, length);
        res = ccn_parse_Link(d, &pl, NULL);
        if (res <= 0) {
            ccnr_msg(ccnr, "Policy link is malformed.");
            goto CreateNewPolicy;
        }
        basename = ccn_charbuf_create();
        ccn_charbuf_append(basename, buf + pl.offset[CCN_PL_B_Name],
                           pl.offset[CCN_PL_E_Name] - pl.offset[CCN_PL_B_Name]);
        ccnr->policy_name = ccn_charbuf_create(); // to detect writes to this name
        ccn_charbuf_append_charbuf(ccnr->policy_name, basename); // has version
        ccn_name_chop(ccnr->policy_name, NULL, -1); // get rid of version
        policy = ccn_charbuf_create();
        // if we fail to retrieve the link target, report and then create a new one
        do {
            ccn_name_append_numeric(basename, CCN_MARKER_SEQNUM, segment++);
            content = r_store_lookup_ccnb(ccnr, basename->buf, basename->length);
            if (content == NULL) {
                ccnr_debug_ccnb(ccnr, __LINE__, "policy lookup failed for", NULL,
                                basename->buf, basename->length);
                break;
            }
            ccn_name_chop(basename, NULL, -1);
            content_msg = r_store_content_base(ccnr, content);
            if (content_msg == NULL) {
                ccnr_debug_ccnb(ccnr, __LINE__, "Unable to read policy object", NULL,
                                basename->buf, basename->length);
                break;
            }
            res = ccn_parse_ContentObject(content_msg, r_store_content_size(ccnr, content), &pco, nc);
            res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, content_msg,
                                      pco.offset[CCN_PCO_B_Content],
                                      pco.offset[CCN_PCO_E_Content],
                                      &buf, &length);
            ccn_charbuf_append(policy, buf, length);
            final = ccn_is_final_pco(content_msg, &pco, nc);
        } while (!final && segment < 100);
        if (policy->length == 0) {
            ccnr_msg(ccnr, "Policy link points to empty or non-existent policy.");
            goto CreateNewPolicy;
        }
        if (segment >= 100) {
            r_init_fail(ccnr, __LINE__, "Policy link points to policy with too many segments.", 0);
            return(-1);
        }
        if (r_proto_parse_policy(ccnr, policy->buf, policy->length, ccnr->parsed_policy) < 0) {
            ccnr_msg(ccnr, "Policy link points to malformed policy.");
            goto CreateNewPolicy;
        }
        res = ccn_name_comp_get(content_msg, nc, nc->n - 3, &buf, &length);
        if (length != 7 || buf[0] != CCN_MARKER_VERSION) {
            ccnr_msg(ccnr, "Policy link points to unversioned policy.");
            goto CreateNewPolicy;
        }
        memmove(ccnr->parsed_policy->version, buf, sizeof(ccnr->parsed_policy->version));
        ccn_indexbuf_destroy(&nc);
        ccn_charbuf_destroy(&basename);
        ccn_charbuf_destroy(&policy);
        ccn_charbuf_destroy(&policyFileName);
        return (0);
    }
    
CreateNewPolicy:
    // clean up if we had previously done some allocation
    ccn_indexbuf_destroy(&nc);
    ccn_charbuf_destroy(&basename);
    ccn_charbuf_destroy(&policy);
    ccn_charbuf_destroy(&ccnr->policy_name);
    ccnr_msg(ccnr, "Creating new policy file.");
    // construct the policy content object
    global_prefix = getenv ("CCNR_GLOBAL_PREFIX");
    if (global_prefix != NULL)
        ccnr_msg(ccnr, "CCNR_GLOBAL_PREFIX=%s", global_prefix);
    else 
        global_prefix = "ccnx:/parc.com/csl/ccn/Repos";
    policy = ccn_charbuf_create();
    r_proto_policy_append_basic(ccnr, policy, "1.5", "Repository", global_prefix);
    r_proto_policy_append_namespace(ccnr, policy, "/");
    basename = ccn_charbuf_create();
    res = ccn_name_from_uri(basename, global_prefix);
    res |= ccn_name_from_uri(basename, "data/policy.xml");
    if (res < 0) {
        r_init_fail(ccnr, __LINE__, "Global prefix is not a valid URI", 0);
        return(-1);
    }
    ccnr->policy_name = ccn_charbuf_create(); // to detect writes to this name
    ccn_charbuf_append_charbuf(ccnr->policy_name, basename);
    ccn_create_version(ccnr->direct_client, basename, 0,
                       ccnr->starttime, ccnr->starttime_usec * 1000);
    policy_cob = ccnr_init_policy_cob(ccnr, ccnr->direct_client, basename,
                                      600, policy);
    // save the policy content object to the repository
    content = process_incoming_content(ccnr, ccnr->face0,
                                       (void *)policy_cob->buf,
                                       policy_cob->length, NULL);
    r_store_commit_content(ccnr, content);
    ccn_charbuf_destroy(&policy_cob);
    // make a link to the policy content object
    ccn_charbuf_destroy(&ccnr->policy_link_cob);
    ccnr->policy_link_cob = ccnr_init_policy_link_cob(ccnr, ccnr->direct_client,
                                                      basename);
    if (ccnr->policy_link_cob == NULL) {
        r_init_fail(ccnr, __LINE__, "Unable to create policy link object", 0);
        return(-1);
    }
    
    fd = open(ccn_charbuf_as_string(policyFileName), O_WRONLY | O_CREAT, 0666);
    if (fd < 0) {
        r_init_fail(ccnr, __LINE__, "Unable to open repoPolicy file for write", errno);
        return(-1);
    }
    lseek(fd, 0, SEEK_SET);
    res = write(fd, ccnr->policy_link_cob->buf, ccnr->policy_link_cob->length);
    if (res == -1) {
        r_init_fail(ccnr, __LINE__, "Unable to write repoPolicy file", errno);
        return(-1);
    }
    res = ftruncate(fd, ccnr->policy_link_cob->length);
    close(fd);
    if (res == -1) {
        r_init_fail(ccnr, __LINE__, "Unable to truncate repoPolicy file", errno);
        return(-1);
    }
    // parse the policy for later use
    if (r_proto_parse_policy(ccnr, policy->buf, policy->length, ccnr->parsed_policy) < 0) {
        r_init_fail(ccnr, __LINE__, "Unable to parse new repoPolicy file", 0);
        return(-1);
    }
    // get the pp->version from the policy_cob base name .../policy.xml/<ver>
    nc = ccn_indexbuf_create();
    ccn_name_split(basename, nc);
    res = ccn_name_comp_get(basename->buf, nc, nc->n - 2, &buf, &length);
    if (length != 7 || buf[0] != CCN_MARKER_VERSION) {
        r_init_fail(ccnr, __LINE__, "Unable to get repository policy object version", 0);
        return(-1);
    }
    memmove(ccnr->parsed_policy->version, buf, sizeof(ccnr->parsed_policy->version));
    ccn_indexbuf_destroy(&nc);
    ccn_charbuf_destroy(&basename);
    ccn_charbuf_destroy(&policy);
    ccn_charbuf_destroy(&policyFileName);
    return(0);
}

