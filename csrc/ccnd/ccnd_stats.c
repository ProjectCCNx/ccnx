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

static int ccnd_collect_stats(struct ccnd_handle *h, struct ccnd_stats *ans);
static struct ccn_charbuf *collect_stats_html(struct ccnd_handle *h);
static void send_http_response(struct ccnd_handle *h, struct face *face,
                               const char *mime_type,
                               struct ccn_charbuf *response);
static struct ccn_charbuf *collect_stats_html(struct ccnd_handle *h);
static struct ccn_charbuf *collect_stats_xml(struct ccnd_handle *h);

/* HTTP */

static const char *resp404 =
    "HTTP/1.1 404 Not Found" CRLF
    "Connection: close" CRLF CRLF;

static const char *resp405 =
    "HTTP/1.1 405 Method Not Allowed" CRLF
    "Connection: close" CRLF CRLF;

static void
ccnd_stats_http_set_debug(struct ccnd_handle *h, struct face *face, int level)
{
    struct ccn_charbuf *response = ccn_charbuf_create();
    
    h->debug = 1;
    ccnd_msg(h, "CCND_DEBUG=%d", level);
    h->debug = level;
    ccn_charbuf_putf(response, "<title>CCND_DEBUG=%d</title><tt>CCND_DEBUG=%d</tt>" CRLF, level, level);
    send_http_response(h, face, "text/html", response);
    ccn_charbuf_destroy(&response);
}

int
ccnd_stats_handle_http_connection(struct ccnd_handle *h, struct face *face)
{
    struct ccn_charbuf *response = NULL;
    char rbuf[16];
    int i;
    int nspace;
    int n;
    
    if (face->inbuf->length < 4)
        return(-1);
    if ((face->flags & CCN_FACE_NOSEND) != 0) {
        ccnd_destroy_face(h, face->faceid);
        return(-1);
    }
    n = sizeof(rbuf) - 1;
    if (face->inbuf->length < n)
        n = face->inbuf->length;
    for (i = 0, nspace = 0; i < n && nspace < 2; i++) {
        rbuf[i] = face->inbuf->buf[i];
        if (rbuf[i] == ' ')
            nspace++;
    }
    rbuf[i] = 0;
    if (nspace < 2 && i < sizeof(rbuf) - 1)
        return(-1);
    if (0 == strcmp(rbuf, "GET / ") ||
        0 == strcmp(rbuf, "GET /? ")) {
        response = collect_stats_html(h);
        send_http_response(h, face, "text/html", response);
    }
    else if (0 == strcmp(rbuf, "GET /?l=none ")) {
        ccnd_stats_http_set_debug(h, face, 0);
    }
    else if (0 == strcmp(rbuf, "GET /?l=low ")) {
        ccnd_stats_http_set_debug(h, face, 1);
    }
    else if (0 == strcmp(rbuf, "GET /?l=co ")) {
        ccnd_stats_http_set_debug(h, face, 4);
    }
    else if (0 == strcmp(rbuf, "GET /?l=med ")) {
        ccnd_stats_http_set_debug(h, face, 71);
    }
    else if (0 == strcmp(rbuf, "GET /?l=high ")) {
        ccnd_stats_http_set_debug(h, face, -1);
    }
    else if (0 == strcmp(rbuf, "GET /?f=xml ")) {
        response = collect_stats_xml(h);
        send_http_response(h, face, "text/xml", response);
    }
    else if (0 == strcmp(rbuf, "GET "))
        ccnd_send(h, face, resp404, strlen(resp404));
    else
        ccnd_send(h, face, resp405, strlen(resp405));
    face->flags |= (CCN_FACE_NOSEND | CCN_FACE_CLOSING);
    ccn_charbuf_destroy(&response);
    return(0);
}

static void
send_http_response(struct ccnd_handle *h, struct face *face,
                   const char *mime_type, struct ccn_charbuf *response)
{
    struct linger linger = { .l_onoff = 1, .l_linger = 1 };
    char buf[128];
    int hdrlen;

    /* Set linger to prevent quickly resetting the connection on close.*/
    setsockopt(face->recv_fd, SOL_SOCKET, SO_LINGER, &linger, sizeof(linger));
    hdrlen = snprintf(buf, sizeof(buf),
                      "HTTP/1.1 200 OK" CRLF
                      "Content-Type: %s; charset=utf-8" CRLF
                      "Connection: close" CRLF
                      "Content-Length: %jd" CRLF CRLF,
                      mime_type,
                      (intmax_t)response->length);
    ccnd_send(h, face, buf, hdrlen);
    ccnd_send(h, face, response->buf, response->length);
}

/* Common statistics collection */

static int
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
            if (ccnd_face_from_faceid(h, p->faceid) != NULL)
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
    if (sum != ans->total_interest_counts)
        ccnd_msg(h, "ccnd_collect_stats found inconsistency %ld != %ld\n",
                 (long)sum, (long)ans->total_interest_counts);
    ans->total_interest_counts = sum;
    return(0);
}

/* HTML formatting */

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
                CCN_FACE_NOSEND | CCN_FACE_GG | CCN_FACE_PASSIVE;
                if ((face->flags & chk) == 0)
                    ccn_charbuf_putf(b,
                                     " <b>remote:</b> "
                                     "<a href='http://%s:%s/'>"
                                     "%s:%d</a>",
                                     node, CCN_DEFAULT_UNICAST_PORT,
                                     node, port);
                else if ((face->flags & CCN_FACE_PASSIVE) == 0)
                    ccn_charbuf_putf(b, " <b>remote:</b> %s:%d",
                                     node, port);
                else
                    ccn_charbuf_putf(b, " <b>local:</b> %s:%d",
                                     node, port);
                if (face->sendface != face->faceid &&
                    face->sendface != CCN_NOFACEID)
                    ccn_charbuf_putf(b, " <b>via:</b> %u", face->sendface);
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

