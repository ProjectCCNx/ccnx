/**
 * @file ccnr_stats.c
 * 
 * Statistics presentation for ccnr.
 *
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011, 2013 Palo Alto Research Center, Inc.
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
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/sockaddrutil.h>
#include <ccn/hashtb.h>
#include <ccn/uri.h>

#include "ccnr_private.h"

#include "ccnr_stats.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"


#define CRLF "\r\n"
#define NL   "\n"

/**
 * Provide a way to monitor rates.
 */
struct ccnr_meter {
    uintmax_t total;
    char what[8];
    unsigned rate; /** a scale factor applies */
    unsigned lastupdate;
};

struct ccnr_stats {
    long total_interest_counts;
    long total_flood_control;      /* done propagating, still recorded */
};

static int ccnr_collect_stats(struct ccnr_handle *h, struct ccnr_stats *ans);
static struct ccn_charbuf *collect_stats_html(struct ccnr_handle *h);
static void send_http_response(struct ccnr_handle *h, struct fdholder *fdholder,
                               const char *mime_type,
                               struct ccn_charbuf *response);
static struct ccn_charbuf *collect_stats_html(struct ccnr_handle *h);
static struct ccn_charbuf *collect_stats_xml(struct ccnr_handle *h);

/* HTTP */

static const char *resp404 =
    "HTTP/1.1 404 Not Found" CRLF
    "Connection: close" CRLF CRLF;

static const char *resp405 =
    "HTTP/1.1 405 Method Not Allowed" CRLF
    "Connection: close" CRLF CRLF;

static void
ccnr_stats_http_set_debug(struct ccnr_handle *h, struct fdholder *fdholder, int level)
{
    struct ccn_charbuf *response = ccn_charbuf_create();
    
    h->debug = 1;
    ccnr_msg(h, "CCNR_DEBUG=%d", level);
    h->debug = level;
    ccn_charbuf_putf(response, "<title>CCNR_DEBUG=%d</title><tt>CCNR_DEBUG=%d</tt>" CRLF, level, level);
    send_http_response(h, fdholder, "text/html", response);
    ccn_charbuf_destroy(&response);
}

int
ccnr_stats_handle_http_connection(struct ccnr_handle *h, struct fdholder *fdholder)
{
    struct ccn_charbuf *response = NULL;
    char rbuf[16];
    int i;
    int nspace;
    int n;
    
    if (fdholder->inbuf->length < 4)
        return(-1);
    if ((fdholder->flags & CCNR_FACE_NOSEND) != 0) {
        r_io_destroy_face(h, fdholder->filedesc);
        return(-1);
    }
    n = sizeof(rbuf) - 1;
    if (fdholder->inbuf->length < n)
        n = fdholder->inbuf->length;
    for (i = 0, nspace = 0; i < n && nspace < 2; i++) {
        rbuf[i] = fdholder->inbuf->buf[i];
        if (rbuf[i] == ' ')
            nspace++;
    }
    rbuf[i] = 0;
    if (nspace < 2 && i < sizeof(rbuf) - 1)
        return(-1);
    if (0 == strcmp(rbuf, "GET / ") ||
        0 == strcmp(rbuf, "GET /? ")) {
        response = collect_stats_html(h);
        send_http_response(h, fdholder, "text/html", response);
    }
    else if (0 == strcmp(rbuf, "GET /?l=none ")) {
        ccnr_stats_http_set_debug(h, fdholder, 0);
    }
    else if (0 == strcmp(rbuf, "GET /?l=low ")) {
        ccnr_stats_http_set_debug(h, fdholder, 1);
    }
    else if (0 == strcmp(rbuf, "GET /?l=co ")) {
        ccnr_stats_http_set_debug(h, fdholder, 4);
    }
    else if (0 == strcmp(rbuf, "GET /?l=med ")) {
        ccnr_stats_http_set_debug(h, fdholder, 71);
    }
    else if (0 == strcmp(rbuf, "GET /?l=high ")) {
        ccnr_stats_http_set_debug(h, fdholder, -1);
    }
    else if (0 == strcmp(rbuf, "GET /?f=xml ")) {
        response = collect_stats_xml(h);
        send_http_response(h, fdholder, "text/xml", response);
    }
    else if (0 == strcmp(rbuf, "GET "))
        r_io_send(h, fdholder, resp404, strlen(resp404), NULL);
    else
        r_io_send(h, fdholder, resp405, strlen(resp405), NULL);
    fdholder->flags |= (CCNR_FACE_NOSEND | CCNR_FACE_CLOSING);
    ccn_charbuf_destroy(&response);
    return(0);
}

