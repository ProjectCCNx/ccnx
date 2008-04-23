/*
 * ccnd_stats.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <unistd.h>

#include <ccn/charbuf.h>
#include <ccn/schedule.h>
#include <ccn/hashtb.h>

#include "ccnd_private.h"

static char *
collect_stats_html(struct ccnd *h)
{
    char *ans;
    struct ccn_charbuf *b = ccn_charbuf_create();
    ccn_charbuf_putf(b,
        "<html>"
        "<title>ccnd[%d]</title>"
        "<meta http-equiv='refresh' content='3'>"
        "<body>"
        "<div><b>Content items in store:</b> %d</div>"
        "<div><b>Existing interests:</b> %d</div>"
        "<div><b>Active faces and listeners:</b> %d</div>"
        "</body>"
        "<html>",
        getpid(),
        hashtb_n(h->content_tab),
        hashtb_n(h->interest_tab),
        hashtb_n(h->faces_by_fd) + hashtb_n(h->dgram_faces));
    ans = strdup((char *)b->buf);
    ccn_charbuf_destroy(&b);
    return(ans);
}

static int
check_for_http_connection(struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    int res;
    int sock;
    char *response = NULL;
    sock = ev->evint;
    if ((flags && CCN_SCHEDULE_CANCEL) != 0) {
        close(sock);
        return(0);
    }
    for (;;) {
        int fd = accept(sock, NULL, 0);
        if (fd == -1)
            break;
        if (response == NULL)
            response = collect_stats_html(clienth);
        res = write(fd, response, strlen(response));
        close(fd);
    }
    free(response);
    return(4000000);
}

int
ccnd_stats_httpd_start(struct ccnd *h)
{
    int res;
    int sock;
    struct addrinfo hints = {0};
    struct addrinfo *ai = NULL;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_PASSIVE;
    res = getaddrinfo(NULL, "8544", &hints, &ai);
    if (res == -1) {
        perror("ccnd_stats_httpd_listen: getaddrinfo");
        return(-1);
    }
    sock = socket(ai->ai_family, SOCK_STREAM, 0);
    if (sock == -1) {
        perror("ccnd_stats_httpd_listen: getaddrinfo");
        return(-1);
    }
    res = bind(sock, ai->ai_addr, ai->ai_addrlen);
    if (res == -1) {
        perror("ccnd_stats_httpd_listen: bind");
        close(sock);
        return(-1);
    }
    res = fcntl(sock, F_SETFL, O_NONBLOCK);
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
    ccn_schedule_event(h->sched, 1000000, &check_for_http_connection, NULL, sock);
    return(0);
}
