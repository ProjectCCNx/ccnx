/*
 * ccnd_stats.c
 *  
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc. All rights reserved.
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
#include <time.h>
#include <unistd.h>

#if defined(NEED_GETADDRINFO_COMPAT)
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
    unsigned i;
    for (sum = 0, hashtb_start(h->nameprefix_tab, e);
                                         e->data != NULL; hashtb_next(e)) {
        struct nameprefix_entry *ipe = e->data;
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
    /* Do a consistency check on pending interest counts */
    for (sum = 0, i = 0; i < h->face_limit; i++) {
        struct face *face = h->faces_by_faceid[i];
        if (face != NULL)
            sum += face->pending_interests;
    }
    if (sum != ans->total_interest_counts)
        ccnd_msg(h, "ccnd_collect_stats found inconsistency %ld != %ld\n",
            (long)sum, (long)ans->total_interest_counts);
    return(0);
}

static void
collect_faces_html(struct ccnd *h, struct ccn_charbuf *b)
{
    int i;
    ccn_charbuf_putf(b, "<h4>Faces</h4>");
    ccn_charbuf_putf(b, "<ul>");
    for (i = 0; i < h->face_limit; i++) {
        struct face *face = h->faces_by_faceid[i];
        if (face != NULL) {
            ccn_charbuf_putf(b, "<li>");
            ccn_charbuf_putf(b, " <b>face:</b> %u <b>flags:</b> 0x%x",
                             face->faceid, face->flags);
            ccn_charbuf_putf(b, " <b>pending:</b> %d",
                             face->pending_interests);
            if (face->recvcount != 0)
                ccn_charbuf_putf(b, " <b>activity:</b> %d",
                                 face->recvcount);
            ccn_charbuf_putf(b, "</li>");
        }
    }
    ccn_charbuf_putf(b, "</ul>");
}

static void
collect_forwarding_html(struct ccnd *h, struct ccn_charbuf *b)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_forwarding *f;
    int res;
    struct ccn_charbuf *name = ccn_charbuf_create();
    
    ccn_charbuf_putf(b, "<h4>Forwarding</h4>");
    ccn_charbuf_putf(b, "<ul>");
    hashtb_start(h->nameprefix_tab, e);
    for (; e->data != NULL; hashtb_next(e)) {
        struct nameprefix_entry *ipe = e->data;
        ccn_name_init(name);
        res = ccn_name_append_components(name, e->key, 0, e->keysize);
        if (res < 0)
            abort();
        if (0) {
            ccn_charbuf_putf(b, "<li>");
            ccn_uri_append(b, name->buf, name->length, 1);
            ccn_charbuf_putf(b, "</li>");
        }
        for (f = ipe->forwarding; f != NULL; f = f->next) {
            if ((f->flags & CCN_FORW_ACTIVE) != 0) {
                ccn_name_init(name);
                res = ccn_name_append_components(name, e->key, 0, e->keysize);
                ccn_charbuf_putf(b, "<li>");
                ccn_uri_append(b, name->buf, name->length, 1);
                ccn_charbuf_putf(b, " <b>face:</b> %u <b>flags:</b> 0x%x <b>expires:</b> %d",
                                 f->faceid, f->flags, f->expires);
                ccn_charbuf_putf(b, "</li>");
            }
        }
    }
    hashtb_end(e);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_putf(b, "</ul>");
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
        "<div><b>Interest totals:</b> %lu accepted, %lu dropped, %lu sent, %lu stuffed</div>",
        pid,
        un.nodename,
        pid,
        portstr,
        (unsigned long long)h->accession,
        hashtb_n(h->content_tab),
        hashtb_n(h->sparse_straggler_tab),
        h->content_dups_recvd,
        h->content_items_sent,
        hashtb_n(h->nameprefix_tab), stats.total_interest_counts,
        hashtb_n(h->propagating_tab) - stats.total_flood_control,
        stats.total_flood_control,
        h->interests_accepted, h->interests_dropped,
        h->interests_sent, h->interests_stuffed);
    if (0)
        ccn_charbuf_putf(b,
                         "<div><b>Active faces and listeners:</b> %d</div>",
                         hashtb_n(h->faces_by_fd) + hashtb_n(h->dgram_faces));
    collect_faces_html(h, b);
    collect_forwarding_html(h, b);
    ccn_charbuf_putf(b,
        "</body>"
        "</html>");
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
            (void)write(fd, buf, hdrlen);
            (void)write(fd, response, res);
        }
        else if (0 == memcmp(buf, "GET ", 4))
            (void)write(fd, resp404, strlen(resp404));
        else
            (void)write(fd, resp405, strlen(resp405));
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

/**
 *  Produce ccnd debug output.
 *  Output is produced on stderr under the control of h->debug;
 *  prepends decimal timestamp and process identification.
 *  Caller should not supply newlines.
 *  @param      h  the ccnd handle
 *  @param      fmt  printf-like format string
 */
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
    if ((h != NULL) && ((h->debug & 64) != 0) &&
        ((h->logbreak-- < 0 && t.tv_sec != h->logtime) ||
          t.tv_sec >= h->logtime + 30)) {
        fprintf(stderr, "%ld.000000 ccnd[%d]: _______________________ %s",
                (long)t.tv_sec, (int)getpid(), ctime(&t.tv_sec));
        h->logtime = t.tv_sec;
        h->logbreak = 30;
    }
    ccn_charbuf_putf(b, "%ld.%06u ccnd[%d]: %s\n",
        (long)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), fmt);
    va_start(ap, fmt);
    vfprintf(stderr, (const char *)b->buf, ap);
    ccn_charbuf_destroy(&b);
    /* if there's no one to hear, don't make a sound */
    if (ferror(stderr))
	h->debug = 0;
}

/**
 *  Produce a ccnd debug trace entry.
 *  Output is produced by calling ccnd_msg.
 *  @param      h  the ccnd handle
 *  @param      lineno  caller's source line number (usually __LINE__)
 *  @param      msg  a short text tag to identify the entry
 *  @param      face    handle of associated face; may be NULL
 *  @param      ccnb    points to ccnb-encoded Interest or ContentObject
 *  @param      ccnb_size   is in bytes
 */
void
ccnd_debug_ccnb(struct ccnd *h,
                int lineno,
                const char *msg,
                struct face *face,
                const unsigned char *ccnb,
                size_t ccnb_size)
{
    struct ccn_charbuf *c;
    struct ccn_parsed_interest pi;
    const unsigned char *nonce = NULL;
    size_t nonce_size = 0;
    size_t i;
    
    if (h != NULL && h->debug == 0)
        return;
    c = ccn_charbuf_create();
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
