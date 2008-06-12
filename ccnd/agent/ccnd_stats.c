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

#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/hashtb.h>

#include "ccnd_private.h"

struct ccnd_stats {
    long total_interest_counts;
    long total_content_suppressed;
    long total_flood_control;      /* done propagating, still recorded */
};

int
ccnd_collect_stats(struct ccnd *h, struct ccnd_stats *ans)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    long sum;
    int i;
    int n;
    for (sum = 0, hashtb_start(h->interest_tab, e);
                                   e->data != NULL; hashtb_next(e)) {
        struct interest_entry *interest = e->data;
        n = interest->counters->n;
        for (i = 0; i < n; i++)
            sum += interest->counters->buf[i];
    }
    ans->total_interest_counts = (sum + CCN_UNIT_INTEREST-1) / CCN_UNIT_INTEREST;
    hashtb_end(e);
    for (sum = 0, hashtb_start(h->content_tab, e);
                                  e->data != NULL; hashtb_next(e)) {
        struct content_entry *content = e->data;
        if (content->faces != NULL)
            sum += content->faces->n;
    }
    ans->total_content_suppressed = sum;
    hashtb_end(e);
    for (sum = 0, hashtb_start(h->propagating_tab, e);
                                      e->data != NULL; hashtb_next(e)) {
        struct propagating_entry *pi = e->data;
        if (pi->outbound == NULL)
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
        "HTTP/0.9 200 OK\r\n"
        "Content-Type: text/html; charset=utf-8\r\n\r\n"
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
        "<div><b>Content items:</b> %llu accessioned, %d stored, %lu duplicate, %lu sent</div>"
        "<div><b>Content supression:</b> %d</div>"
        "<div><b>Interests:</b> %d names, %ld pending, %ld propagating, %ld noted</div>"
        "<div><b>Interest totals:</b> %lu accepted, %lu dropped, %lu sent</div>"
        "<div><b>Active faces and listeners:</b> %d</div>"
        "</body>"
        "</html>",
        pid,
        un.nodename,
        pid,
        portstr,
        (unsigned long long)h->accession,
                hashtb_n(h->content_tab),
                h->content_dups_recvd,
                h->content_items_sent,
        stats.total_content_suppressed,
        hashtb_n(h->interest_tab), stats.total_interest_counts,
                hashtb_n(h->propagating_tab) - stats.total_flood_control,
                stats.total_flood_control,
        h->interests_accepted, h->interests_dropped, h->interests_sent,
        hashtb_n(h->faces_by_fd) + hashtb_n(h->dgram_faces));
    ans = strdup((char *)b->buf);
    ccn_charbuf_destroy(&b);
    return(ans);
}

static const char *resp404 = "HTTP/0.9 404 Not Found\r\n";
int
ccnd_stats_check_for_http_connection(struct ccnd *h)
{
    int res;
    int fd;
    char *response = NULL;
    char buf[7] = "GET / ";
    if (h->httpd_listener_fd == -1)
        return(-1);
    fd = accept(h->httpd_listener_fd, NULL, 0);
    if (fd == -1) {
        perror("check_for_http_connection - accept");
        close(h->httpd_listener_fd);
        h->httpd_listener_fd = -1;
        return(-1);
    }
    // XXX - the blocking read opens us to a D.O.S., but non-blocking causes
    //  problems on the client side (for unknown reasons).
    // fcntl(fd, F_SETFL, O_NONBLOCK);
    response = collect_stats_html(h);
    res = read(fd, buf, sizeof(buf)-1);
    if ((res == -1 && errno == EAGAIN) || res == sizeof(buf)-1) {
        if (0 == strcmp(buf, "GET / ")) {
            write(fd, response, strlen(response));
        }
        else
            write(fd, resp404, strlen(resp404));
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
    //ccn_schedule_event(h->sched, 1000000, &check_for_http_connection, NULL, sock);
    return(0);
}

/* ccnd_msg may migrate into a different place ... */
void
ccnd_msg(struct ccnd *h, const char *fmt, ...)
{
    struct timeval t;
    va_list ap;
    struct ccn_charbuf *b = ccn_charbuf_create();
    
    gettimeofday(&t, NULL);
    ccn_charbuf_putf(b, "%d.%06u ccnd[%d]: %s\n",
        (int)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), fmt);
    va_start(ap, fmt);
    vfprintf(stderr, (const char *)b->buf, ap);
    ccn_charbuf_destroy(&b);
}
