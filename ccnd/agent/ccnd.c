/*
 * ccnd.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/hashtb.h>
#include <ccn/matrix.h>
#include <ccn/schedule.h>

#include "ccnd_private.h"

static void cleanup_at_exit(void);
static void unlink_at_exit(const char *path);
static int create_local_listener(const char *sockname, int backlog);
static void accept_new_client(struct ccnd *h);
static void shutdown_client_fd(struct ccnd *h, int fd);
static void process_input_message(struct ccnd *h, struct face *face,
                                  unsigned char *msg, size_t size, int pdu_ok);
static void process_input(struct ccnd *h, int fd);
static void do_write(struct ccnd *h, struct face *face,
                     unsigned char *data, size_t size);
static void do_deferred_write(struct ccnd *h, int fd);
static void run(struct ccnd *h);
static void clean_needed(struct ccnd *h);
static struct back_filter *bloom_create(struct ccnd *h, int n);
static void bloom_destroy(struct back_filter **);
static int bloom_insert(struct content_entry *, struct back_filter *);
static int bloom_match(struct content_entry *, const struct back_filter *);
static void bloom_update_for_old_content(struct ccnd *, struct interest_entry *);

static const char *unlink_this_at_exit = NULL;
static void
cleanup_at_exit(void)
{
    if (unlink_this_at_exit != NULL) {
        unlink(unlink_this_at_exit);
        unlink_this_at_exit = NULL;
    }
}

static void
handle_fatal_signal(int sig)
{
    cleanup_at_exit();
    _exit(sig);
}

static void
unlink_at_exit(const char *path)
{
    if (unlink_this_at_exit == NULL) {
        unlink_this_at_exit = path;
        signal(SIGTERM, &handle_fatal_signal);
        signal(SIGINT, &handle_fatal_signal);
        signal(SIGHUP, &handle_fatal_signal);
        atexit(&cleanup_at_exit);
    }
}

static int
comm_file_ok(void)
{
    struct stat statbuf;
    int res;
    if (unlink_this_at_exit == NULL)
        return(1);
    res = stat(unlink_this_at_exit, &statbuf);
    if (res == -1)
        return(0);
    return(1);
}

static void
fatal_err(const char *msg)
{
    perror(msg);
    exit(1);
}

static struct ccn_charbuf *
charbuf_obtain(struct ccnd *h)
{
    struct ccn_charbuf *c = h->scratch_charbuf;
    if (c == NULL)
        return(ccn_charbuf_create());
    h->scratch_charbuf = NULL;
    c->length = 0;
    return(c);
}

static void
charbuf_release(struct ccnd *h, struct ccn_charbuf *c)
{
    c->length = 0;
    if (h->scratch_charbuf == NULL)
        h->scratch_charbuf = c;
    else
        ccn_charbuf_destroy(&c);
}

static struct ccn_indexbuf *
indexbuf_obtain(struct ccnd *h)
{
    struct ccn_indexbuf *c = h->scratch_indexbuf;
    if (c == NULL)
        return(ccn_indexbuf_create());
    h->scratch_indexbuf = NULL;
    c->n = 0;
    return(c);
}

static void
indexbuf_release(struct ccnd *h, struct ccn_indexbuf *c)
{
    c->n = 0;
    if (h->scratch_indexbuf == NULL)
        h->scratch_indexbuf = c;
    else
        ccn_indexbuf_destroy(&c);
}

static struct face *
face_from_faceid(struct ccnd *h, unsigned faceid) {
    unsigned slot = faceid & MAXFACES;
    struct face *face = NULL;
    if (slot < h->face_limit) {
        face = h->faces_by_faceid[slot];
        if (face != NULL && face->faceid != faceid)
            face = NULL;
    }
    return(face);
}

static int
enroll_face(struct ccnd *h, struct face *face) {
    unsigned i;
    unsigned n = h->face_limit;
    struct face **a = h->faces_by_faceid;
    for (i = h->face_rover; i < n; i++)
        if (a[i] == NULL) goto use_i;
    for (i = 0; i < n; i++)
        if (a[i] == NULL) {
            /* bump gen only if second pass succeeds */
            h->face_gen += MAXFACES + 1;
            goto use_i;
        }
    i = (n + 1) * 3 / 2;
    if (i > MAXFACES) i = MAXFACES;
    if (i <= n)
        return(-1); /* overflow */
    a = realloc(a, i * sizeof(struct face *));
    if (a == NULL)
        return(-1); /* ENOMEM */
    h->face_limit = i;
    while (--i > n)
        a[i] = NULL;
    h->faces_by_faceid = a;
use_i:
    a[i] = face;
    h->face_rover = i + 1;
    face->faceid = i | h->face_gen;
    return (face->faceid);
}

static void
finalize_face(struct hashtb_enumerator *e)
{
    struct ccnd *h = hashtb_get_param(e->ht, NULL);
    struct face *face = e->data;
    unsigned i = face->faceid & MAXFACES;
    if (i < h->face_limit && h->faces_by_faceid[i] == face) {
        h->faces_by_faceid[i] = NULL;
        ccnd_msg(h, "releasing face id %u (slot %u)",
            face->faceid, face->faceid & MAXFACES);
        /* If face->addr is not NULL, it is our key so don't free it. */
        ccn_charbuf_destroy(&face->inbuf);
        ccn_charbuf_destroy(&face->outbuf);
    }
    else
        ccnd_msg(h, "orphaned face %u", face->faceid);
}

static struct content_entry *
content_from_accession(struct ccnd *h, uint_least64_t accession)
{
    struct content_entry *ans = NULL;
    if (accession >= h->accession_base &&
        accession < h->accession_base + h->content_by_accession_window) {
        ans = h->content_by_accession[accession - h->accession_base];
        if (ans != NULL && ans->accession != accession)
            ans = NULL;
    }
    return(ans);
}

static void
enroll_content(struct ccnd *h, struct content_entry *content)
{
    unsigned new_window;
    struct content_entry **new_array;
    struct content_entry **old_array = h->content_by_accession;
    unsigned i = 0;
    unsigned j = 0;
    if (content->accession >= h->accession_base + h->content_by_accession_window) {
        new_window = ((h->content_by_accession_window + 20) * 3 / 2);
        new_array = calloc(new_window, sizeof(new_array[0]));
        if (new_array == NULL)
            return;
        while (i < h->content_by_accession_window && old_array[i] == NULL)
            i++;
        h->accession_base += i;
        h->content_by_accession = new_array;
        while (i < h->content_by_accession_window)
            new_array[j++] = old_array[i++];
        h->content_by_accession_window = new_window;
	free(old_array);
    }
    h->content_by_accession[content->accession - h->accession_base] = content;
}

static void
finalize_content(struct hashtb_enumerator *e)
{
    struct ccnd *h = hashtb_get_param(e->ht, NULL);
    struct content_entry *entry = e->data;
    unsigned i = entry->accession - h->accession_base;
    if (i < h->content_by_accession_window && h->content_by_accession[i] == entry) {
        if (entry->sender != NULL) {
            ccn_schedule_cancel(h->sched, entry->sender);
            entry->sender = NULL;
        }
        h->content_by_accession[i] = NULL;
        if (entry->comps != NULL) {
            free(entry->comps);
            entry->comps = NULL;
        }
        if (entry->tail != NULL) {
            free(entry->tail);
            entry->tail = NULL;
        }
        ccn_indexbuf_destroy(&entry->faces);
    }
    else
        ccnd_msg(h, "orphaned content %u", i);
}

