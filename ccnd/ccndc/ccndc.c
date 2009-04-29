/*
 * Watch interests and inject interests wrapped with routing
 * back into the ccnd
 */

/*
 * parse input lines
 *	ccn:/prefix  udp|tcp hostname|ipaddr port
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netdb.h>

#if defined(NEED_GETADDRINFO_COMPAT)
    #include "getaddrinfo.h"
    #include "dummyin6.h"
#endif
#ifndef AI_ADDRCONFIG
#define AI_ADDRCONFIG 0 /*IEEE Std 1003.1-2001/Cor 1-2002, item XSH/TC1/D6/20*/
#endif

#include <ccn/ccn.h>
#include <ccn/uri.h>

#define DEFAULTPORTSTRING "4485"

struct ribline {
    struct ccn_charbuf *name;
    struct addrinfo *addrinfo;
};

struct routing {
    int nEntries;
    struct ribline rib[1024];
};

void
ccndc_fatal(int line, char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d ccndc[%d] line %d: ", (int)t.tv_sec, (unsigned)t.tv_usec, getpid(), line);
    vfprintf(stderr, format, ap);
    exit(1);
}

void
ccndc_warn(int line, char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d ccndc[%d] line %d: ", (int)t.tv_sec, (unsigned)t.tv_usec, getpid(), line);
    vfprintf(stderr, format, ap);
}

int
ccn_inject_create(struct ccn_charbuf *c,
                      const int sotype,
                      const struct sockaddr *addr,
                      const socklen_t addr_size,
                      const unsigned char *interest,
                      size_t interest_size)
{
    unsigned char *ucp = NULL;
    int res;

    res = ccn_charbuf_append_tt(c, CCN_DTAG_Inject, CCN_DTAG);
    res |= ccn_charbuf_append_tt(c, CCN_DTAG_SOType, CCN_DTAG);
    res |= ccn_charbuf_append_non_negative_integer(c, sotype);
    res |= ccn_charbuf_append_closer(c); /* </SOtype> */
    res |= ccn_charbuf_append_tt(c, CCN_DTAG_Address, CCN_DTAG);
    res |= ccn_charbuf_append_tt(c, addr_size, CCN_BLOB);
    ucp = ccn_charbuf_reserve(c, addr_size);
    memcpy(ucp, addr, addr_size);
    c->length += addr_size;
    res |= ccn_charbuf_append_closer(c); /* </Address> */
    ucp = ccn_charbuf_reserve(c, interest_size);
    memcpy(ucp, interest, interest_size);
    c->length += interest_size;
    res |= ccn_charbuf_append_closer(c); /* </Inject> */
    return (res);
}

enum ccn_upcall_res
incoming_interest(
                  struct ccn_closure *selfp,
                  enum ccn_upcall_kind kind,
                  struct ccn_upcall_info *info)
{
    struct routing *rt = selfp->data;
    const unsigned char *ccnb = info->interest_ccnb;
    struct ccn_parsed_interest *pi = info->pi;
    int i;
    int res;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind != CCN_UPCALL_INTEREST || rt == NULL)
        return(CCN_UPCALL_RESULT_ERR);
  
    for (i = 0; i < rt->nEntries; i++) {
        int name = pi->offset[CCN_PI_B_Name];
        int ccnb_size = pi->offset[CCN_PI_E];
        int inlength = pi->offset[CCN_PI_E_Name] - pi->offset[CCN_PI_B_Name];
        int nlength = rt->rib[i].name->length;

        if (inlength >= nlength && 0 == memcmp(rt->rib[i].name->buf, &ccnb[name], nlength - 1)) {
            struct ccn_charbuf *inject = ccn_charbuf_create();
            int socktype = rt->rib[i].addrinfo->ai_socktype;
            socklen_t addr_size = rt->rib[i].addrinfo->ai_addrlen;
            struct sockaddr *addr = rt->rib[i].addrinfo->ai_addr;

            res = ccn_inject_create(inject, socktype, addr, addr_size, ccnb, ccnb_size);
            if (res == 0)
                res = ccn_put(info->h, inject->buf, inject->length);
            if (res != 0) ccndc_warn(__LINE__, "ccn_put failed\n");
            ccn_charbuf_destroy(&inject);
            break;
        }
    }
    return(CCN_UPCALL_RESULT_OK);
}

