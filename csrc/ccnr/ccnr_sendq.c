/**
 * @file ccnr_sendq.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
 
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <netinet/in.h>

#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/ccn_private.h>
#include <ccn/charbuf.h>
#include <ccn/face_mgmt.h>
#include <ccn/hashtb.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/reg_mgmt.h>
#include <ccn/uri.h>

#include "ccnr_private.h"

#include "ccnr_sendq.h"

#include "ccnr_io.h"
#include "ccnr_link.h"
#include "ccnr_msg.h"
#include "ccnr_store.h"

static int
choose_face_delay(struct ccnr_handle *h, struct fdholder *fdholder, enum cq_delay_class c)
{
    if (fdholder->flags & CCNR_FACE_CCND)
        return(1);
    if (fdholder->flags & CCNR_FACE_REPODATA)
        return(1);
    return(1);
}

static struct content_queue *
content_queue_create(struct ccnr_handle *h, struct fdholder *fdholder, enum cq_delay_class c)
{
    struct content_queue *q;
    unsigned usec;
    q = calloc(1, sizeof(*q));
    if (q != NULL) {
        usec = choose_face_delay(h, fdholder, c);
        q->burst_nsec = (usec <= 500 ? 500 : 150000); // XXX - needs a knob
        q->min_usec = usec;
        q->rand_usec = 2 * usec;
        q->nrun = 0;
        q->send_queue = ccn_indexbuf_create();
        if (q->send_queue == NULL) {
            free(q);
            return(NULL);
        }
        q->sender = NULL;
    }
    return(q);
}

PUBLIC void
r_sendq_content_queue_destroy(struct ccnr_handle *h, struct content_queue **pq)
{
    struct content_queue *q;
    if (*pq != NULL) {
        q = *pq;
        ccn_indexbuf_destroy(&q->send_queue);
        if (q->sender != NULL) {
            ccn_schedule_cancel(h->sched, q->sender);
            q->sender = NULL;
        }
        free(q);
        *pq = NULL;
    }
}

static enum cq_delay_class
choose_content_delay_class(struct ccnr_handle *h, unsigned filedesc, int content_flags)
{
    return(CCN_CQ_NORMAL); /* default */
}

static unsigned
randomize_content_delay(struct ccnr_handle *h, struct content_queue *q)
{
    unsigned usec;
    
    usec = q->min_usec + q->rand_usec;
    if (usec < 2)
        return(1);
    if (usec <= 20 || q->rand_usec < 2) // XXX - what is a good value for this?
        return(usec); /* small value, don't bother to randomize */
    usec = q->min_usec + (nrand48(h->seed) % q->rand_usec);
    if (usec < 2)
        return(1);
    return(usec);
}

static int
content_sender(struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    int i, j;
    int delay;
    int nsec;
    int burst_nsec;
    int burst_max;
    struct ccnr_handle *h = clienth;
    struct content_entry *content = NULL;
    unsigned filedesc = ev->evint;
    struct fdholder *fdholder = NULL;
    struct content_queue *q = ev->evdata;
    (void)sched;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        goto Bail;
    fdholder = r_io_fdholder_from_fd(h, filedesc);
    if (fdholder == NULL)
        goto Bail;
    if (q->send_queue == NULL)
        goto Bail;
    if ((fdholder->flags & CCNR_FACE_NOSEND) != 0)
        goto Bail;
    /* Send the content at the head of the queue */
    if (q->ready > q->send_queue->n ||
        (q->ready == 0 && q->nrun >= 12 && q->nrun < 120))
        q->ready = q->send_queue->n;
    nsec = 0;
    burst_nsec = q->burst_nsec;
    burst_max = 2;
    if (q->ready < burst_max)
        burst_max = q->ready;
    if (burst_max == 0)
        q->nrun = 0;
    for (i = 0; i < burst_max && nsec < 1000000; i++) {
        content = r_store_content_from_cookie(h, q->send_queue->buf[i]);
        if (content == NULL)
            q->nrun = 0;
        else {
            r_link_send_content(h, fdholder, content);
            /* fdholder may have vanished, bail out if it did */
            if (r_io_fdholder_from_fd(h, filedesc) == NULL)
                goto Bail;
            // nsec += burst_nsec * (unsigned)((content->size + 1023) / 1024);
            q->nrun++;
        }
    }
    if (q->ready < i) abort();
    q->ready -= i;
    /* Update queue */
    for (j = 0; i < q->send_queue->n; i++, j++)
        q->send_queue->buf[j] = q->send_queue->buf[i];
    q->send_queue->n = j;
    /* Do a poll before going on to allow others to preempt send. */
    delay = (nsec + 499) / 1000 + 1;
    if (q->ready > 0) {
        return(delay);
    }
    q->ready = j;
    if (q->nrun >= 12 && q->nrun < 120) {
        /* We seem to be a preferred provider, forgo the randomized delay */
        if (j == 0)
            delay += burst_nsec / 50;
        return(delay);
    }
    /* Determine when to run again */
    for (i = 0; i < q->send_queue->n; i++) {
        content = r_store_content_from_cookie(h, q->send_queue->buf[i]);
        if (content != NULL) {
            q->nrun = 0;
            delay = randomize_content_delay(h, q);
            if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
                ccnr_msg(h, "fdholder %u queued %u delay %i",
                         (unsigned)ev->evint, q->ready, delay);
            return(delay);
        }
    }
    q->send_queue->n = q->ready = 0;
Bail:
    q->sender = NULL;
    return(0);
}

PUBLIC int
r_sendq_face_send_queue_insert(struct ccnr_handle *h,
                       struct fdholder *fdholder, struct content_entry *content)
{
    int ans = -1;
    int delay;
    enum cq_delay_class c;
    struct content_queue *q;
    if (fdholder == NULL || content == NULL || (fdholder->flags & CCNR_FACE_NOSEND) != 0)
        return(-1);
    c = choose_content_delay_class(h, fdholder->filedesc, r_store_content_flags(content));
    if (fdholder->q[c] == NULL)
        fdholder->q[c] = content_queue_create(h, fdholder, c);
    q = fdholder->q[c];
    if (q == NULL)
        return(-1);
    ans = ccn_indexbuf_set_insert(q->send_queue, r_store_content_cookie(h, content));
    if (q->sender == NULL) {
        delay = randomize_content_delay(h, q);
        q->ready = q->send_queue->n;
        q->sender = ccn_schedule_event(h->sched, delay,
                                       content_sender, q, fdholder->filedesc);
        if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
            ccnr_msg(h, "fdholder %u q %d delay %d usec", fdholder->filedesc, c, delay);
    }
    return (ans);
}
