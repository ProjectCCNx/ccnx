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

#include <sync/SyncBase.h>

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
#include "ccnr_util.h"

static int load_policy(struct ccnr_handle *h, struct ccnr_parsed_policy *pp);
static int merge_files(struct ccnr_handle *h);

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
 * Create a new ccnr instance
 * @param progname - name of program binary, used for locating helpers
 * @param logger - logger function
 * @param loggerdata - data to pass to logger function
 */
PUBLIC struct ccnr_handle *
r_init_create(const char *progname, ccnr_logger logger, void *loggerdata)
{
    char *sockname;
    const char *portstr;
    const char *listen_on;
    struct ccnr_handle *h;
    struct hashtb_param param = {0};
    struct ccn_charbuf *cb;
    struct ccn_charbuf *basename;
    struct ccnr_parsed_policy *pp = NULL;
    
    sockname = r_net_get_local_sockname();
    h = calloc(1, sizeof(*h));
    if (h == NULL)
        return(h);
    h->notify_after = CCNR_MAX_ACCESSION;
    h->logger = logger;
    h->loggerdata = loggerdata;
    h->appnonce = &r_fwd_append_plain_nonce;
    h->logpid = (int)getpid();
    h->progname = progname;
    h->debug = -1;
    h->skiplinks = ccn_indexbuf_create();
    h->face_limit = 10; /* soft limit */
    h->fdholder_by_fd = calloc(h->face_limit, sizeof(h->fdholder_by_fd[0]));
    param.finalize_data = h;
    param.finalize = &r_fwd_finalize_nameprefix;
    h->nameprefix_tab = hashtb_create(sizeof(struct nameprefix_entry), &param);
    param.finalize = &r_fwd_finalize_propagating;
    h->propagating_tab = hashtb_create(sizeof(struct propagating_entry), &param);
    param.finalize = 0;
    h->enum_state_tab = hashtb_create(sizeof(struct enum_state), NULL); // XXX - do we need finalization? Perhaps
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
    h->debug = 1; /* so that we see any complaints */
    h->debug = r_init_debug_getenv(h, "CCNR_DEBUG");
    h->syncdebug = r_init_debug_getenv(h, "SYNC_DEBUG");
    h->directory = getenv("CCNR_DIRECTORY");
    if (h->directory == NULL || h->directory[0] == 0)
        h->directory = ".";
    portstr = getenv("CCNR_STATUS_PORT");
    if (portstr == NULL || portstr[0] == 0 || strlen(portstr) > 10)
        portstr = "";
    h->portstr = portstr;
    ccnr_msg(h, "CCNR_DEBUG=%d CCNR_DIRECTORY=%s CCNR_STATUS_PORT=%s", h->debug, h->directory, h->portstr);
    listen_on = getenv("CCNR_LISTEN_ON");
    if (listen_on != NULL && listen_on[0] != 0)
        ccnr_msg(h, "CCNR_LISTEN_ON=%s", listen_on);
    h->appnonce = &r_fwd_append_debug_nonce;
     
    if (ccnr_init_repo_keystore(h, NULL) < 0) {
        h->running = -1;
        goto Bail;
    }
    r_util_reseed(h);
    r_store_init(h);
    if (h->running == -1) goto Bail;
    if (h->face0 == NULL) {
        struct fdholder *fdholder;
        fdholder = calloc(1, sizeof(*fdholder));
        fdholder->recv_fd = -1;
        fdholder->sendface = 0;
        fdholder->flags = (CCNR_FACE_GG | CCNR_FACE_NORECV);
        h->face0 = fdholder;
    }
    r_io_enroll_face(h, h->face0);
    ccnr_direct_client_start(h);
    if (ccn_connect(h->direct_client, NULL) != -1) {
        struct fdholder *fdholder;
        fdholder = r_io_record_fd(h, ccn_get_connection_fd(h->direct_client), "CCND", 5, CCNR_FACE_CCND | CCNR_FACE_LOCAL);
        if (fdholder == NULL) abort();
        ccnr_uri_listen(h, h->direct_client, "ccnx:/%C1.M.S.localhost/%C1.M.SRV/repository",
                        &ccnr_answer_req, OP_SERVICE);
        ccnr_uri_listen(h, h->direct_client, "ccnx:/%C1.M.S.neighborhood/%C1.M.SRV/repository",
                        &ccnr_answer_req, OP_SERVICE);
    }
    else
        ccn_disconnect(h->direct_client); // Apparently ccn_connect error case needs work.
    h->sync_handle = SyncNewBase(h, h->direct_client, h->sched);
    pp = ccnr_parsed_policy_create();
    load_policy(h, pp);
    h->parsed_policy = pp;
    basename = ccn_charbuf_create();
    ccn_name_init(basename);
    ccn_name_from_uri(basename, (char *)pp->store->buf + pp->global_prefix_offset);
    ccn_name_from_uri(basename, "data/policy.xml");
    h->policy_name = basename;
    cb = ccn_charbuf_create();
    ccn_uri_append(cb, basename->buf, basename->length, 0);
    ccnr_uri_listen(h, h->direct_client, ccn_charbuf_as_string(cb),
                   &ccnr_answer_req, OP_POLICY | 3);
    ccn_charbuf_destroy(&cb);
    r_net_listen_on(h, listen_on);
    r_fwd_age_forwarding_needed(h);
    ccnr_internal_client_start(h);
    r_proto_init(h);
    r_proto_activate_policy(h, pp);
    if (merge_files(h) == -1)
        r_init_fail(h, __LINE__, "Unable to merge additional repository data files.", errno);
    if (h->running == -1) goto Bail;
    SyncInit(h->sync_handle);
Bail:
    free(sockname);
    sockname = NULL;
    if (h->running == -1)
        r_init_destroy(&h);
    return(h);
}

