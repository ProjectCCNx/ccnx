#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <net/if.h>

#ifndef AI_ADDRCONFIG
#define AI_ADDRCONFIG 0 /*IEEE Std 1003.1-2001/Cor 1-2002, item XSH/TC1/D6/20*/
#endif

static const char *this_program = "flowtest";
struct options {
    const char *sourceportstr;
    const char *portstr;
    const char *remotehost;
    int n_packets;
    int pipeline;
    int echo_server;
    size_t payload_size;
    int verbose;
};

static void report(const char *format, ...);
static void fatal(int lineno, const char *format, ...);

static void
usage(char *prog) {
    fprintf(stderr, "Usage: %s "
            "[-c n_packets ] "
            "[-l pipeline_limit ] "
            "[-p source_port] "
            "[-s bytes ] "
            "[ -v ] "
            "[ -e echo_responder_port ] "
            "remotehost [port]\n",
            prog);
    exit(1);
}

static void
process_options(int argc, char *const argv[], struct options *options)
{
    int c;

    options->sourceportstr = "0";
    options->portstr = "7"; /* echo */
    options->verbose = 0;
    options->remotehost = NULL;
    options->n_packets = 1;
    options->echo_server = 0;
    options->payload_size = 104;
    options->pipeline = 0;
    
    for (optind = 1; (c = getopt(argc, argv, "c:e:hl:p:s:v")) != -1;) {
        switch (c) {
            case 'c':
                options->n_packets = atol(optarg);
                if (options->n_packets < 1 || options->n_packets > 1000000)
                    fatal(__LINE__, "-c value invalid\n");
                break;
            case 'e':
                options->sourceportstr = optarg;
                options->echo_server = 1;
                options->payload_size = 8800;
                break;
            case 'l':
                options->pipeline = atol(optarg);
                if (options->pipeline > 255) {
                    options->pipeline = 255;
                    report("limiting -l %d", options->pipeline);
                }
                break;
            case 'p':
                options->sourceportstr = optarg;
                break;
            case 's':
                options->payload_size = atol(optarg);
                if (options->payload_size < 1 || options->payload_size > 65000)
                    fatal(__LINE__, "-s value invalid\n");
                break;
            case 'v':
                options->verbose++;
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    if (argv[optind] == NULL)
        usage(argv[0]);
    options->remotehost = argv[optind++];
    if (argv[optind] != NULL)
        options->portstr = argv[optind++];
}

static void
report(const char *format, ...)
{
    char xfmt[128];
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    snprintf(xfmt, sizeof(xfmt), "%u.%06u %s[%d]: %s\n",
            (unsigned)t.tv_sec,
            (unsigned)t.tv_usec,
            this_program,
            getpid(),
            format);
    vfprintf(stdout, xfmt, ap);
}

static void
fatal(int lineno, const char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    fprintf(stderr, "%u.%06u %s[%d] line %d: ",
            (unsigned)t.tv_sec,
            (unsigned)t.tv_usec,
            this_program,
            getpid(),
            lineno);
    vfprintf(stderr, format, ap);
    exit(1);
}

ssize_t
send_remote(int s, struct addrinfo *r,
            unsigned char *buf, size_t start, size_t length)
{
    ssize_t res;
    int flags = 0;

    res = sendto(s, buf + start, length, flags, r->ai_addr, r->ai_addrlen);
    return (res);
}

#ifndef AI_NUMERICSERV
#define AI_NUMERICSERV 0
#endif

struct payload {
    unsigned char mod256;
    char decimal[11];
    uint32_t seqno;
};

int
main(int argc, char *const argv[])
{
    int res;
    int sock;
    char canonical_remote[NI_MAXHOST] = "";
    char canonical_service[NI_MAXSERV] = "";
    struct addrinfo *raddrinfo = NULL;
    struct addrinfo *laddrinfo = NULL;
    struct addrinfo hints = {0};
    struct sockaddr_storage responder = {0};
    socklen_t responder_size;
    ssize_t dres;
    struct options option_space = {0};
    struct options *opt = &option_space;
    int i;
    size_t size;
    struct payload *buf = NULL;
    struct timeval timeout = {0};
    struct timeval starttime = {0};
    struct timeval stoptime = {0};
    int missing = 0;

    process_options(argc, argv, opt);

    size = opt->payload_size;
    
    buf = calloc(1, size + sizeof(struct payload));
    
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_flags = AI_ADDRCONFIG;
    hints.ai_flags |= AI_NUMERICSERV;

    res = getaddrinfo(opt->remotehost, opt->portstr, &hints, &raddrinfo);
    if (res != 0 || raddrinfo == NULL)
        fatal(__LINE__, "getaddrinfo(\"%s\", \"%s\", ...): %s\n",
              opt->remotehost, opt->portstr, gai_strerror(res));
    getnameinfo(raddrinfo->ai_addr, raddrinfo->ai_addrlen,
                canonical_remote, sizeof(canonical_remote),
                canonical_service, sizeof(canonical_service), NI_NUMERICSERV);

    hints.ai_family = raddrinfo->ai_family;
    hints.ai_flags = AI_PASSIVE;
    hints.ai_flags |= AI_NUMERICSERV;

    res = getaddrinfo(NULL, opt->sourceportstr, &hints, &laddrinfo);
    if (res != 0 || laddrinfo == NULL)
        fatal(__LINE__, "getaddrinfo(NULL, %s, ...): %s\n",
              opt->sourceportstr, gai_strerror(res));

    sock = socket(raddrinfo->ai_family, raddrinfo->ai_socktype, 0);
    if (sock == -1)
        fatal(__LINE__, "socket: %s\n", strerror(errno));

    if (opt->verbose > 0 && !opt->echo_server)
        report("contacting %s:%s", canonical_remote, canonical_service);

    res = bind(sock, laddrinfo->ai_addr, laddrinfo->ai_addrlen);

    if (res == -1)
        fatal(__LINE__, "bind(sock, local...): %s\n", strerror(errno));

    gettimeofday(&starttime, NULL);
    if (opt->echo_server) {
        if (opt->verbose > 0)
            report("echo server started, max packet count %d", opt->n_packets);
        for (i = 1; i <= opt->n_packets; i++) {
            responder_size = sizeof(responder);
            dres = recvfrom(sock, buf, size, /*flags*/0,
                            (struct sockaddr *)&responder, &responder_size);
            if (opt->verbose > 1)
                report("%ld byte packet received (echo)", (long)dres);
            if (dres > 0) {
                dres = sendto(sock, buf, dres, /*flags*/0,
                              (struct sockaddr *)&responder, responder_size);
                if (dres == -1)
                    report("sendto(sock, buf, %ld, ...): %s",
                           (long)size, strerror(errno));
            }
        }
    }
    else {
        int j;
        int k;
        int running = 1;
        int maxburst = 1;
        int expect[256]; /* maps mod256 seqno to seqno */
        int curwindow = 0;
        memset(expect, ~0, sizeof(expect));
        timeout.tv_usec = 65535;
        res = setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        if (res == -1)
            report("setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, ...): %s", strerror(errno));
        for (i = 0, j = 0; running && j + missing < opt->n_packets;) {
            for (k = 0; k < maxburst; k++) {
                if (i <= j + missing + curwindow && i < opt->n_packets) {
                    i++;
                    memset(buf, i & 0xff, size);
                    if (size >= sizeof(struct payload)) {
                        buf->seqno = htonl(i);
                        snprintf(buf->decimal, sizeof(buf->decimal), "%10u", i);
                        if (expect[i & 0xff] != -1) {
                            report("missed %d", expect[i & 0xff]);
                            missing++;
                        }
                        expect[i & 0xff] = i;
                    }
                    dres = sendto(sock, buf, size, /*flags*/0,
                                  raddrinfo->ai_addr, raddrinfo->ai_addrlen);
                    if (dres == -1)
                        report("sendto(sock, buf, %ld, ...): %s",
                               (long)size, strerror(errno));
                    else if (opt->verbose > 1)
                        report("%ld byte packet sent %d(%02x)", (long)dres,
                               i, (unsigned)buf->mod256);
                }
            }
            responder_size = sizeof(responder);
            dres = recvfrom(sock, buf, size + 4, /*flags*/0,
                            (struct sockaddr *)&responder, &responder_size);
            if (dres > 0) {
                if (dres >= sizeof(struct payload)) {
                    int m = ntohl(buf->seqno);
                    if (expect[buf->mod256] == m) {
                        expect[buf->mod256] = -1;
                        if (m == opt->n_packets)
                            running = 0;
                    }
                    else {
                        report("%ld byte packet discarded, seqno %d not expected", (long)dres, m);
                        maxburst = 0;
                        continue;
                    }
                }
                else if (expect[buf->mod256] != -1) {
                    /* can't be quite so careful for short packets */
                    expect[buf->mod256] = -1;
                }
                else {
                    report("%ld byte packet discarded", (long)dres);
                    maxburst = 0;
                    continue;
                }
                j++;
                if (maxburst == 2 && timeout.tv_usec > 15) {
                    timeout.tv_usec -= ((unsigned)timeout.tv_usec) >> 4;
                    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
                }
                maxburst = 2;
                if (opt->verbose > 0 && curwindow + 1 == opt->pipeline) {
                    curwindow++;
                    report("(%d sent, %d recvd, %d missing, %d curwindow)",
                        i, j, missing, curwindow);
                }
                else if (curwindow < opt->pipeline)
                    curwindow++;
                if (opt->verbose > 1)
                    report("%ld byte packet received (%02x)", (long)dres,
                           (unsigned)buf->mod256);
            }
            if (dres == -1) {
                if (maxburst == 1)
                    curwindow = 0;
                maxburst = 1;
                if (timeout.tv_usec < 500000)
                    timeout.tv_usec *= 2;
                else {
                    for (k = i - 255; k <= i; k++) {
                        if (expect[k & 0xff] != -1) {
                            if (opt->verbose > 0)
                                report("missed seqno %d (%d sent, %d recvd)",
                                       expect[k & 0xff], i, j);
                            expect[k & 0xff] = -1;
                            missing++;
                            break;
                        }
                    }
                    timeout.tv_usec = 999999;
                }
                if (opt->verbose > 0)
                    report("setting timeout to %d us (%d sent, %d recvd, %d curwindow)",
                        timeout.tv_usec, i, j, curwindow);
                setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            }
        }
        for (k = 0; k < 256; k++) {
            if (expect[k] != -1) {
                if (opt->verbose > 0)
                    report("missed seqno %d", expect[k]);
                missing++;
            }
        }
        if (opt->verbose > 0)
            report("%d missing, adjusted timeout %d us", missing, (int)timeout.tv_usec);
    }
    gettimeofday(&stoptime, NULL);
    if (opt->verbose > 0)
        report("done");
    close(sock);
    free(buf);
    buf = NULL;
    freeaddrinfo(raddrinfo);
    freeaddrinfo(laddrinfo);
    exit(0);
}