static void
finalize_interest(struct hashtb_enumerator *e)
{
    struct interest_entry *entry = e->data;
    ccn_indexbuf_destroy(&entry->interested_faceid);
    ccn_indexbuf_destroy(&entry->counters);
    bloom_destroy(&entry->back_filter);
}

static int
create_local_listener(const char *sockname, int backlog)
{
    int res;
    int sock;
    struct sockaddr_un a = { 0 };
    res = unlink(sockname);
    if (!(res == 0 || errno == ENOENT))
        ccnd_msg(NULL, "failed to unlink %s", sockname);
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, sockname, sizeof(a.sun_path));
    sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock == -1)
        return(sock);
    res = bind(sock, (struct sockaddr *)&a, sizeof(a));
    if (res == -1) {
        close(sock);
        return(-1);
    }
    unlink_at_exit(sockname);
    res = listen(sock, backlog);
    if (res == -1) {
        close(sock);
        return(-1);
    }
    return(sock);
}

static void
accept_new_client(struct ccnd *h)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct sockaddr who;
    socklen_t wholen = sizeof(who);
    int fd;
    int res;
    struct face *face;
    fd = accept(h->local_listener_fd, &who, &wholen);
    if (fd == -1) {
        perror("accept");
        return;
    }
    res = fcntl(fd, F_SETFL, O_NONBLOCK);
    if (res == -1)
        perror("fcntl");
    hashtb_start(h->faces_by_fd, e);
    if (hashtb_seek(e, &fd, sizeof(fd), 0) != HT_NEW_ENTRY)
        fatal_err("ccnd: accept_new_client");
    face = e->data;
    face->fd = fd;
    res = enroll_face(h, face);
    hashtb_end(e);
    ccnd_msg(h, "accepted client fd=%d id=%d", fd, res);
}

static void
shutdown_client_fd(struct ccnd *h, int fd)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct face *face;
    hashtb_start(h->faces_by_fd, e);
    if (hashtb_seek(e, &fd, sizeof(fd), 0) != HT_OLD_ENTRY)
        fatal_err("ccnd: shutdown_client_fd");
    face = e->data;
    if (face->fd != fd) abort();
    close(fd);
    face->fd = -1;
    ccnd_msg(h, "shutdown client fd=%d id=%d", fd, (int)face->faceid);
    ccn_charbuf_destroy(&face->inbuf);
    ccn_charbuf_destroy(&face->outbuf);
    hashtb_delete(e);
    hashtb_end(e);
}

static void
send_content(struct ccnd *h, struct face *face, struct content_entry *content) {
    struct ccn_charbuf *c = charbuf_obtain(h);
    if ((face->flags & CCN_FACE_LINK) != 0)
        ccn_charbuf_append_tt(c, CCN_DTAG_CCNProtocolDataUnit, CCN_DTAG);
    ccn_charbuf_append(c, content->key, content->key_size);
    ccn_charbuf_append(c, content->tail, content->tail_size);
    /* stuff interest here */
    if ((face->flags & CCN_FACE_LINK) != 0)
        ccn_charbuf_append_closer(c);
    do_write(h, face, c->buf, c->length);
    h->content_items_sent += 1;
    charbuf_release(h, c);
}

#define CCN_DATA_PAUSE (16U*1024U)

static int
choose_content_delay(struct ccnd *h, unsigned faceid, int content_flags)
{
    struct face *face = face_from_faceid(h, faceid);
    int shift = (content_flags & CCN_CONTENT_ENTRY_SLOWSEND) ? 2 : 0;
    if (face == NULL)
        return(1); /* Going nowhere, get it over with */
    if ((face->flags & CCN_FACE_DGRAM) != 0)
        return(100); /* localhost udp, delay just a little */
    if ((face->flags & CCN_FACE_LINK) != 0) /* udplink or such, delay more */
        return(((nrand48(h->seed) % CCN_DATA_PAUSE) + CCN_DATA_PAUSE/2) << shift);
    return(10); /* local stream, answer quickly */
}

static int
content_sender(struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    struct ccnd *h = clienth;
    struct content_entry *content = ev->evdata;
    if (content == NULL ||
        content != content_from_accession(h, content->accession)) {
        ccnd_msg(h, "ccn.c:%d bogon", __LINE__);
        return(0);
    }
    if ((flags & CCN_SCHEDULE_CANCEL) != 0 || content->faces == NULL) {
        content->sender = NULL;
        return(0);
    }
    while (content->nface_done < content->faces->n) {
        unsigned faceid = content->faces->buf[content->nface_done++];
        struct face *face = face_from_faceid(h, faceid);
        if (face != NULL) {
            send_content(h, face, content);
            if (content->nface_done < content->faces->n)
                return(choose_content_delay(h,
                        content->faces->buf[content->nface_done],
                        content->flags));
        }
    }
    content->sender = NULL;
    return(0);
}

/*
 * Returns index at which the element was found or added,
 * or -1 in case of error.
 */
static int
indexbuf_unordered_set_insert(struct ccn_indexbuf *x, size_t val)
{
    int i;
    if (x == NULL)
        return (-1);
    for (i = 0; i < x->n; i++)
        if (x->buf[i] == val)
            return(i);
    if (ccn_indexbuf_append_element(x, val) < 0)
        return(-1);
    return(i);
}

static int
content_faces_set_insert(struct content_entry *content, unsigned faceid)
{
    if (content->faces == NULL) {
        content->faces = ccn_indexbuf_create();
        content->nface_done = 0;
    }
    return (indexbuf_unordered_set_insert(content->faces, faceid));
}

/*
 * match_interests: Find and consume interests that match given content
 * Adds to content->faces the faceids that should receive copies,
 * and schedules content_sender if needed.  Returns number of matches.
 */
static int
match_interests(struct ccnd *h, struct content_entry *content)
{
    int n_matched = 0;
    int n;
    int i;
    int k;
    int ci;
    unsigned faceid;
    struct face *face = NULL;
    unsigned c0 = content->comps[0];
    const unsigned char *key = content->key + c0;
    for (ci = content->ncomps - 1; ci >= 0; ci--) {
        int size = content->comps[ci] - c0;
        struct interest_entry *interest = hashtb_lookup(h->interest_tab, key, size);
        if (interest != NULL) {
            if (content->accession > interest->newest) {
                if (ci > 0) {
                    intptr_t delta = (content->accession - interest->newest);
                    ccn_matrix_store(h->backlinks, content->accession, ci, delta);
                }
                interest->newest = content->accession;
                interest->matches += 1;
                if (interest->back_filter != NULL)
                    bloom_insert(content, interest->back_filter);                
            }
            n = (interest->counters == NULL) ? 0 : interest->counters->n;
            for (i = 0; i < n; i++) {
                /* Use signed count for this calculation */
                intptr_t count = interest->counters->buf[i];
                if (count > 0) {
                    faceid = interest->interested_faceid->buf[i];
                    face = face_from_faceid(h, faceid);
                    if (face != NULL) {
                        k = content_faces_set_insert(content, faceid);
                        if (k >= content->nface_done) {
                            n_matched += 1;
                            count -= CCN_UNIT_INTEREST;
                            if (count < 0)
                                count = 0;
                        }
                    }
                    else
                        count = 0;
                    interest->counters->buf[i] = count;
                }
            }
        }
    }
    if (content->sender == NULL &&
          content->faces != NULL &&
          content->faces->n > content->nface_done)
        content->sender = ccn_schedule_event(h->sched,
            choose_content_delay(h, content->faces->buf[content->nface_done],
                                    content->flags),
            content_sender, content, 0);
    return(n_matched);
}