void
r_init_fail(struct ccnr_handle *ccnr, int line, const char *culprit, int err)
{
    ccnr_msg(ccnr, "Startup failure %d %s - %s", line, culprit,
             (err > 0) ? strerror(err) : "");
    ccnr->running = -1;
}

/**
 * Destroy the ccnr instance, releasing all associated resources.
 */
PUBLIC void
r_init_destroy(struct ccnr_handle **pccnr)
{
    struct ccnr_handle *h = *pccnr;
    if (h == NULL)
        return;
    r_io_shutdown_all(h);
    ccnr_internal_client_stop(h);
    ccnr_direct_client_stop(h);
    ccn_schedule_destroy(&h->sched);
    hashtb_destroy(&h->propagating_tab);
    hashtb_destroy(&h->nameprefix_tab);
    hashtb_destroy(&h->content_by_accession_tab);
    hashtb_destroy(&h->enum_state_tab);
    
    SyncFreeBase(&h->sync_handle);
    
    r_store_final(h);

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
        h->content_by_cookie_window = 0;
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
    if (h->face0 != NULL) {
        ccn_charbuf_destroy(&h->face0->inbuf);
        ccn_charbuf_destroy(&h->face0->outbuf);
        free(h->face0);
        h->face0 = NULL;
    }
    free(h);
    *pccnr = NULL;
}

static int
map_and_process_file(struct ccnr_handle *h, struct ccn_charbuf *filename, int add_content)
{
    int res = 0;
    int dres;
    struct stat statbuf;
    unsigned char *mapped_file;
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
    mapped_file = mmap(NULL, statbuf.st_size, PROT_READ, MAP_FILE | MAP_SHARED, fd, 0);
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
            content = process_incoming_content(h, fdholder, msg + d->index - dres, dres);
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
        res = map_and_process_file(h, filename, 0);
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
        res = map_and_process_file(h, filename, 1);
        if (res < 0) {
            ccnr_msg(h, "Error in phase 2 incorporating repository file %s", ccn_charbuf_as_string(filename));
             return (-1);
        }
    }
    
    for (i = last_file; i > 1; --i) {
        filename->length = 0;
        ccn_charbuf_putf(filename, "%s/repoFile%d", h->directory, i);
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
    struct ccn_charbuf *cob = ccn_charbuf_create();
    int res;
    
    res = ccn_get_public_key(h, NULL, pubid, pubkey);
    if (res < 0) abort();
    ccn_charbuf_append_charbuf(name, basename);
    ccn_create_version(h, name, 0, ccnr->starttime, ccnr->starttime_usec * 1000);
    ccn_name_from_uri(name, "%00");
    sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    sp.type = CCN_CONTENT_DATA;
    sp.freshness = freshness;
    res = ccn_sign_content(h, cob, name, &sp, content->buf, content->length);
    if (res != 0) abort();
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&pubid);
    ccn_charbuf_destroy(&pubkey);
    ccn_charbuf_destroy(&keyid);
    ccn_charbuf_destroy(&sp.template_ccnb);
    return(cob);
}