static unsigned
ccnd_colorhash(struct ccnd_handle *h)
{
    unsigned const char *a = h->ccnd_id;
    unsigned v;
    
    v = (a[0] << 16) + (a[1] << 8) + a[2];
    return (v | 0xC0C0C0);
}

static struct ccn_charbuf *
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
        "<html xmlns='http://www.w3.org/1999/xhtml'>"
        "<head>"
        "<title>%s ccnd[%d]</title>"
        //"<meta http-equiv='refresh' content='3'>"
        "<style type='text/css'>"
        "/*<![CDATA[*/"
        "p.header {color: white; background-color: blue; width: 100%%}"
        "/*]]>*/"
        "</style>"
        "</head>" NL
        "<body bgcolor='#%06X'>"
        "<p class='header'>%s ccnd[%d] local port %s</p>" NL
        "<div><b>Content items:</b> %llu accessioned,"
        " %d stored, %lu stale, %d sparse, %lu duplicate, %lu sent</div>" NL
        "<div><b>Interests:</b> %d names,"
        " %ld pending, %ld propagating, %ld noted</div>" NL
        "<div><b>Interest totals:</b> %lu accepted,"
        " %lu dropped, %lu sent, %lu stuffed</div>" NL,
        un.nodename,
        pid,
        ccnd_colorhash(h),
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

/* XML formatting */

static void
collect_faces_xml(struct ccnd_handle *h, struct ccn_charbuf *b)
{
    int i;
    struct ccn_charbuf *nodebuf;
    int port;
    
    nodebuf = ccn_charbuf_create();
    ccn_charbuf_putf(b, "<faces>");
    for (i = 0; i < h->face_limit; i++) {
        struct face *face = h->faces_by_faceid[i];
        if (face != NULL && (face->flags & CCN_FACE_UNDECIDED) == 0) {
            ccn_charbuf_putf(b, "<face>");
            ccn_charbuf_putf(b,
                             "<faceid>%u</faceid>"
                             "<faceflags>%04x</faceflags>",
                             face->faceid, face->flags);
            ccn_charbuf_putf(b, "<pending>%d</pending>",
                             face->pending_interests);
            ccn_charbuf_putf(b, "<recvcount>%d</recvcount>",
                             face->recvcount);
            nodebuf->length = 0;
            port = ccn_charbuf_append_sockaddr(nodebuf, face->addr);
            if (port > 0) {
                const char *node = ccn_charbuf_as_string(nodebuf);
                ccn_charbuf_putf(b, "<ip>%s:%d</ip>", node, port);
            }
            if (face->sendface != face->faceid &&
                face->sendface != CCN_NOFACEID)
                ccn_charbuf_putf(b, "<via>%u</via>", face->sendface);
            ccn_charbuf_putf(b, "</face>" NL);
        }
    }
    ccn_charbuf_putf(b, "</faces>");
    ccn_charbuf_destroy(&nodebuf);
}

static void
collect_forwarding_xml(struct ccnd_handle *h, struct ccn_charbuf *b)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_forwarding *f;
    int res;
    struct ccn_charbuf *name = ccn_charbuf_create();
    
    ccn_charbuf_putf(b, "<forwarding>");
    hashtb_start(h->nameprefix_tab, e);
    for (; e->data != NULL; hashtb_next(e)) {
        struct nameprefix_entry *ipe = e->data;
        for (f = ipe->forwarding, res = 0; f != NULL && !res; f = f->next) {
            if ((f->flags & CCN_FORW_ACTIVE) != 0)
                res = 1;
        }
        if (res) {
            ccn_name_init(name);
            res = ccn_name_append_components(name, e->key, 0, e->keysize);
            ccn_charbuf_putf(b, "<fentry>");
            ccn_charbuf_putf(b, "<prefix>");
            ccn_uri_append(b, name->buf, name->length, 1);
            ccn_charbuf_putf(b, "</prefix>");
            for (f = ipe->forwarding; f != NULL; f = f->next) {
                if ((f->flags & CCN_FORW_ACTIVE) != 0) {
                    ccn_charbuf_putf(b,
                                     "<dest>"
                                     "<faceid>%u</faceid>"
                                     "<flags>%x</flags>"
                                     "<expires>%d</expires>"
                                     "</dest>",
                                     f->faceid, f->flags, f->expires);
                }
            }
            ccn_charbuf_putf(b, "</fentry>");
        }
    }
    hashtb_end(e);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_putf(b, "</forwarding>");
}

static struct ccn_charbuf *
collect_stats_xml(struct ccnd_handle *h)
{
    struct ccnd_stats stats = {0};
    struct ccn_charbuf *b = ccn_charbuf_create();
        
    ccnd_collect_stats(h, &stats);
    ccn_charbuf_putf(b,
        "<ccnd>"
        "<cobs>"
        "<accessioned>%llu</accessioned>"
        "<stored>%d</stored>"
        "<stale>%lu</stale>"
        "<sparse>%d</sparse>"
        "<duplicate>%lu</duplicate>"
        "<sent>%lu</sent>"
        "</cobs>"
        "<interests>"
        "<names>%d</names>"
        "<pending>%ld</pending>"
        "<propagating>%ld</propagating>"
        "<noted>%ld</noted>"
        "<accepted>%lu</accepted>"
        "<dropped>%lu</dropped>"
        "<sent>%lu</sent>"
        "<stuffed>%lu</stuffed>"
        "</interests>",
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
    collect_faces_xml(h, b);
    collect_forwarding_xml(h, b);
    ccn_charbuf_putf(b, "</ccnd>" NL);
    return(b);
}
