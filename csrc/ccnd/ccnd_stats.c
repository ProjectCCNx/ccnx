/**
 * @file ccnd_stats.c
 *
 * Statistics presentation for ccnd.
 *
 * Part of ccnd - the CCNx Daemon.
 *
 * Copyright (C) 2008-2010 Palo Alto Research Center, Inc.
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

#include <sys/types.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/sockaddrutil.h>
#include <ccn/hashtb.h>
#include <ccn/uri.h>

#include "ccnd_private.h"

#define CRLF "\r\n"
#define NL   "\n"

struct ccnd_stats {
    long total_interest_counts;
    long total_flood_control;      /* done propagating, still recorded */
};

int
ccnd_collect_stats(struct ccnd_handle *h, struct ccnd_stats *ans)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    long sum;
    unsigned i;
    for (sum = 0, hashtb_start(h->nameprefix_tab, e);
         e->data != NULL; hashtb_next(e)) {
        struct nameprefix_entry *npe = e->data;
        struct propagating_entry *head = &npe->pe_head;
        struct propagating_entry *p;
        for (p = head->next; p != head; p = p->next) {
            // XXX - This should check p->faceid before counting p
            // ... but face_from_faceid() is private.
            sum += 1;
        }
    }
    ans->total_interest_counts = sum;
    hashtb_end(e);
    for (sum = 0, hashtb_start(h->propagating_tab, e);
         e->data != NULL; hashtb_next(e)) {
        struct propagating_entry *pe = e->data;
        if (pe->interest_msg == NULL)
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
    if ((h->debug & 32) != 0 && sum != ans->total_interest_counts)
        ccnd_msg(h, "ccnd_collect_stats found inconsistency %ld != %ld\n",
                 (long)sum, (long)ans->total_interest_counts);
    ans->total_interest_counts = sum;
    return(0);
}

static void
collect_faces_html(struct ccnd_handle *h, struct ccn_charbuf *b)
{
    int i;
    struct ccn_charbuf *nodebuf;
    int port;
    
    nodebuf = ccn_charbuf_create();
    ccn_charbuf_putf(b, "<h4>Faces</h4>" NL);
    ccn_charbuf_putf(b, "<ul>");
    for (i = 0; i < h->face_limit; i++) {
        struct face *face = h->faces_by_faceid[i];
        if (face != NULL && (face->flags & CCN_FACE_UNDECIDED) == 0) {
            ccn_charbuf_putf(b, " <li>");
            ccn_charbuf_putf(b, "<b>face:</b> %u <b>flags:</b> 0x%x",
                             face->faceid, face->flags);
            ccn_charbuf_putf(b, " <b>pending:</b> %d",
                             face->pending_interests);
            if (face->recvcount != 0)
                ccn_charbuf_putf(b, " <b>activity:</b> %d",
                                 face->recvcount);
            nodebuf->length = 0;
            port = ccn_charbuf_append_sockaddr(nodebuf, face->addr);
            if (port > 0) {
                const char *node = ccn_charbuf_as_string(nodebuf);
                int chk = CCN_FACE_MCAST | CCN_FACE_UNDECIDED |
                          CCN_FACE_NOSEND | CCN_FACE_GG;
                if ((face->flags & chk) == 0)
                    ccn_charbuf_putf(b,
                                     " <b>remote:</b> "
                                     "<a href='http://%s:%s/'>"
                                     "%s:%d</a>",
                                     node, CCN_DEFAULT_UNICAST_PORT,
                                     node, port);
                else
                    ccn_charbuf_putf(b, " <b>remote:</b> %s:%d",
                                     node, port);
            }
            ccn_charbuf_putf(b, "</li>" NL);
        }
    }
    ccn_charbuf_putf(b, "</ul>");
    ccn_charbuf_destroy(&nodebuf);
}

