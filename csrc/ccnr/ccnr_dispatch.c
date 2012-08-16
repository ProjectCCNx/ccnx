/**
 * @file ccnr_dispatch.c
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

#include <sync/SyncBase.h>

#include "ccnr_private.h"

#include "ccnr_dispatch.h"

#include "ccnr_forwarding.h"
#include "ccnr_io.h"
#include "ccnr_link.h"
#include "ccnr_match.h"
#include "ccnr_msg.h"
#include "ccnr_proto.h"
#include "ccnr_sendq.h"
#include "ccnr_stats.h"
#include "ccnr_store.h"
#include "ccnr_sync.h"
#include "ccnr_util.h"

static void
process_input_message(struct ccnr_handle *h, struct fdholder *fdholder,
                      unsigned char *msg, size_t size, int pdu_ok,
                      off_t *offsetp)
{
    struct ccn_skeleton_decoder decoder = {0};
    struct ccn_skeleton_decoder *d = &decoder;
    ssize_t dres;
    enum ccn_dtag dtag;
    struct content_entry *content = NULL;
    
    if ((fdholder->flags & CCNR_FACE_UNDECIDED) != 0) {
        fdholder->flags &= ~CCNR_FACE_UNDECIDED;
        if ((fdholder->flags & CCNR_FACE_LOOPBACK) != 0)
            fdholder->flags |= CCNR_FACE_GG;
        /* YYY This is the first place that we know that an inbound stream fdholder is speaking CCNx protocol. */
        r_io_register_new_face(h, fdholder);
    }
    d->state |= CCN_DSTATE_PAUSE;
    dres = ccn_skeleton_decode(d, msg, size);
    if (d->state < 0)
        abort(); /* cannot happen because of checks in caller */
    if (CCN_GET_TT_FROM_DSTATE(d->state) != CCN_DTAG) {
        ccnr_msg(h, "discarding unknown message; size = %lu", (unsigned long)size);
        // XXX - keep a count?
        return;
    }
    dtag = d->numval;
    switch (dtag) {
//        case CCN_DTAG_Interest:
//            process_incoming_interest(h, fdholder, msg, size);
//            return;
        case CCN_DTAG_ContentObject:
            content = process_incoming_content(h, fdholder, msg, size, offsetp);
            if (content != NULL)
                r_store_commit_content(h, content);
            return;
        default:
            break;
    }
    ccnr_msg(h, "discarding unknown message; dtag=%u, size = %lu",
             (unsigned)dtag,
             (unsigned long)size);
}

/**
 * Break up data in a face's input buffer buffer into individual messages,
 * and call process_input_message on each one.
 *
 * This is used to handle things originating from the internal client -
 * its output is input for fdholder 0.
 */
static void
process_input_buffer(struct ccnr_handle *h, struct fdholder *fdholder)
{
    unsigned char *msg;
    size_t size;
    ssize_t dres;
    struct ccn_skeleton_decoder *d;

    if (fdholder == NULL || fdholder->inbuf == NULL)
        return;
    d = &fdholder->decoder;
    msg = fdholder->inbuf->buf;
    size = fdholder->inbuf->length;
    while (d->index < size) {
        dres = ccn_skeleton_decode(d, msg + d->index, size - d->index);
        if (d->state != 0)
            break;
        process_input_message(h, fdholder, msg + d->index - dres, dres, 0, NULL);
    }
    if (d->index != size) {
        ccnr_msg(h, "protocol error on fdholder %u (state %d), discarding %d bytes",
                     fdholder->filedesc, d->state, (int)(size - d->index));
        // XXX - perhaps this should be a fatal error.
    }
    fdholder->inbuf->length = 0;
    memset(d, 0, sizeof(*d));
}

/**
 * Process the input from a socket or file.
 *
 * The fd has been found ready for input by the poll call.
 * Decide what fdholder it corresponds to, and after checking for exceptional
 * cases, receive data, parse it into ccnb-encoded messages, and call
 * process_input_message for each one.
 */