/*
 * adjust_filters_matching_interests:
 * Install new back filter(s) on all interests matching the content.
 */
static void
adjust_filters_matching_interests(struct ccnd *h, struct content_entry *content)
{
    int ci;
    unsigned c0 = content->comps[0];
    const unsigned char *key = content->key + c0;
    for (ci = content->ncomps - 1; ci >= 0; ci--) {
        int size = content->comps[ci] - c0;
        struct interest_entry *interest = hashtb_lookup(h->interest_tab, key, size);
        if (interest != NULL) {
            bloom_destroy(&interest->back_filter);
            if (interest->matches < 20000) { // XXX 20000
                interest->back_filter = bloom_create(h, interest->matches);
                bloom_update_for_old_content(h, interest);
            }
        }
    }
}

static void
clean_filters(struct ccnd *h)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct interest_entry *interest;
    hashtb_start(h->interest_tab, e);
    for (interest = e->data; interest != NULL; interest = e->data) {
        bloom_destroy(&interest->back_filter);
        hashtb_next(e);
    }
    hashtb_end(e);
}

/*
 * age_interests:
 * This is called several times per interest halflife to age
 * the interest counters.  Returns the number of still-active counts.
 */
#define CCN_INTEREST_AGING_MICROSEC (CCN_INTEREST_HALFLIFE_MICROSEC / 4)
static int
age_interests(struct ccnd *h) {
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct interest_entry *interest;
    int n;
    int i;
    int n_active = 0;
    hashtb_start(h->interest_tab, e);
    for (interest = e->data; interest != NULL; interest = e->data) {
        n = interest->counters->n;
        if (n > 0)
            interest->idle = 0;
        else if ((++interest->idle) > 8) {
            hashtb_delete(e);
            continue;
        }
        interest->cached_faceid = ~0;
        for (i = 0; i < n; i++) {
            size_t count = interest->counters->buf[i];
            if (count > CCN_UNIT_INTEREST) {
                /* factor of approximately the fourth root of 1/2 */
                interest->counters->buf[i] = (count * 5 + 3) / 6;
            }
            else if (count > 0) {
                interest->counters->buf[i] -= 1;
            }
            else {
                /* count was 0, remove this counter */
                interest->interested_faceid->buf[i] = interest->interested_faceid->buf[n-1];
                interest->counters->buf[i] = interest->counters->buf[n-1];
                i -= 1;
                n -= 1;
                interest->interested_faceid->n = interest->counters->n = n;
            }
        }
        n_active += n;
        hashtb_next(e);
    }
    hashtb_end(e);
    return(n_active);
}

/*
 * do_write_BFI:
 * This is temporary...
 */
static void
do_write_BFI(struct ccnd *h, struct face *face,
             unsigned char *data, size_t size) {
    struct ccn_charbuf *c;
    if ((face->flags & CCN_FACE_LINK) != 0) {
        c = charbuf_obtain(h);
        ccn_charbuf_reserve(c, size + 5);
        ccn_charbuf_append_tt(c, CCN_DTAG_CCNProtocolDataUnit, CCN_DTAG);
        ccn_charbuf_append(c, data, size);
        ccn_charbuf_append_closer(c);
        do_write(h, face, c->buf, c->length);
        charbuf_release(h, c);
        return;
    }
    do_write(h, face, data, size);
}

/*
 * This checks for inactivity on datagram faces.
 * Returns number of faces that have gone away.
 */
static int
check_dgram_faces(struct ccnd *h)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int count = 0;
    hashtb_start(h->dgram_faces, e);
    while (e->data != NULL) {
        struct face *face = e->data;
        if ((face->flags & CCN_FACE_DGRAM) != 0 && face->addr != NULL) {
            if (face->recvcount == 0) {
                count += 1;
                hashtb_delete(e);
                continue;
            }
            face->recvcount = (face->recvcount > 1); /* go around twice */
        }
        hashtb_next(e);
    }
    hashtb_end(e);
    return(count);
}

/*
 * This checks for expired propagating interests.
 * Returns number that have gone away.
 */
static int
check_propagating(struct ccnd *h)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int count = 0;
    hashtb_start(h->propagating_tab, e);
    while (e->data != NULL) {
        struct propagating_entry *pi = e->data;
        if (pi->interest_msg == NULL) {
            if (pi->size == 0) {
                count += 1;
                hashtb_delete(e);
                continue;
            }
            pi->size = (pi->size > 1); /* go around twice */
        }
        hashtb_next(e);
    }
    hashtb_end(e);
    return(count);
}

static int
reap(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    struct ccnd *h = clienth;
    if ((flags & CCN_SCHEDULE_CANCEL) == 0) {
        check_dgram_faces(h);
        check_propagating(h);
        if (!comm_file_ok()) {
            ccnd_msg(h, "exiting (%s gone)", unlink_this_at_exit);
            exit(0);
        }
        if (hashtb_n(h->dgram_faces) > 0 || hashtb_n(h->propagating_tab) > 0)
            return(2 * CCN_INTEREST_HALFLIFE_MICROSEC);
    }
    /* nothing on the horizon, so go away */
    h->reaper = NULL;
    return(0);
}

static void
reap_needed(struct ccnd *h, int init_delay_usec)
{
    if (h->reaper == NULL)
        h->reaper = ccn_schedule_event(h->sched, init_delay_usec, reap, NULL, 0);
}

static int
aging_deamon(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    struct ccnd *h = clienth;
    if ((flags & CCN_SCHEDULE_CANCEL) == 0) {
        age_interests(h);
        if (hashtb_n(h->interest_tab) != 0)
            return(ev->evint);
    }
    /* nothing on the horizon, so go away */
    h->age = NULL;
    return(0);
}

static void
aging_needed(struct ccnd *h)
{
    if (h->age == NULL) {
        int period = CCN_INTEREST_AGING_MICROSEC;
        h->age = ccn_schedule_event(h->sched, period, aging_deamon, NULL, period);
    }
}

/*
 * clean_deamon: weeds expired faceids out of the content table
 * and expires short-term blocking state.
 */
