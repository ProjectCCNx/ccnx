/*
 * ccnd_stats.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <sys/types.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/utsname.h>

#include <unistd.h>
#if !defined(HAVE_GETADDRINFO) || !defined(HAVE_GETNAMEINFO)
    #include "getaddrinfo.h"
#endif

#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/hashtb.h>
#include <ccn/uri.h>

#include "ccnd_private.h"

#define CRLF "\r\n"

struct ccnd_stats {
    long total_interest_counts;
    long total_flood_control;      /* done propagating, still recorded */
};

int
ccnd_collect_stats(struct ccnd *h, struct ccnd_stats *ans)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    long sum;
    for (sum = 0, hashtb_start(h->interestprefix_tab, e);
                                   e->data != NULL; hashtb_next(e)) {
        struct interestprefix_entry *ipe = e->data;
        struct propagating_entry *head = ipe->propagating_head;
        struct propagating_entry *p;
        if (head != NULL) {
            for (p = head->next; p != head; p = p->next) {
                sum += 1;
            }
        }
    }
    ans->total_interest_counts = sum;
    hashtb_end(e);
    for (sum = 0, hashtb_start(h->propagating_tab, e);
                                      e->data != NULL; hashtb_next(e)) {
        struct propagating_entry *pi = e->data;
        if (pi->interest_msg == NULL)
            sum += 1;
    }
    ans->total_flood_control = sum;
    hashtb_end(e);

    return(0);
}

static char *
collect_stats_html(struct ccnd *h)
{
    char *ans;
    struct ccnd_stats stats = {0};
    struct ccn_charbuf *b = ccn_charbuf_create();
    int pid;
    struct utsname un;
    const char *portstr;
    
    portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    if (portstr == NULL || portstr[0] == 0 || strlen(portstr) > 10)
        portstr = "4485";
    uname(&un);
    pid = getpid();
    
    ccnd_collect_stats(h, &stats);
    ccn_charbuf_putf(b,
        "<html>"
        "<head>"
        "<title>ccnd[%d]</title>"
        //"<meta http-equiv='refresh' content='3'>"
        "<style type='text/css'>"
        " p.header {color: white; background-color: blue} "
        "</style>"
        "</head>"
        "<body>"
        "<p class='header' width='100%%'>%s ccnd[%d] local port %s</p>"
        "<div><b>Content items:</b> %llu accessioned, %d stored, %d sparse, %lu duplicate, %lu sent</div>"
        "<div><b>Interests:</b> %d names, %ld pending, %ld propagating, %ld noted</div>"
        "<div><b>Interest totals:</b> %lu accepted, %lu dropped, %lu sent, %lu stuffed</div>"
        "<div><b>Active faces and listeners:</b> %d</div>"
        "</body>"
        "</html>",
        pid,
        un.nodename,
        pid,
        portstr,
        (unsigned long long)h->accession,
        hashtb_n(h->content_tab),
        hashtb_n(h->sparse_straggler_tab),
        h->content_dups_recvd,
        h->content_items_sent,
        hashtb_n(h->interestprefix_tab), stats.total_interest_counts,
        hashtb_n(h->propagating_tab) - stats.total_flood_control,
        stats.total_flood_control,
        h->interests_accepted, h->interests_dropped,
        h->interests_sent, h->interests_stuffed,
        hashtb_n(h->faces_by_fd) + hashtb_n(h->dgram_faces));
    ans = strdup((char *)b->buf);
    ccn_charbuf_destroy(&b);
    return(ans);
}

static const char *resp404 =
    "HTTP/1.1 404 Not Found" CRLF
    "Connection: close" CRLF CRLF;

static const char *resp405 =
    "HTTP/1.1 405 Method Not Allowed" CRLF
    "Connection: close" CRLF CRLF;