static void
send_http_response(struct ccnr_handle *h, struct fdholder *fdholder,
                   const char *mime_type, struct ccn_charbuf *response)
{
    struct linger linger = { .l_onoff = 1, .l_linger = 1 };
    char buf[128];
    int hdrlen;

    /* Set linger to prevent quickly resetting the connection on close.*/
    setsockopt(fdholder->filedesc, SOL_SOCKET, SO_LINGER, &linger, sizeof(linger));
    hdrlen = snprintf(buf, sizeof(buf),
                      "HTTP/1.1 200 OK" CRLF
                      "Content-Type: %s; charset=utf-8" CRLF
                      "Connection: close" CRLF
                      "Content-Length: %jd" CRLF CRLF,
                      mime_type,
                      (intmax_t)response->length);
    r_io_send(h, fdholder, buf, hdrlen, NULL);
    r_io_send(h, fdholder, response->buf, response->length, NULL);
}

/* Common statistics collection */

static int
ccnr_collect_stats(struct ccnr_handle *h, struct ccnr_stats *ans)
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
            if (ccnr_r_io_fdholder_from_fd(h, p->filedesc) != NULL)
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
        struct fdholder *fdholder = h->fdholder_by_fd[i];
        if (fdholder != NULL)
            sum += fdholder->pending_interests;
    }
    if (sum != ans->total_interest_counts)
        ccnr_msg(h, "ccnr_collect_stats found inconsistency %ld != %ld\n",
                 (long)sum, (long)ans->total_interest_counts);
    ans->total_interest_counts = sum;
    return(0);
}

/* HTML formatting */

static void
collect_faces_html(struct ccnr_handle *h, struct ccn_charbuf *b)
{
    int i;
    struct ccn_charbuf *nodebuf;
    
    nodebuf = ccn_charbuf_create();
    ccn_charbuf_putf(b, "<h4>Faces</h4>" NL);
    ccn_charbuf_putf(b, "<ul>");
    for (i = 0; i < h->face_limit; i++) {
        struct fdholder *fdholder = h->fdholder_by_fd[i];
        if (fdholder != NULL && (fdholder->flags & CCNR_FACE_UNDECIDED) == 0) {
            ccn_charbuf_putf(b, " <li>");
            ccn_charbuf_putf(b, "<b>fdholder:</b> %u <b>flags:</b> 0x%x",
                             fdholder->filedesc, fdholder->flags);
            ccn_charbuf_putf(b, " <b>pending:</b> %d",
                             fdholder->pending_interests);
            if (fdholder->recvcount != 0)
                ccn_charbuf_putf(b, " <b>activity:</b> %d",
                                 fdholder->recvcount);
            nodebuf->length = 0;
#if 0
            port = 0;
// XXX - fix for fdholder->name
            int port = ccn_charbuf_append_sockaddr(nodebuf, fdholder->addr);
            if (port > 0) {
                const char *node = ccn_charbuf_as_string(nodebuf);
                if ((fdholder->flags & CCNR_FACE_PASSIVE) == 0)
                    ccn_charbuf_putf(b, " <b>remote:</b> %s:%d",
                                     node, port);
                else
                    ccn_charbuf_putf(b, " <b>local:</b> %s:%d",
                                     node, port);
                if (fdholder->sendface != fdholder->filedesc &&
                    fdholder->sendface != CCN_NOFACEID)
                    ccn_charbuf_putf(b, " <b>via:</b> %u", fdholder->sendface);
            }
#endif
            ccn_charbuf_putf(b, "</li>" NL);
        }
    }
    ccn_charbuf_putf(b, "</ul>");
    ccn_charbuf_destroy(&nodebuf);
}