static int
clean_deamon(
             struct ccn_schedule *sched,
             void *clienth,
             struct ccn_scheduled_event *ev,
             int flags)
{
    struct ccnd *h = clienth;
    unsigned i;
    unsigned n;
    struct content_entry* content;
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        h->clean = NULL;
        return(0);
    }
    n = h->accession - h->accession_base + 1;
    if (n > h->content_by_accession_window)
        n = h->content_by_accession_window;
    for (i = 0; i < n; i++) {
        content = h->content_by_accession[i];
        if (content != NULL && content->faces != NULL) {
            int j, k, d;
            for (j = 0, k = 0, d = 0; j < content->faces->n; j++) {
                unsigned faceid = content->faces->buf[j];
                struct face *face = face_from_faceid(h, faceid);
                if (face != NULL) {
                    if (j < content->nface_old) {
                        if ((face->flags & CCN_FACE_LINK) != 0)
                            continue;
                    }
                    if (j < content->nface_done)
                        d++;
                    content->faces->buf[k++] = faceid;
                }
            }
            if (k < content->faces->n) {
                content->faces->n = k;
                content->nface_done = d;
            }
            content->nface_old = d;
        }
    }
    clean_filters(h);
    return(15000000);
}

static void
clean_needed(struct ccnd *h)
{
    if (h->clean == NULL)
        h->clean = ccn_schedule_event(h->sched, 1000000, clean_deamon, NULL, 0);
}

/*
 * This is where a forwarding table would be plugged in.
 * For now we forward everywhere but the source, subject to scope.
 */
static struct ccn_indexbuf *
get_outbound_faces(struct ccnd *h,
    struct face *from,
    unsigned char *msg,
    struct ccn_parsed_interest *pi)
{
    struct ccn_indexbuf *x = ccn_indexbuf_create();
    unsigned i;
    struct face **a = h->faces_by_faceid;
    int blockmask = 0;
    if (pi->scope == 0)
        return(x);
    if (pi->scope == 1)
        blockmask = CCN_FACE_LINK;
    for (i = 0; i < h->face_limit; i++)
        if (a[i] != NULL && a[i] != from && ((a[i]->flags & blockmask) == 0))
            ccn_indexbuf_append_element(x, a[i]->faceid);
    return(x);
}

static int
indexbuf_member(struct ccn_indexbuf *x, size_t val)
{
    int i;
    if (x == NULL)
        return (-1);
    for (i = x->n - 1; i >= 0; i--)
        if (x->buf[i] == val)
            return(i);
    return(-1);
}

static void
indexbuf_remove_element(struct ccn_indexbuf *x, size_t val)
{
    int i;
    if (x == NULL) return;
    for (i = x->n - 1; i >= 0; i--)
        if (x->buf[i] == val) {
            x->buf[i] = x->buf[--x->n]; /* move last element into vacant spot */
            return;
        }
}

static int
do_propagate(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    struct ccnd *h = clienth;
    struct propagating_entry *pe = ev->evdata;
    if (pe->outbound == NULL || pe->interest_msg == NULL)
        return(0);
    if (flags & CCN_SCHEDULE_CANCEL)
        pe->outbound->n = 0;
    if (pe->outbound->n > 0) {
        unsigned faceid = pe->outbound->buf[--pe->outbound->n];
        struct face *face = face_from_faceid(h, faceid);
        if (face != NULL) {
            do_write_BFI(h, face, pe->interest_msg, pe->size);
            h->interests_sent += 1;
        }
    }
    if (pe->outbound->n == 0) {
        if (pe->interest_msg != NULL)
            free(pe->interest_msg);
        pe->interest_msg = NULL;
        ccn_indexbuf_destroy(&pe->outbound);
        reap_needed(h, 0);
        return(0);
    }
    return(nrand48(h->seed) % 8192 + 500);
}

static int
propagate_interest(struct ccnd *h, struct face *face,
                      unsigned char *msg, size_t msg_size,
                      struct ccn_parsed_interest *pi,
                      struct interest_entry *ie)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    unsigned char *pkey;
    size_t pkeysize;
    struct ccn_charbuf *cb = NULL;
    int res;
    struct propagating_entry *pe = NULL;
    unsigned char *msg_out = msg;
    size_t msg_out_size = msg_size;
    if (pi->nonce_size == 0) {
        /* This interest has no nonce; add one before going on */
        int noncebytes = 6;
        size_t nonce_start = 0;
        int i;
        unsigned char *s;
        cb = charbuf_obtain(h);
        ccn_charbuf_append(cb, msg, pi->nonce_start);
        nonce_start = cb->length;
        ccn_charbuf_append_tt(cb, CCN_DTAG_Nonce, CCN_DTAG);
        ccn_charbuf_append_tt(cb, noncebytes, CCN_BLOB);
        s = ccn_charbuf_reserve(cb, noncebytes);
        for (i = 0; i < noncebytes; i++)
            s[i] = nrand48(h->seed) >> i;
        cb->length += noncebytes;
        ccn_charbuf_append_closer(cb);
        pkeysize = cb->length - nonce_start;
        if (ie->back_filter != NULL && pi->offset[CCN_PI_E_OTHER] == pi->offset[CCN_PI_B_OTHER]) {
            /* send our current Bloom filter if we have one and none is there */
            /* XXX - this should probably be independent of adding the Nonce */
            struct back_filter *f = ie->back_filter;
            size_t size = sizeof(*f) - sizeof(f->bloom) + (1 << (f->lg_bits - 3));
            ccn_charbuf_append_tt(cb, CCN_DTAG_ExperimentalResponseFilter, CCN_DTAG);
            ccn_charbuf_append_tt(cb, size, CCN_BLOB);
            ccn_charbuf_append(cb, f, size);
            ccn_charbuf_append_closer(cb);
        }
        ccn_charbuf_append(cb, msg + pi->nonce_start,
                               msg_size - pi->nonce_start);
        pkey = cb->buf + nonce_start;
        msg_out = cb->buf;
        msg_out_size = cb->length;
    }
    else {
        pkey = msg + pi->nonce_start;
        pkeysize = pi->nonce_size;
    }
    hashtb_start(h->propagating_tab, e);
    res = hashtb_seek(e, pkey, pkeysize, 0);
    pe = e->data;
    if (res == HT_NEW_ENTRY) {
        unsigned char *m;
        m = calloc(1, msg_out_size);
        if (m == NULL) {
            res = -1;
            pe = NULL;
            hashtb_delete(e);
        }
        else {
            memcpy(m, msg_out, msg_out_size);
            pe->interest_msg = m;
            pe->size = msg_out_size;
            pe->outbound = get_outbound_faces(h, face, msg, pi);
            res = 0;
            ccn_schedule_event(h->sched, nrand48(h->seed) % 8192, do_propagate, pe, 0);
        }
    }
    else if (res == HT_OLD_ENTRY) {
        ccnd_msg(h, "Interesting - this shouldn't happen much - ccnd.c:%d", __LINE__);
        if (pe->outbound != NULL)
            indexbuf_remove_element(pe->outbound, face->faceid);
        res = -1; /* We've seen this already, do not propagate */
    }
    hashtb_end(e);
    if (cb != NULL)
        charbuf_release(h, cb);
    return(res);
}