PUBLIC void
r_dispatch_process_input(struct ccnr_handle *h, int fd)
{
    struct fdholder *fdholder = NULL;
    struct fdholder *source = NULL;
    ssize_t res;
    ssize_t dres;
    ssize_t msgstart;
    unsigned char *buf;
    struct ccn_skeleton_decoder *d;
    struct sockaddr_storage sstor;
    socklen_t addrlen = sizeof(sstor);
    struct sockaddr *addr = (struct sockaddr *)&sstor;
    
    fdholder = r_io_fdholder_from_fd(h, fd);
    if (fdholder == NULL)
        return;
    if ((fdholder->flags & (CCNR_FACE_DGRAM | CCNR_FACE_PASSIVE)) == CCNR_FACE_PASSIVE) {
        r_io_accept_connection(h, fd);
        return;
    }
    if ((fdholder->flags & CCNR_FACE_CCND) != 0) {
        res = ccn_run(h->direct_client, 0);
        if (res < 0) {
            // Deal with it somehow.  Probably means ccnd went away.
            // Should schedule reconnection.
            ccnr_msg(h, "ccn_run returned error, shutting down direct client");
            r_io_shutdown_client_fd(h, fd);
        }
        return;
    }
    d = &fdholder->decoder;
    if (fdholder->inbuf == NULL) {
        fdholder->inbuf = ccn_charbuf_create();
        fdholder->bufoffset = 0;
    }
    if (fdholder->inbuf->length == 0)
        memset(d, 0, sizeof(*d));
    buf = ccn_charbuf_reserve(fdholder->inbuf, 8800);
    memset(&sstor, 0, sizeof(sstor));
    if ((fdholder->flags & CCNR_FACE_SOCKMASK) != 0) {
        res = recvfrom(fdholder->filedesc, buf, fdholder->inbuf->limit - fdholder->inbuf->length,
            /* flags */ 0, addr, &addrlen);
    }
    else {
        res = read(fdholder->filedesc, buf, fdholder->inbuf->limit - fdholder->inbuf->length);
    }
    if (res == -1)
        ccnr_msg(h, "read %u :%s (errno = %d)",
                    fdholder->filedesc, strerror(errno), errno);
    else if (res == 0 && (fdholder->flags & CCNR_FACE_DGRAM) == 0) {
        if (fd == h->active_in_fd && h->stable == 0) {
            h->stable = lseek(fd, 0, SEEK_END);
            ccnr_msg(h, "read %ju bytes", (uintmax_t)h->stable);
        }
        r_io_shutdown_client_fd(h, fd);
    }
    else {
        off_t offset = (off_t)-1;
        off_t *offsetp = NULL;
        if ((fdholder->flags & CCNR_FACE_REPODATA) != 0)
            offsetp = &offset;
        source = fdholder;
        ccnr_meter_bump(h, source->meter[FM_BYTI], res);
        source->recvcount++;
        fdholder->inbuf->length += res;
        msgstart = 0;
        if (((fdholder->flags & CCNR_FACE_UNDECIDED) != 0 &&
             fdholder->inbuf->length >= 6 &&
             0 == memcmp(fdholder->inbuf->buf, "GET ", 4))) {
            ccnr_stats_handle_http_connection(h, fdholder);
            return;
        }
        dres = ccn_skeleton_decode(d, buf, res);
        while (d->state == 0) {
            if (offsetp != NULL)
                *offsetp = fdholder->bufoffset + msgstart;
            process_input_message(h, source,
                                  fdholder->inbuf->buf + msgstart,
                                  d->index - msgstart,
                                  (fdholder->flags & CCNR_FACE_LOCAL) != 0,
                                  offsetp);
            msgstart = d->index;
            if (msgstart == fdholder->inbuf->length) {
                fdholder->inbuf->length = 0;
                fdholder->bufoffset += msgstart;
                return;
            }
            dres = ccn_skeleton_decode(d,
                    fdholder->inbuf->buf + msgstart,
                    fdholder->inbuf->length - msgstart);
        }
        fdholder->bufoffset += msgstart;
        if ((fdholder->flags & CCNR_FACE_DGRAM) != 0) {
            ccnr_msg(h, "protocol error on fdholder %u, discarding %u bytes",
                source->filedesc,
                (unsigned)(fdholder->inbuf->length - msgstart));
            fdholder->inbuf->length = 0;
            /* XXX - should probably ignore this source for a while */
            return;
        }
        else if (d->state < 0) {
            ccnr_msg(h, "protocol error on fdholder %u", source->filedesc);
            r_io_shutdown_client_fd(h, fd);
            return;
        }
        if (msgstart < fdholder->inbuf->length && msgstart > 0) {
            /* move partial message to start of buffer */
            memmove(fdholder->inbuf->buf, fdholder->inbuf->buf + msgstart,
                fdholder->inbuf->length - msgstart);
            fdholder->inbuf->length -= msgstart;
            d->index -= msgstart;
        }
    }
}