static int
load_policy(struct ccnr_handle *ccnr, struct ccnr_parsed_policy *pp)
{
    int fd;
    ssize_t res;
    struct fdholder *fdholder = NULL;
    struct content_entry *content = NULL;
    const unsigned char *buf = NULL;
    size_t length = 0;

    while (content == NULL) {
        fd = r_io_open_repo_data_file(ccnr, "repoPolicy", 0);
        if (fd >= 0) {
            fdholder = r_io_fdholder_from_fd(ccnr, fd);
            if (fdholder->inbuf == NULL)
                fdholder->inbuf = ccn_charbuf_create();
            fdholder->inbuf->length = 0;    // clear the buffer
            ccn_charbuf_reserve(fdholder->inbuf, 8800);   // limits the size of the policy file
            res = read(fdholder->recv_fd, fdholder->inbuf->buf, fdholder->inbuf->limit - fdholder->inbuf->length);
            if (res == -1) {
                ccnr_msg(ccnr, "read policy: %s (errno = %d)", strerror(errno), errno);
                abort();
            }
            fdholder->inbuf->length = res;
            content = process_incoming_content(ccnr, fdholder, fdholder->inbuf->buf, fdholder->inbuf->length);
            if (content == NULL) {
                ccnr_msg(ccnr, "Unable to process repository policy object");
                abort();
            }
            r_store_content_field_access(ccnr, content, CCN_DTAG_Content, &buf, &length);
            if (r_proto_parse_policy(ccnr, buf, length, pp) < 0) {
                ccnr_msg(ccnr, "Malformed policy");
                abort();
            }
            r_store_commit_content(ccnr, content);
        }
        else {
            struct ccn_charbuf *policy = ccn_charbuf_create();
            struct ccn_charbuf *policy_cob;
            struct ccn_charbuf *basename = ccn_charbuf_create();
            const char *global_prefix;
            
            global_prefix = getenv ("CCNR_GLOBAL_PREFIX");
            if (global_prefix != NULL)
                ccnr_msg(ccnr, "CCNR_GLOBAL_PREFIX=%s", global_prefix);
            else 
                global_prefix = "ccnx:/parc.com/csl/ccn/Repos";
            r_proto_policy_append_basic(ccnr, policy, "2.0", "Repository",
                                        global_prefix);
            r_proto_policy_append_namespace(ccnr, policy, "/");
            ccn_name_from_uri(basename, global_prefix);
            ccn_name_from_uri(basename, "data/policy.xml");
            policy_cob = ccnr_init_policy_cob(ccnr, ccnr->direct_client, basename,
                                              600, policy);
            fd = r_io_open_repo_data_file(ccnr, "repoPolicy", 1);
            if (fd < 0) {
                ccnr_msg(ccnr, "open policy: %s (errno = %d)", strerror(errno), errno);
                abort();
            }
            
            res = write(fd, policy_cob->buf, policy_cob->length);
            if (res == -1) {
                ccnr_msg(ccnr, "write policy: %s (errno = %d)", strerror(errno), errno);
                abort();
            }
            ccn_charbuf_destroy(&policy_cob);
            ccn_charbuf_destroy(&basename);
            ccn_charbuf_destroy(&policy);
        }
        r_io_shutdown_client_fd(ccnr, fd);
    }
    r_store_content_change_flags(content, CCN_CONTENT_ENTRY_PRECIOUS, 0);
    
    return(0);
}