static void
create_backlinks_for_new_interest(struct ccnd *h,
      struct interest_entry *interest,
      unsigned char *msg, struct ccn_indexbuf *comps) {
    struct interest_entry *shorter = NULL;
    int n = comps->n;
    int i;
    int col = 0;
    uint_least64_t accession = h->accession;
    uint_least64_t newer = 0;
    size_t keysize;
    intptr_t delta = 1;
    if (n <= 1) {
        /* Pointless to create backlinks if everything matches */
        interest->newest = h->accession;
        interest->matches = hashtb_n(h->content_tab);
        return; 
    }
    /* Try to bootstrap using the longest interest shorter than this one */
    keysize = comps->buf[n-1] - comps->buf[0];
    for (i = n - 2; (shorter != NULL) && i > 0; i--) {
        shorter = hashtb_lookup(h->interest_tab,
            msg + comps->buf[0], comps->buf[i] - comps->buf[0]);
    }
    if (shorter != NULL) {
        accession = shorter->newest;
        col = i;
    }
    interest->matches = 0;
    while (accession >= h->accession_base) {
        struct content_entry *content = content_from_accession(h, accession);
        if (content != NULL &&
              content->ncomps >= n &&
              keysize == content->comps[n-1] - content->comps[0] &&
              0 == memcmp(msg + comps->buf[0],
                          content->key + content->comps[0], keysize)) {
            if (newer == 0)
                interest->newest = accession;
            else
                ccn_matrix_store(h->backlinks, newer, n-1, newer - accession);
            newer = accession;
            interest->matches += 1;
        }
        if (col > 0) {
            delta = ccn_matrix_fetch(h->backlinks, accession, col);
            if (delta == 0)
                break;
        }
        if (delta > accession)
            break;
        accession -= delta;
    }
}

static int
is_duplicate_flooded(struct ccnd *h, unsigned char *msg, struct ccn_parsed_interest *pi)
{
    struct propagating_entry *pe = NULL;
    if (pi->nonce_size == 0)
        return(0);
    pe = hashtb_lookup(h->propagating_tab, msg + pi->nonce_start, pi->nonce_size);
    return(pe != NULL);
}

static struct back_filter *
bloom_create(struct ccnd *h, int n)
{
    struct back_filter *f;
    int i;
    f = calloc(1, sizeof(*f));
    if (f != NULL) {
        f->method = 's';
        f->lg_bits = 13;
        /* try for about m = 12*n (m = bits in Bloom filter) */
        while (f->lg_bits > 3 && (1 << f->lg_bits) > n * 12)
            f->lg_bits--;
        /* optimum number of hash functions is ln(2)*(m/n); use ln(2) ~= 9/13 */
        f->n_hash = (9 << f->lg_bits) / (13 * n + 1);
        if (f->n_hash < 2)
            f->n_hash = 2;
        if (f->n_hash > 32)
            f->n_hash = 32;
        for (i = 0; i < sizeof(f->seed); i++)
            f->seed[i] = nrand48(h->seed) >> 8;
    }
    return(f);
}

static void
bloom_destroy(struct back_filter **f)
{
    if (*f == NULL) return;
    free(*f);
    *f = NULL;
}

const struct back_filter *
bloom_validate(const unsigned char *buf, size_t size)
{
    const struct back_filter *f = (const struct back_filter *)buf;
    if (size < 9)
        return (NULL);
    if (f->lg_bits > 13 || f->lg_bits < 3)
        return (NULL);
    if (f->n_hash < 1 || f->n_hash > 16)
        return (NULL);
    if (size != (sizeof(*f) - sizeof(f->bloom)) + (1 << (f->lg_bits - 3)))
        return (NULL);
    if (!(f->reserved == 0 && f->method == 's'))
        return (NULL);
    return(f);
}

static int
bloom_seed(const struct back_filter *f, const unsigned char *x)
{
    unsigned u;
    const unsigned char *s = f->seed;
    u = ((s[0] + x[0]) << 24) |
        ((s[1] + x[1]) << 16) |
        ((s[2] + x[2]) << 8) |
        (s[3] + x[3]);
    return(u & 0x7FFFFFFF);
}

static int
bloom_nexthash(int s, unsigned char u)
{
    const int k = 13; /* use this many bits of feedback shift output */
    int b = s & ((1 << k) - 1);
    /* fsr primitive polynomial (modulo 2) x**31 + x**13 + 1 */
    s = ((s >> k) ^ (b << (31 - k)) ^ (b << (13 - k))) + u;
    return(s);
}

static void
bloom_gethashbytes(struct content_entry *content, unsigned char method,
        unsigned char *hb, size_t size)
{
    if (method != 's') abort(); /* should have screened this earlier */
    if (content->sig_offset > 0)
        memcpy(hb, content->key + content->sig_offset, size);
}

/*
 * bloom_insert:
 * Returns the number of bits changed in the filter, so a zero return
 * means a collison has happened.
 */
static int
_bloom_insert(struct content_entry *content, struct back_filter *f)
{
    int d = 0;
    int n = f->n_hash;
    int m = (8*sizeof(f->bloom) - 1) & ((1 << f->lg_bits) - 1);
    unsigned char hb[32] = {0};
    int i, k, h, s;
    bloom_gethashbytes(content, f->method, hb, sizeof(hb));
    s = bloom_seed(f, hb);
    for (k = 0; k < 4; k++)
        s = bloom_nexthash(s, 0);
    for (i = 0; i < n; i++) {
        h = s & m;
        if (0 == (f->bloom[h >> 3] & (1 << (h & 7)))) {
            f->bloom[h >> 3] |= (1 << (h & 7));
            d++;
        }
        f->bloom[h >> 3] |= (1 << (h & 7));
        if (i + 1 == n) break;
        s = bloom_nexthash(s, hb[k++ % 32]);
    }
    return(d);
}

/*
 * bloom_match:
 * This is identical to bloom_insert except at the fringes and what
 * happens in the inner loop.
 */
static int
bloom_match(struct content_entry *content, const struct back_filter *f)
{
    int n = f->n_hash;
    int m = (8*sizeof(f->bloom) - 1) & ((1 << f->lg_bits) - 1);
    unsigned char hb[32] = {0};
    int i, k, h, s;
    bloom_gethashbytes(content, f->method, hb, sizeof(hb));
    s = bloom_seed(f, hb);
    for (k = 0; k < 4; k++)
        s = bloom_nexthash(s, 0);
    for (i = 0; i < n; i++) {
        h = s & m;
        if (0 == (f->bloom[h >> 3] & (1 << (h & 7))))
            return(0);
        if (i + 1 == n) break;
        s = bloom_nexthash(s, hb[k++ % 32]);
    }
    return(1);
}

static int ccnd_debug_bloom = 0;
static int
bloom_insert(struct content_entry *content, struct back_filter *f)
{
    int d;
    if (ccnd_debug_bloom && bloom_match(content, f)) {
        unsigned char zero[4] = {0};
        unsigned char hb[32] = {0};
        bloom_gethashbytes(content, f->method, hb, sizeof(hb));
        fprintf(stderr, "Bloom collision! lg_bits = %d, n_hash = %d, seed = %d, sig = %02X%02X%02X...\n",
                (int)f->lg_bits, (int)f->n_hash, bloom_seed(f, zero), (int)hb[0], (int)hb[1], (int)hb[2]);
    }
    d = _bloom_insert(content, f);
    if (!bloom_match(content, f))
        fprintf(stderr, "Bloom bug!!!!\n");
    return(d);
}