int
ccnd_stats_check_for_http_connection(struct ccnd *h)
{
    int res;
    int hdrlen;
    int fd;
    char *response = NULL;
    char buf[512] = "GET / ";
    struct linger linger = { .l_onoff = 1, .l_linger = 1 };
    struct timeval timeout = { .tv_sec = 0, .tv_usec = 100000 };
    
    if (h->httpd_listener_fd == -1)
        return(-1);
    fd = accept(h->httpd_listener_fd, NULL, 0);
    if (fd == -1) {
        perror("check_for_http_connection - accept");
        close(h->httpd_listener_fd);
        h->httpd_listener_fd = -1;
        return(-1);
    }
    response = collect_stats_html(h);
    /* Set linger to prevent quickly resetting the connection on close.*/
    res = setsockopt(fd, SOL_SOCKET, SO_LINGER, &linger, sizeof(linger));
    /* Set a receive timeout so we don't end up waiting for very long. */
    /* (This may fail on some platforms, if so we could block.) */
    res = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    res = read(fd, buf, sizeof(buf));
    if ((res == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) || res >= 6) {
        if (0 == memcmp(buf, "GET / ", 6)) {
            res = strlen(response);
            hdrlen = snprintf(buf, sizeof(buf),
                              "HTTP/1.1 200 OK" CRLF
                              "Content-Type: text/html; charset=utf-8" CRLF
                              "Connection: close" CRLF
                              "Content-Length: %d" CRLF CRLF,
                              res);
            write(fd, buf, hdrlen);
            write(fd, response, res);
        }
        else if (0 == memcmp(buf, "GET ", 4))
            write(fd, resp404, strlen(resp404));
        else
            write(fd, resp405, strlen(resp405));
    }
    close(fd);
    free(response);
    return(0);
}

int
ccnd_stats_httpd_start(struct ccnd *h)
{
    int res;
    int sock;
    int yes = 1;
    struct addrinfo hints = {0};
    struct addrinfo *ai = NULL;
    const char *portstr;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_PASSIVE;
    /*
     * XXX - use the tcp port corresponding to the configured udp port
     * for our httpd service.
     * This is probably wrong long-term, but handy for debugging multiple
     * ccnd instances on one machine.
     */
    portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    if (portstr == NULL || portstr[0] == 0 || strlen(portstr) > 10)
        portstr = "4485";
    res = getaddrinfo(NULL, portstr, &hints, &ai);
    if (res == -1) {
        perror("ccnd_stats_httpd_listen: getaddrinfo");
        return(-1);
    }
    sock = socket(ai->ai_family, SOCK_STREAM, 0);
    if (sock == -1) {
        perror("ccnd_stats_httpd_listen: getaddrinfo");
        return(-1);
    }
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    res = bind(sock, ai->ai_addr, ai->ai_addrlen);
    if (res == -1) {
        perror("ccnd_stats_httpd_listen: bind");
        close(sock);
        return(-1);
    }
    //res = fcntl(sock, F_SETFL, O_NONBLOCK);
    if (res == -1) {
        perror("ccnd_stats_httpd_listen: fcntl");
        close(sock);
        return(-1);
    }
    res = listen(sock, 30);
    if (res == -1) {
        perror("ccnd_stats_httpd_listen: listen");
        close(sock);
        return(-1);
    }
    freeaddrinfo(ai);
    ai = NULL;
    h->httpd_listener_fd = sock;
    return(0);
}

/* ccnd_msg may migrate into a different place ... */
void
ccnd_msg(struct ccnd *h, const char *fmt, ...)
{
    struct timeval t;
    va_list ap;
    struct ccn_charbuf *b;
    if (h != NULL && h->debug == 0)
        return;
    b = ccn_charbuf_create();
    gettimeofday(&t, NULL);
    ccn_charbuf_putf(b, "%d.%06u ccnd[%d]: %s\n",
        (int)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), fmt);
    va_start(ap, fmt);
    vfprintf(stderr, (const char *)b->buf, ap);
    ccn_charbuf_destroy(&b);
}

void
ccnd_debug_ccnb(struct ccnd *h,
                int lineno,
                const char *msg,
                struct face *face,
                const unsigned char *ccnb,
                size_t ccnb_size)
{
    struct ccn_charbuf *c = ccn_charbuf_create();
    struct ccn_parsed_interest pi;
    const unsigned char *nonce = NULL;
    size_t nonce_size = 0;
    size_t i;
    ccn_charbuf_putf(c, "debug.%d %s ", lineno, msg);
    if (face != NULL)
        ccn_charbuf_putf(c, "%u ", face->faceid);
    ccn_uri_append(c, ccnb, ccnb_size, 1);
    ccn_charbuf_putf(c, " (%u bytes)", (unsigned)ccnb_size);
    if (ccn_parse_interest(ccnb, ccnb_size, &pi, NULL) >= 0) {
        ccn_ref_tagged_BLOB(CCN_DTAG_Nonce, ccnb,
                  pi.offset[CCN_PI_B_Nonce],
                  pi.offset[CCN_PI_E_Nonce],
                  &nonce,
                  &nonce_size);
        if (nonce_size > 0) {
            ccn_charbuf_putf(c, " ");
            for (i = 0; i < nonce_size; i++)
                ccn_charbuf_putf(c, "%02X", nonce[i]);
        }
    }
    ccnd_msg(h, "%s", ccn_charbuf_as_string(c));
    ccn_charbuf_destroy(&c);
}