static void
collect_forwarding_html(struct ccnd_handle *h, struct ccn_charbuf *b)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_forwarding *f;
    int res;
    struct ccn_charbuf *name = ccn_charbuf_create();
    
    ccn_charbuf_putf(b, "<h4>Forwarding</h4>" NL);
    ccn_charbuf_putf(b, "<ul>");
    hashtb_start(h->nameprefix_tab, e);
    for (; e->data != NULL; hashtb_next(e)) {
        struct nameprefix_entry *ipe = e->data;
        ccn_name_init(name);
        res = ccn_name_append_components(name, e->key, 0, e->keysize);
        if (res < 0)
            abort();
        if (0) {
            ccn_charbuf_putf(b, " <li>");
            ccn_uri_append(b, name->buf, name->length, 1);
            ccn_charbuf_putf(b, "</li>" NL);
        }
        for (f = ipe->forwarding; f != NULL; f = f->next) {
            if ((f->flags & CCN_FORW_ACTIVE) != 0) {
                ccn_name_init(name);
                res = ccn_name_append_components(name, e->key, 0, e->keysize);
                ccn_charbuf_putf(b, " <li>");
                ccn_uri_append(b, name->buf, name->length, 1);
                ccn_charbuf_putf(b,
                                 " <b>face:</b> %u"
                                 " <b>flags:</b> 0x%x"
                                 " <b>expires:</b> %d",
                                 f->faceid, f->flags, f->expires);
                ccn_charbuf_putf(b, "</li>" NL);
            }
        }
    }
    hashtb_end(e);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_putf(b, "</ul>");
}

struct ccn_charbuf *
collect_stats_html(struct ccnd_handle *h)
{
    struct ccnd_stats stats = {0};
    struct ccn_charbuf *b = ccn_charbuf_create();
    int pid;
    struct utsname un;
    const char *portstr;
    
    portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    if (portstr == NULL || portstr[0] == 0 || strlen(portstr) > 10)
        portstr = CCN_DEFAULT_UNICAST_PORT;
    uname(&un);
    pid = getpid();
    
    ccnd_collect_stats(h, &stats);
    ccn_charbuf_putf(b,
        "<html>"
        "<head>"
        "<title>%s ccnd[%d]</title>"
        //"<meta http-equiv='refresh' content='3'>"
        "<style type='text/css'>"
        " p.header {color: white; background-color: blue} "
        "</style>"
        "</head>" NL
        "<body>"
        "<p class='header' width='100%%'>%s ccnd[%d] local port %s</p>" NL
        "<div><b>Content items:</b> %llu accessioned,"
        " %d stored, %lu stale, %d sparse, %lu duplicate, %lu sent</div>" NL
        "<div><b>Interests:</b> %d names,"
        " %ld pending, %ld propagating, %ld noted</div>" NL
        "<div><b>Interest totals:</b> %lu accepted,"
        " %lu dropped, %lu sent, %lu stuffed</div>" NL,
        un.nodename,
        pid,
        un.nodename,
        pid,
        portstr,
        (unsigned long long)h->accession,
        hashtb_n(h->content_tab),
        h->n_stale,
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
                         "<div><b>Active faces and listeners:</b> %d</div>" NL,
                         hashtb_n(h->faces_by_fd) + hashtb_n(h->dgram_faces));
    collect_faces_html(h, b);
    collect_forwarding_html(h, b);
    ccn_charbuf_putf(b,
        "</body>"
        "</html>" NL);
    return(b);
}

static const char *resp404 =
    "HTTP/1.1 404 Not Found" CRLF
    "Connection: close" CRLF CRLF;

static const char *resp405 =
    "HTTP/1.1 405 Method Not Allowed" CRLF
    "Connection: close" CRLF CRLF;

int
ccnd_stats_handle_http_connection(struct ccnd_handle *h, struct face *face)
{
    int hdrlen;
    struct ccn_charbuf *response = NULL;
    struct linger linger = { .l_onoff = 1, .l_linger = 1 };
    char buf[128];
    
    if (face->inbuf->length < 6)
        return(-1);

    if ((face->flags & CCN_FACE_NOSEND) != 0) {
        ccnd_destroy_face(h, face->faceid);
        return(-1);
    }
    /* Set linger to prevent quickly resetting the connection on close.*/
    setsockopt(face->send_fd, SOL_SOCKET, SO_LINGER, &linger, sizeof(linger));
    if (0 == memcmp(face->inbuf->buf, "GET / ", 6)) {
        response = collect_stats_html(h);
        hdrlen = snprintf(buf, sizeof(buf),
                          "HTTP/1.1 200 OK" CRLF
                          "Content-Type: text/html; charset=utf-8" CRLF
                          "Connection: close" CRLF
                          "Content-Length: %jd" CRLF CRLF,
                          (intmax_t)response->length);
        ccnd_send(h, face, buf, hdrlen);
        ccnd_send(h, face, response->buf, response->length);
    }
    else if (0 == memcmp(buf, "GET ", 4))
        ccnd_send(h, face, resp404, strlen(resp404));
    else
        ccnd_send(h, face, resp405, strlen(resp405));
    face->flags |= (CCN_FACE_NOSEND | CCN_FACE_CLOSING);
    ccn_charbuf_destroy(&response);
    return(0);
}