static void
bloom_update_for_old_content(struct ccnd *h, struct interest_entry *interest)
{
    struct back_filter *f = interest->back_filter;
    int ncomps = interest->ncomp;
    struct content_entry *content = NULL;
    uint_least64_t accession;
    intptr_t delta = 1;
    if (f == NULL)
        return;
    for (accession = interest->newest; accession >= h->accession_base; accession -= delta) {
        content = content_from_accession(h, accession);
        if (content != NULL)
            bloom_insert(content, f);
        if (ncomps > 0) {
            delta = ccn_matrix_fetch(h->backlinks, accession, ncomps);
            if (delta < 0) abort();
        }
        if (delta == 0 || delta > accession)
            break;
    }
}

/*
 * content_is_unblocked: 
 * Decide whether to send content in response to the interest, which
 * we already know is a prefix match.
 */
static int
content_is_unblocked(struct content_entry *content,
        struct ccn_parsed_interest *pi, unsigned char *msg, unsigned faceid)
{
    const unsigned char *filtbuf = NULL;
    size_t filtsize = 0;
    const struct back_filter *f = NULL;
    int k;
    if (pi->offset[CCN_PI_E_OTHER] > pi->offset[CCN_PI_B_OTHER]) {
        struct ccn_buf_decoder decoder;
        struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder,
                msg + pi->offset[CCN_PI_B_OTHER],
                pi->offset[CCN_PI_E_OTHER] - pi->offset[CCN_PI_B_OTHER]);
        if (ccn_buf_match_dtag(d, CCN_DTAG_ExperimentalResponseFilter)) {
            ccn_buf_advance(d);
            ccn_buf_match_blob(d, &filtbuf, &filtsize);
            f = bloom_validate(filtbuf, filtsize);
        }
    }
    if (f != NULL) {
        if (bloom_match(content, f))
            return(0);
        /* Not in filter, so send even if we have sent before. */
        k = indexbuf_member(content->faces, faceid);
        if (0 <= k && k < content->nface_done) {
            content->faces->buf[k] = ~0;
            return(1);
        }
        /* Say no if we are already planning to send anyway */
        return(k == -1);
    }
    return(indexbuf_member(content->faces, faceid) == -1);
}

static void
process_incoming_interest(struct ccnd *h, struct face *face,
                      unsigned char *msg, size_t size)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_parsed_interest parsed_interest = {0};
    size_t namesize = 0;
    int res;
    int matched;
    struct interest_entry *interest = NULL;
    struct ccn_indexbuf *comps = indexbuf_obtain(h);
    res = ccn_parse_interest(msg, size, &parsed_interest, comps);
    if (res < 0) {
        ccnd_msg(h, "error parsing Interest - code %d", res);
    }
    else if (parsed_interest.scope > 0 && parsed_interest.scope < 2 &&
             (face->flags & CCN_FACE_LINK) != 0) {
        ccnd_msg(h, "Interest from %u out of scope - discarded", face->faceid);
        res = -__LINE__;
    }
    else if (comps->n < 1 ||
             (namesize = comps->buf[comps->n - 1] - comps->buf[0]) > 65535) {
        ccnd_msg(h, "Interest with namesize %lu discarded",
                (unsigned long)namesize);
        res = -__LINE__;
    }
    else if (is_duplicate_flooded(h, msg, &parsed_interest)) {
        h->interests_dropped += 1;
    }
    else {
        h->interests_accepted += 1;
        matched = 0;
        hashtb_start(h->interest_tab, e);
        res = hashtb_seek(e, msg + comps->buf[0], namesize, 0);
        interest = e->data;
        if (res == HT_NEW_ENTRY) {
            interest->ncomp = comps->n - 1;
            interest->interested_faceid = ccn_indexbuf_create();
            interest->counters = ccn_indexbuf_create();
            interest->cached_faceid = ~0U;
            ccnd_msg(h, "New interest");
            create_backlinks_for_new_interest(h, interest, msg, comps);
        }
        if (interest != NULL) {
            struct content_entry *content = NULL;
            uint_least64_t accession;
            res = indexbuf_unordered_set_insert(interest->interested_faceid, face->faceid);
            while (interest->counters->n <= res)
                if (0 > ccn_indexbuf_append_element(interest->counters, 0)) break;
            if (0 <= res && res < interest->counters->n)
                interest->counters->buf[res] += CCN_UNIT_INTEREST;
            if (face->faceid == interest->cached_faceid)
                accession = interest->cached_accession;
            else if (comps->n == 1)
                accession = h->accession;
            else
                accession = interest->newest;
            while (accession >= h->accession_base) {
                content = content_from_accession(h, accession);
                if (content != NULL &&
                      content_is_unblocked(content, &parsed_interest, msg, face->faceid))
                    break;
                content = NULL;
                if (comps->n == 1) {
                    if (accession-- == 0) break;
                }
                else {
                    intptr_t delta = ccn_matrix_fetch(h->backlinks, accession, comps->n - 1);
                    if (delta <= 0 || delta > accession)
                        break;
                    accession -= delta;
                }
            }
            if (content != NULL) {
                match_interests(h, content);
                interest->cached_accession = content->accession;
                interest->cached_faceid = face->faceid;
                matched = 1;
            }
        }
        hashtb_end(e);
        aging_needed(h);
        if (!matched && parsed_interest.scope != 0)
            propagate_interest(h, face, msg, size, &parsed_interest, interest);
    }
    indexbuf_release(h, comps);
}

int
get_signature_offset(struct ccn_parsed_ContentObject *pco, const unsigned char *msg)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    if (pco->Signature >= 0 && pco->Content > pco->Signature) {
        d = ccn_buf_decoder_start(&decoder, msg + pco->Signature, pco->Content - pco->Signature);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Signature)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_some_blob(d) && d->decoder.numval >= 32) {
                return(pco->Signature + d->decoder.index);
            }
        }
    }
    return(0);
}