static void
collect_face_meter_html(struct ccnr_handle *h, struct ccn_charbuf *b)
{
    int i;
    ccn_charbuf_putf(b, "<h4>fdholder Activity Rates</h4>");
    ccn_charbuf_putf(b, "<table cellspacing='0' cellpadding='0' class='tbl' summary='fdholder activity rates'>");
    ccn_charbuf_putf(b, "<tbody>" NL);
    ccn_charbuf_putf(b, " <tr><td>        </td>\t"
                        " <td>Bytes/sec In/Out</td>\t"
                        " <td>recv data/intr sent</td>\t"
                        " <td>sent data/intr recv</td></tr>" NL);
    for (i = 0; i < h->face_limit; i++) {
        struct fdholder *fdholder = h->fdholder_by_fd[i];
        if (fdholder != NULL && (fdholder->flags & (CCNR_FACE_UNDECIDED|CCNR_FACE_PASSIVE)) == 0) {
            ccn_charbuf_putf(b, " <tr>");
            ccn_charbuf_putf(b, "<td><b>fdholder:</b> %u</td>\t",
                             fdholder->filedesc);
            ccn_charbuf_putf(b, "<td>%6u / %u</td>\t\t",
                                 ccnr_meter_rate(h, fdholder->meter[FM_BYTI]),
                                 ccnr_meter_rate(h, fdholder->meter[FM_BYTO]));
            ccn_charbuf_putf(b, "<td>%9u / %u</td>\t\t",
                                 ccnr_meter_rate(h, fdholder->meter[FM_DATI]),
                                 ccnr_meter_rate(h, fdholder->meter[FM_INTO]));
            ccn_charbuf_putf(b, "<td>%9u / %u</td>",
                                 ccnr_meter_rate(h, fdholder->meter[FM_DATO]),
                                 ccnr_meter_rate(h, fdholder->meter[FM_INTI]));
            ccn_charbuf_putf(b, "</tr>" NL);
        }
    }
    ccn_charbuf_putf(b, "</tbody>");
    ccn_charbuf_putf(b, "</table>");
}

static void
collect_forwarding_html(struct ccnr_handle *h, struct ccn_charbuf *b)
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
            if ((f->flags & (CCN_FORW_ACTIVE | CCN_FORW_PFXO)) != 0) {
                ccn_name_init(name);
                res = ccn_name_append_components(name, e->key, 0, e->keysize);
                ccn_charbuf_putf(b, " <li>");
                ccn_uri_append(b, name->buf, name->length, 1);
                ccn_charbuf_putf(b,
                                 " <b>fdholder:</b> %u"
                                 " <b>flags:</b> 0x%x"
                                 " <b>expires:</b> %d",
                                 f->filedesc,
                                 f->flags & CCN_FORW_PUBMASK,
                                 f->expires);
                ccn_charbuf_putf(b, "</li>" NL);
            }
        }
    }
    hashtb_end(e);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_putf(b, "</ul>");
}

static unsigned
ccnr_colorhash(struct ccnr_handle *h)
{
    unsigned const char *a = h->ccnr_id;
    unsigned v;
    
    v = (a[0] << 16) + (a[1] << 8) + a[2];
    return (v | 0xC0C0C0);
}

static struct ccn_charbuf *
collect_stats_html(struct ccnr_handle *h)
{
    struct ccnr_stats stats = {0};
    struct ccn_charbuf *b = ccn_charbuf_create();
    int pid;
    struct utsname un;
    
    uname(&un);
    pid = getpid();
    
    ccnr_collect_stats(h, &stats);
    ccn_charbuf_putf(b,
        "<html xmlns='http://www.w3.org/1999/xhtml'>"
        "<head>"
        "<title>%s ccnr[%d]</title>"
        //"<meta http-equiv='refresh' content='3'>"
        "<style type='text/css'>"
        "/*<![CDATA[*/"
        "p.header {color: white; background-color: blue; width: 100%%} "
        "table.tbl {border-style: solid; border-width: 1.0px 1.0px 1.0px 1.0px; border-color: black} "
        "td {border-style: solid; "
            "border-width: 1.0px 1.0px 1.0px 1.0px; "
            "border-color: #808080 #808080 #808080 #808080; "
            "padding: 6px 6px 6px 6px; "
            "margin-left: auto; margin-right: auto; "
            "text-align: center"
            "} "
        "td.left {text-align: left} "
        "/*]]>*/"
        "</style>"
        "</head>" NL
        "<body bgcolor='#%06X'>"
        "<p class='header'>%s ccnr[%d] local port %s api %d start %ld.%06u now %ld.%06u</p>" NL
        "<div><b>Content items:</b> %llu accessioned,"
        " %llu cached, %lu stale, %d sparse, %lu duplicate, %lu sent</div>" NL
        "<div><b>Interests:</b> %d names,"
        " %ld pending, %ld propagating, %ld noted</div>" NL
        "<div><b>Interest totals:</b> %lu accepted,"
        " %lu dropped, %lu sent, %lu stuffed</div>" NL,
        un.nodename,
        pid,
        ccnr_colorhash(h),
        un.nodename,
        pid,
        h->portstr,
        (int)CCN_API_VERSION,
        h->starttime, h->starttime_usec,
        h->sec,
        h->usec,
        (unsigned long long)hashtb_n(h->content_by_accession_tab), // XXXXXX - 
        (unsigned long long)(h->cob_count),
        h->n_stale,
        hashtb_n(h->content_by_accession_tab),
        h->content_dups_recvd,
        h->content_items_sent,
        hashtb_n(h->nameprefix_tab), stats.total_interest_counts,
        hashtb_n(h->propagating_tab) - stats.total_flood_control,
        stats.total_flood_control,
        h->interests_accepted, h->interests_dropped,
        h->interests_sent, h->interests_stuffed);
    collect_faces_html(h, b);
    collect_face_meter_html(h, b);
    collect_forwarding_html(h, b);
    ccn_charbuf_putf(b,
        "</body>"
        "</html>" NL);
    return(b);
}