PUBLIC void
r_dispatch_process_internal_client_buffer(struct ccnr_handle *h)
{
    struct fdholder *fdholder = h->face0;
    if (fdholder == NULL)
        return;
    fdholder->inbuf = ccn_grab_buffered_output(h->internal_client);
    if (fdholder->inbuf == NULL)
        return;
    ccnr_meter_bump(h, fdholder->meter[FM_BYTI], fdholder->inbuf->length);
    process_input_buffer(h, fdholder);
    ccn_charbuf_destroy(&(fdholder->inbuf));
}
/**
 * Run the main loop of the ccnr
 */
PUBLIC void
r_dispatch_run(struct ccnr_handle *h)
{
    int i;
    int res;
    int timeout_ms = -1;
    int prev_timeout_ms = -1;
    int usec;
    int usec_direct;
    
    if (h->running < 0) {
        ccnr_msg(h, "Fatal error during initialization");
        return;
    }
    for (h->running = 1; h->running;) {
        r_dispatch_process_internal_client_buffer(h);
        usec = ccn_schedule_run(h->sched);
        usec_direct = ccn_process_scheduled_operations(h->direct_client);
        if (usec_direct < usec)
            usec = usec_direct;
        if (1) {
            /* If so requested, shut down when ccnd goes away. */
            if (ccn_get_connection_fd(h->direct_client) == -1) {
                /* XXX - since we cannot reasonably recover, always go away. */
                ccnr_msg(h, "lost connection to ccnd");
                h->running = 0;
                break;
            }
        }
        timeout_ms = (usec < 0) ? -1 : ((usec + 960) / 1000);
        if (timeout_ms == 0 && prev_timeout_ms == 0)
            timeout_ms = 1;
        r_dispatch_process_internal_client_buffer(h);
        r_store_trim(h, h->cob_limit);
        r_io_prepare_poll_fds(h);
        res = poll(h->fds, h->nfds, timeout_ms);
        prev_timeout_ms = ((res == 0) ? timeout_ms : 1);
        if (-1 == res) {
            if (errno == EINTR)
                continue;
            ccnr_msg(h, "poll: %s (errno = %d)", strerror(errno), errno);
            sleep(1);
            continue;
        }
        for (i = 0; res > 0 && i < h->nfds; i++) {
            if (h->fds[i].revents != 0) {
                res--;
                if (h->fds[i].revents & (POLLERR | POLLNVAL | POLLHUP)) {
                    if (h->fds[i].revents & (POLLIN))
                        r_dispatch_process_input(h, h->fds[i].fd);
                    else
                        r_io_shutdown_client_fd(h, h->fds[i].fd);
                    continue;
                }
                if (h->fds[i].revents & (POLLOUT))
                    r_link_do_deferred_write(h, h->fds[i].fd);
                else if (h->fds[i].revents & (POLLIN))
                    r_dispatch_process_input(h, h->fds[i].fd);
                else
                    ccnr_msg(h, "poll: UNHANDLED");
            }
        }
    }
}