static void
process_incoming_content(struct ccnd *h, struct face *face,
                      unsigned char *msg, size_t size)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_parsed_ContentObject obj = {0};
    int res;
    size_t keysize = 0;
    size_t tailsize = 0;
    unsigned char *tail = NULL;
    struct content_entry *content = NULL;
    int i;
    struct ccn_indexbuf *comps = indexbuf_obtain(h);
    res = ccn_parse_ContentObject(msg, size, &obj, comps);
    if (res < 0) {
        ccnd_msg(h, "error parsing ContentObject - code %d", res);
    }
    else if (comps->n < 1 ||
             (keysize = comps->buf[comps->n - 1]) > 65535) {
        ccnd_msg(h, "ContentObject with keysize %lu discarded",
                (unsigned long)keysize);
        res = -__LINE__;
    }
    else {
        keysize = obj.Content;
        tail = msg + obj.Content;
        tailsize = size - obj.Content;
        hashtb_start(h->content_tab, e);
        res = hashtb_seek(e, msg, keysize, 0);
        content = e->data;
        if (res == HT_OLD_ENTRY) {
            if (tailsize != content->tail_size ||
                  0 != memcmp(tail, content->tail, tailsize)) {
                ccnd_msg(h, "ContentObject name collision!!!!!");
                content = NULL;
                hashtb_delete(e); /* XXX - Mercilessly throw away both of them. */
                res = -__LINE__;
            }
            else {
                h->content_dups_recvd++;
                ccnd_msg(h, "received duplicate ContentObject from %u (accession %llu)",
                    face->faceid, (unsigned long long)content->accession);
                adjust_filters_matching_interests(h, content);
                /* Make note that this face knows about this content */
                i = content_faces_set_insert(content, face->faceid);
                if (i >= content->nface_done) {
                    content->faces->buf[i] = content->faces->buf[content->nface_done];
                    content->faces->buf[content->nface_done++] = face->faceid;
                }
            }
        }
        else if (res == HT_NEW_ENTRY) {
            content->accession = ++(h->accession);
            content->faces = ccn_indexbuf_create();
            ccn_indexbuf_append_element(content->faces, face->faceid);
            content->nface_done = 1;
            enroll_content(h, content);
            content->ncomps = comps->n;
            content->comps = calloc(comps->n, sizeof(comps[0]));
            content->tail_size = tailsize;
            content->tail = calloc(1, tailsize);
            content->sig_offset = get_signature_offset(&obj, msg);
            content->key_size = e->keysize;
            content->key = e->key;
            if (content->tail != NULL && content->comps != NULL && content->faces != NULL) {
                memcpy(content->tail, tail, tailsize);
                for (i = 0; i < comps->n; i++)
                    content->comps[i] = comps->buf[i];
            }
            else {
                perror("process_incoming_content");
                hashtb_delete(e);
                res = -__LINE__;
                content = NULL;
            }
        }
        hashtb_end(e);
    }
    indexbuf_release(h, comps);
    if (res >= 0 && content != NULL) {
        int n_matches;
        n_matches = match_interests(h, content);
        if (res == HT_NEW_ENTRY && n_matches == 0)
            content->flags |= CCN_CONTENT_ENTRY_SLOWSEND;
    }
}

static void
process_input_message(struct ccnd *h, struct face *face,
                      unsigned char *msg, size_t size, int pdu_ok)
{
    struct ccn_skeleton_decoder decoder = {0};
    struct ccn_skeleton_decoder *d = &decoder;
    ssize_t dres;
    d->state |= CCN_DSTATE_PAUSE;
    dres = ccn_skeleton_decode(d, msg, size);
    if (d->state >= 0 && CCN_GET_TT_FROM_DSTATE(d->state) == CCN_DTAG) {
        if (pdu_ok && d->numval == CCN_DTAG_CCNProtocolDataUnit) {
            size -= d->index;
            if (size > 0)
                size--;
            msg += d->index;
            face->flags |= CCN_FACE_LINK;
            memset(d, 0, sizeof(*d));
            while (d->index < size) {
                dres = ccn_skeleton_decode(d, msg + d->index, size - d->index);
                if (d->state != 0)
                    break;
                process_input_message(h, face, msg + d->index - dres, dres, 0);
            }
            return;
        }
        else if (d->numval == CCN_DTAG_Interest) {
            process_incoming_interest(h, face, msg, size);
            return;
        }
        else if (d->numval == CCN_DTAG_ContentObject) {
            process_incoming_content(h, face, msg, size);
            return;
        }
    }
    ccnd_msg(h, "discarding unknown message; size = %lu", (unsigned long)size);
}

struct face *
get_dgram_source(struct ccnd *h, struct face *face,
           struct sockaddr *addr, socklen_t addrlen)
{
    struct face *source;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int res;
    if ((face->flags & CCN_FACE_DGRAM) == 0)
        return(face);
    hashtb_start(h->dgram_faces, e);
    res = hashtb_seek(e, addr, addrlen, 0);
    if (res >= 0) {
        source = e->data;
        if (source->addr == NULL) {
            source->addr = e->key;
            source->addrlen = e->keysize;
            source->fd = face->fd;
            source->flags |= CCN_FACE_DGRAM;
            enroll_face(h, source);
            reap_needed(h, CCN_INTEREST_HALFLIFE_MICROSEC);
        }
        source->recvcount++;
    }
    hashtb_end(e);
    return(source);
}

static void
process_input(struct ccnd *h, int fd)
{
    struct face *face = hashtb_lookup(h->faces_by_fd, &fd, sizeof(fd));
    struct face *source = NULL;
    ssize_t res;
    ssize_t dres;
    ssize_t msgstart;
    unsigned char *buf;
    struct ccn_skeleton_decoder *d = &face->decoder;
    struct sockaddr_storage sstor;
    socklen_t addrlen = sizeof(sstor);
    struct sockaddr *addr = (struct sockaddr *)&sstor;
    if (face->inbuf == NULL)
        face->inbuf = ccn_charbuf_create();
    if (face->inbuf->length == 0)
        memset(d, 0, sizeof(*d));
    buf = ccn_charbuf_reserve(face->inbuf, 8800);
    res = recvfrom(face->fd, buf, face->inbuf->limit - face->inbuf->length,
            /* flags */ 0, addr, &addrlen);
    if (res == -1)
        perror("ccnd: recvfrom");
    else if (res == 0) {
        if ((face->flags & CCN_FACE_DGRAM) == 0)
            shutdown_client_fd(h, fd);
        else
            face->recvcount++;
    }
    else {
        face->recvcount++;
        source = get_dgram_source(h, face, addr, addrlen);
        face->inbuf->length += res;
        msgstart = 0;
        dres = ccn_skeleton_decode(d, buf, res);
        if (0) ccnd_msg(h, "ccn_skeleton_decode of %d bytes accepted %d",
                        (int)res, (int)dres);
        while (d->state == 0) {
            if (0) ccnd_msg(h, "%lu byte msg received on %d",
                (unsigned long)(d->index - msgstart), fd);
            process_input_message(h, source, face->inbuf->buf + msgstart, 
                                           d->index - msgstart, 1);
            msgstart = d->index;
            if (msgstart == face->inbuf->length) {
                face->inbuf->length = 0;
                return;
            }
            dres = ccn_skeleton_decode(d,
                    face->inbuf->buf + d->index,
                    res = face->inbuf->length - d->index);
            if (0) ccnd_msg(h, "  ccn_skeleton_decode of %d bytes accepted %d",
                            (int)res, (int)dres);
        }
        if ((face->flags & CCN_FACE_DGRAM) != 0) {
            ccnd_msg(h, "ccnd[%d]: protocol error, discarding %d bytes",
                getpid(), (int)(face->inbuf->length - d->index));
            face->inbuf->length = 0;
            return;
        }
        else if (d->state < 0) {
            ccnd_msg(h, "ccnd[%d]: protocol error on fd %d", getpid(), fd);
            shutdown_client_fd(h, fd);
            return;
        }
        if (msgstart < face->inbuf->length && msgstart > 0) {
            /* move partial message to start of buffer */
            memmove(face->inbuf->buf, face->inbuf->buf + msgstart,
                face->inbuf->length - msgstart);
            face->inbuf->length -= msgstart;
            d->index -= msgstart;
        }
    }
}