/* XML formatting */

static void
collect_meter_xml(struct ccnr_handle *h, struct ccn_charbuf *b, struct ccnr_meter *m)
{
    uintmax_t total;
    unsigned rate;
    
    if (m == NULL)
        return;
    total = ccnr_meter_total(m);
    rate = ccnr_meter_rate(h, m);
    ccn_charbuf_putf(b, "<%s><total>%ju</total><persec>%u</persec></%s>",
        m->what, total, rate, m->what);
}

static void
collect_faces_xml(struct ccnr_handle *h, struct ccn_charbuf *b)
{
    int i;
    int m;
    struct ccn_charbuf *nodebuf;
    
    nodebuf = ccn_charbuf_create();
    ccn_charbuf_putf(b, "<faces>");
    for (i = 0; i < h->face_limit; i++) {
        struct fdholder *fdholder = h->fdholder_by_fd[i];
        if (fdholder != NULL && (fdholder->flags & CCNR_FACE_UNDECIDED) == 0) {
            ccn_charbuf_putf(b, "<fdholder>");
            ccn_charbuf_putf(b,
                             "<filedesc>%u</filedesc>"
                             "<faceflags>%04x</faceflags>",
                             fdholder->filedesc, fdholder->flags);
            ccn_charbuf_putf(b, "<pending>%d</pending>",
                             fdholder->pending_interests);
            ccn_charbuf_putf(b, "<recvcount>%d</recvcount>",
                             fdholder->recvcount);
            nodebuf->length = 0;
#if 0
            port = 0;
// XXX - fix this to know about fdholder->name
            int port = ccn_charbuf_append_sockaddr(nodebuf, fdholder->addr);
            if (port > 0) {
                const char *node = ccn_charbuf_as_string(nodebuf);
                ccn_charbuf_putf(b, "<ip>%s:%d</ip>", node, port);
            }
            if (fdholder->sendface != fdholder->filedesc &&
                fdholder->sendface != CCN_NOFACEID)
                ccn_charbuf_putf(b, "<via>%u</via>", fdholder->sendface);
#endif
            if (fdholder != NULL && (fdholder->flags & CCNR_FACE_PASSIVE) == 0) {
                ccn_charbuf_putf(b, "<meters>");
                for (m = 0; m < CCNR_FACE_METER_N; m++)
                    collect_meter_xml(h, b, fdholder->meter[m]);
                ccn_charbuf_putf(b, "</meters>");
            }
            ccn_charbuf_putf(b, "</fdholder>" NL);
        }
    }
    ccn_charbuf_putf(b, "</faces>");
    ccn_charbuf_destroy(&nodebuf);
}