static void
usage(const char *progname)
{
        fprintf(stderr,
                "%s -f configfile\n"
                " Reads configfile and injects routing information "
                "for interest packets that match configured prefixes\n",
                progname);
        exit(1);
}

static int
read_configfile(const char *filename, struct routing *rt)
{
    int res;
    char buf[256], strtokbuf[256];
    int configerrors = 0;
    FILE *cfg;
    struct ccn_charbuf *name = NULL;
    int socktype = 0;
    char *rhostname;
    char *rhostportstring;
    int rhostport;
    struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_flags = AI_ADDRCONFIG};
    struct addrinfo *raddrinfo = NULL;
    const char *seps = " \t\n";
    char *cp = NULL;
    char *last = NULL;

    cfg = fopen(filename, "r");
    if (cfg == NULL)
        ccndc_fatal(__LINE__, "%s (%s)\n", strerror(errno), filename);

    while (fgets((char *)buf, sizeof(buf), cfg)) {
        int len;
        len = strlen(buf);
        if (buf[0] == '#' || len == 0)
            continue;
        if (buf[len - 1] == '\n')
            buf[len - 1] = '\0';
        memcpy(strtokbuf, buf, sizeof(buf));
        cp = index(strtokbuf, '#');
        if (cp != NULL)
            *cp = '\0';
        cp = strtok_r(strtokbuf, seps, &last);
        if (cp == NULL)
            continue;

        name = ccn_charbuf_create();
        res = ccn_name_from_uri(name, cp);
        if (res < 0) {
            ccndc_warn(__LINE__, "Parse error, bad CCN URI '%s'\n", cp);
            configerrors--;
            continue;
        }
        cp = strtok_r(NULL, seps, &last);
        if (cp == NULL) {
            ccndc_warn(__LINE__, "Parse error, missing address type in %s\n", buf);
            configerrors--;
            continue;
        }
        if (strcmp(cp, "udp") == 0)
            socktype = SOCK_DGRAM;
        else if (strcmp(cp, "tcp") == 0)
            socktype = SOCK_STREAM;
        else {
            ccndc_warn(__LINE__, "Parse error, unrecognized address type '%s'\n", cp);
            configerrors--;
            continue;
        }
        rhostname = strtok_r(NULL, seps, &last);
        if (rhostname == NULL) {
            ccndc_warn(__LINE__, "Parse error, missing hostname in %s\n", buf);
            configerrors--;
            continue;
        }
        rhostportstring = strtok_r(NULL, seps, &last);
        if (rhostportstring == NULL) rhostportstring = DEFAULTPORTSTRING;
        rhostport = atoi(rhostportstring);
        if (rhostport <= 0 || rhostport > 65535) {
            ccndc_warn(__LINE__, "Parse error, invalid port %s\n", rhostportstring);
            configerrors--;
            continue;
        }
        hints.ai_socktype = socktype;
        res = getaddrinfo(rhostname, rhostportstring, &hints, &raddrinfo);
        if (res != 0 || raddrinfo == NULL) {
            ccndc_warn(__LINE__, "getaddrinfo: %s\n", gai_strerror(res));
            configerrors--;
            continue;
        }

        /* we have successfully read a config file line */
        rt->rib[rt->nEntries].name = name;
        rt->rib[rt->nEntries].addrinfo = raddrinfo;
        rt->nEntries++;

    }
    fclose(cfg);
    return (configerrors);
}
int
main(int argc, char **argv)
{
    const char *progname = argv[0];
    const char *configfile = NULL;
    struct ccn *ccn = NULL;
    int i;
    int res;
    struct routing rt = { 0 };
    struct ccn_closure in_interest = {.p=&incoming_interest, .data=&rt};

    while ((res = getopt(argc, argv, "f:h")) != -1) {
        switch (res) {
            case 'f':
                configfile = optarg;
                break;
            default:
            case 'h':
                usage(progname);
                break;
        }
    }

    if (configfile == NULL) {
        usage(progname);
    }

    res = read_configfile(configfile, &rt);
    if (res < 0)
        ccndc_fatal(__LINE__, "Error(s) in configuration file\n");


    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1)
        ccndc_fatal(__LINE__, "%s connecting to ccnd\n", strerror(errno));
    
    /* Set up a handler for interests */
    ccn_set_default_interest_handler(ccn, &in_interest);
    
    for (i = 0; i < 1000; i++) {
        ccn_run(ccn, 10000);
    }
    
    ccn_destroy(&ccn);
    exit(0);
}