static void
do_write(struct ccnd *h, struct face *face, unsigned char *data, size_t size)
{
    ssize_t res;
    if (face->outbuf != NULL) {
        ccn_charbuf_append(face->outbuf, data, size);
        return;
    }
    if (face->addr == NULL)
        res = send(face->fd, data, size, 0);
    else {
        res = sendto(face->fd, data, size, 0, face->addr, face->addrlen);
    }
    if (res == size)
        return;
    if (res == -1) {
        if (errno == EAGAIN)
            res = 0;
        else {
            perror("ccnd: send");
            return;
        }
    }
    if ((face->flags & CCN_FACE_DGRAM) != 0) {
        ccnd_msg(h, "sendto short");
        return;
    }
    face->outbuf = ccn_charbuf_create();
    if (face->outbuf == NULL)
        fatal_err("ccnd: ccn_charbuf_create");
    ccn_charbuf_append(face->outbuf, data + res, size - res);
    face->outbufindex = 0;
}

static void
do_deferred_write(struct ccnd *h, int fd)
{
    /* This only happens on connected sockets */
    ssize_t res;
    struct face *face = hashtb_lookup(h->faces_by_fd, &fd, sizeof(fd));
    if (face != NULL && face->outbuf != NULL) {
        ssize_t sendlen = face->outbuf->length - face->outbufindex;
        if (sendlen > 0) {
            res = send(fd, face->outbuf->buf + face->outbufindex, sendlen, 0);
            if (res == -1) {
                perror("ccnd: send");
                shutdown_client_fd(h, fd);
                return;
            }
            if (res == sendlen) {
                face->outbufindex = 0;
                ccn_charbuf_destroy(&face->outbuf);
                return;
            }
            face->outbufindex += res;
            return;
        }
        face->outbufindex = 0;
        ccn_charbuf_destroy(&face->outbuf);
    }
    ccnd_msg(h, "ccnd:do_deferred_write: something fishy on %d", fd);
}

static void
run(struct ccnd *h)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int i;
    int res;
    int timeout_ms = -1;
    int prev_timeout_ms = -1;
    int usec;
    int specials = 1;
    for (;;) {
        usec = ccn_schedule_run(h->sched);
        timeout_ms = (usec < 0) ? -1 : usec / 1000;
        if (timeout_ms == 0 && prev_timeout_ms == 0)
            timeout_ms = 1;
        if (hashtb_n(h->faces_by_fd) + specials != h->nfds) {
            h->nfds = hashtb_n(h->faces_by_fd) + specials;
            h->fds = realloc(h->fds, h->nfds * sizeof(h->fds[0]));
            memset(h->fds, 0, h->nfds * sizeof(h->fds[0]));
        }
        h->fds[0].fd = h->local_listener_fd;
        h->fds[0].events = POLLIN;
        for (i = specials, hashtb_start(h->faces_by_fd, e);
             i < h->nfds && e->data != NULL;
             i++, hashtb_next(e)) {
            struct face *face = e->data;
            h->fds[i].fd = face->fd;
            h->fds[i].events = POLLIN;
            if (face->outbuf != NULL)
                h->fds[i].events |= POLLOUT;
        }
        hashtb_end(e);
        h->nfds = i;
        res = poll(h->fds, h->nfds, timeout_ms);
        prev_timeout_ms = ((res == 0) ? timeout_ms : 1);
        if (-1 == res) {
            perror("ccnd: poll");
            sleep(1);
            continue;
        }
        /* Check for new clients first */
        if (h->fds[0].revents != 0) {
            if (h->fds[0].revents & (POLLERR | POLLNVAL | POLLHUP))
                return;
            if (h->fds[0].revents & (POLLIN))
                accept_new_client(h);
            res--;
        }
        for (i = specials; res > 0 && i < h->nfds; i++) {
            if (h->fds[i].revents != 0) {
                res--;
                if (h->fds[i].revents & (POLLERR | POLLNVAL | POLLHUP)) {
                    if (h->fds[i].revents & (POLLIN))
                        process_input(h, h->fds[i].fd);
                    else
                        shutdown_client_fd(h, h->fds[i].fd);
                    continue;
                }
                if (h->fds[i].revents & (POLLOUT))
                    do_deferred_write(h, h->fds[i].fd);
                if (h->fds[i].revents & (POLLIN))
                    process_input(h, h->fds[i].fd);
            }
        }
    }
}

static struct ccnd *
ccnd_create(void)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct face *face;
    const char *sockname = CCN_DEFAULT_LOCAL_SOCKNAME;
    int fd;
    int res;
    struct ccnd *h;
    struct addrinfo hints = {0};
    struct addrinfo *addrinfo = NULL;
    struct addrinfo *a;
    struct hashtb_param param = { &finalize_face };
    h = calloc(1, sizeof(*h));
    param.finalize_data = h;
    h->face_limit = 10; /* soft limit */
    h->faces_by_faceid = calloc(h->face_limit, sizeof(h->faces_by_faceid[0]));
    param.finalize = &finalize_face;
    h->faces_by_fd = hashtb_create(sizeof(struct face), &param);
    h->dgram_faces = hashtb_create(sizeof(struct face), &param);
    param.finalize = &finalize_content;
    h->content_tab = hashtb_create(sizeof(struct content_entry), &param);
    param.finalize = &finalize_interest;
    h->interest_tab = hashtb_create(sizeof(struct interest_entry), &param);
    h->propagating_tab = hashtb_create(sizeof(struct propagating_entry), NULL);
    h->backlinks = ccn_matrix_create();
    h->sched = ccn_schedule_create(h);
    fd = create_local_listener(sockname, 42);
    if (fd == -1) fatal_err(sockname);
    ccnd_msg(h, "listening on %s", sockname);
    h->local_listener_fd = fd;
    hints.ai_family = PF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_flags = AI_ADDRCONFIG;
    res = getaddrinfo(NULL, "4485", &hints, &addrinfo);
    if (res == 0) {
        for (a = addrinfo; a != NULL; a = a->ai_next) {
            fd = socket(a->ai_family, SOCK_DGRAM, 0);
            if (fd != -1) {
                res = bind(fd, a->ai_addr, a->ai_addrlen);
                if (res != 0) {
                    close(fd);
                    continue;
                }
                res = fcntl(fd, F_SETFL, O_NONBLOCK);
                if (res == -1)
                    perror("fcntl");
                hashtb_start(h->faces_by_fd, e);
                if (hashtb_seek(e, &fd, sizeof(fd), 0) != HT_NEW_ENTRY)
                    exit(1);
                face = e->data;
                face->fd = fd;
                face->flags |= CCN_FACE_DGRAM;
                hashtb_end(e);
                ccnd_msg(h, "accepting datagrams on fd %d", fd);
            }
        }
        freeaddrinfo(addrinfo);
    }
    h->seed[1] = (unsigned short)getpid(); /* should gather more entropy than this */
    clean_needed(h);
    return(h);
}

int
main(int argc, char **argv)
{
    struct ccnd *h;
    signal(SIGPIPE, SIG_IGN);
    h = ccnd_create();
    ccnd_stats_httpd_start(h);
    run(h);
    ccnd_msg(h, "exiting.", (int)getpid());
    exit(0);
}