static void
collect_forwarding_xml(struct ccnr_handle *h, struct ccn_charbuf *b)
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
            if ((f->flags & (CCN_FORW_ACTIVE | CCN_FORW_PFXO)) != 0)
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
                if ((f->flags & (CCN_FORW_ACTIVE | CCN_FORW_PFXO)) != 0) {
                    ccn_charbuf_putf(b,
                                     "<dest>"
                                     "<filedesc>%u</filedesc>"
                                     "<flags>%x</flags>"
                                     "<expires>%d</expires>"
                                     "</dest>",
                                     f->filedesc,
                                     f->flags & CCN_FORW_PUBMASK,
                                     f->expires);
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
collect_stats_xml(struct ccnr_handle *h)
{
    struct ccnr_stats stats = {0};
    struct ccn_charbuf *b = ccn_charbuf_create();
    int i;
        
    ccnr_collect_stats(h, &stats);
    ccn_charbuf_putf(b,
        "<ccnr>"
        "<identity>"
        "<ccnrid>");
    for (i = 0; i < sizeof(h->ccnr_id); i++)
        ccn_charbuf_putf(b, "%02X", h->ccnr_id[i]);
    ccn_charbuf_putf(b, "</ccnrid>"
        "<apiversion>%d</apiversion>"
        "<starttime>%ld.%06u</starttime>"
        "<now>%ld.%06u</now>"
        "</identity>",
        (int)CCN_API_VERSION,
        h->starttime, h->starttime_usec,
        h->sec,
        h->usec);
    ccn_charbuf_putf(b,
        "<cobs>"
        "<accessioned>%llu</accessioned>"
        "<cached>%llu</cached>"
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
        "</interests>"
        "<lookups>"
        "<rightmost>"
        "<found>%lu</found>"
        "<iterations>%lu</iterations>"
        "<notfound>%lu</notfound>"
        "<iterations>%lu</iterations>"
        "</rightmost>"
        "<leftmost>"
        "<found>%lu</found>"
        "<iterations>%lu</iterations>"
        "<notfound>%lu</notfound>"
        "<iterations>%lu</iterations>"
        "</leftmost>"
        "</lookups>"
        ,
        (unsigned long long)hashtb_n(h->content_by_accession_tab), // XXXXXX -
        (unsigned long long)(h->cob_count),
        h->n_stale,
        hashtb_n(h->content_by_accession_tab),
        h->content_dups_recvd,
        h->content_items_sent,
        hashtb_n(h->nameprefix_tab), stats.total_interest_counts,
        hashtb_n(h->propagating_tab) - stats.total_flood_control,
        stats.total_flood_control,
        h->interests_accepted, h->interests_dropped,
        h->interests_sent, h->interests_stuffed,
        h->count_lmc_found, 
        h->count_lmc_found_iters,
        h->count_lmc_notfound,
        h->count_lmc_notfound_iters,
        h->count_rmc_found, 
        h->count_rmc_found_iters,
        h->count_rmc_notfound,
        h->count_rmc_notfound_iters
        );
    collect_faces_xml(h, b);
    collect_forwarding_xml(h, b);
    ccn_charbuf_putf(b, "</ccnr>" NL);
    return(b);
}

/**
 * create and initialize separately allocated meter.
 */
struct ccnr_meter *
ccnr_meter_create(struct ccnr_handle *h, const char *what)
{
    struct ccnr_meter *m;
    m = calloc(1, sizeof(*m));
    if (m == NULL)
        return(NULL);
    ccnr_meter_init(h, m, what);
    return(m);
}

/**
 * Destroy a separately allocated meter.
 */
void
ccnr_meter_destroy(struct ccnr_meter **pm)
{
    if (*pm != NULL) {
        free(*pm);
        *pm = NULL;
    }
}

/**
 * Initialize a meter.
 */
void
ccnr_meter_init(struct ccnr_handle *h, struct ccnr_meter *m, const char *what)
{
    if (m == NULL)
        return;
    memset(m, 0, sizeof(*m));
    if (what != NULL)
        strncpy(m->what, what, sizeof(m->what)-1);
    ccnr_meter_bump(h, m, 0);
}

static const unsigned meterHz = 7; /* 1/ln(8/7) would give RC const of 1 sec */

/**
 * Count something (messages, packets, bytes), and roll up some kind of
 * statistics on it.
 */
void
ccnr_meter_bump(struct ccnr_handle *h, struct ccnr_meter *m, unsigned amt)
{
    unsigned now; /* my ticks, wrap OK */
    unsigned t;
    unsigned r;
    if (m == NULL)
        return;
    now = (((unsigned)(h->sec)) * meterHz) + (h->usec * meterHz / 1000000U);
    t = m->lastupdate;
    m->total += amt;
    if (now - t > 166U)
        m->rate = amt; /* history has decayed away */
    else {
        /* Decay the old rate exponentially based on time since last sample. */
        for (r = m->rate; t != now && r != 0; t++)
            r = r - ((r + 7U) / 8U); /* multiply by 7/8, truncating */
        m->rate = r + amt;
    }
    m->lastupdate = now;
}

/**
 * Return the average rate (units per second) of a metered quantity.
 *
 * m may be NULL.
 */
unsigned
ccnr_meter_rate(struct ccnr_handle *h, struct ccnr_meter *m)
{
    unsigned denom = 8;
    if (m == NULL)
        return(0);
    ccnr_meter_bump(h, m, 0);
    if (m->rate > 0x0FFFFFFF)
        return(m->rate / denom * meterHz);
    return ((m->rate * meterHz + (denom - 1)) / denom);
}

/**
 * Return the grand total for a metered quantity.
 *
 * m may be NULL.
 */
uintmax_t
ccnr_meter_total(struct ccnr_meter *m)
{
    if (m == NULL)
        return(0);
    return (m->total);
}
