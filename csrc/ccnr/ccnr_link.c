/**
 * @file ccnr_link.c
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

#include "ccnr_link.h"

#include "ccnr_forwarding.h"
#include "ccnr_internal_client.h"
#include "ccnr_io.h"
#include "ccnr_link.h"
#include "ccnr_match.h"
#include "ccnr_msg.h"
#include "ccnr_sendq.h"
#include "ccnr_stats.h"
#include "ccnr_store.h"
#include "ccnr_util.h"

static int
ccn_stuff_interest(struct ccnr_handle * h,
				   struct fdholder * fdholder, struct ccn_charbuf * c);
static void
ccn_append_link_stuff(struct ccnr_handle * h,
					  struct fdholder * fdholder,
					  struct ccn_charbuf * c);


PUBLIC void
r_link_send_content(struct ccnr_handle *h, struct fdholder *fdholder, struct content_entry *content)
{
    int n, a, b, size;
    if ((fdholder->flags & CCNR_FACE_NOSEND) != 0) {
        // XXX - should count this.
        return;
    }
    size = content->size;
    if (SHOULDLOG(h, 4))
        ccnr_debug_ccnb(h, __LINE__, "content_to", fdholder,
                        content->key, size);
    /* Excise the message-digest name component */
    n = content->ncomps;
    if (n < 2) abort();
    a = content->comps[n - 2];
    b = content->comps[n - 1];
    if (b - a != 36)
        abort(); /* strange digest length */
    r_link_stuff_and_send(h, fdholder, content->key, a, content->key + b, size - b);
    ccnr_meter_bump(h, fdholder->meter[FM_DATO], 1);
    h->content_items_sent += 1;
}

/**
 * Send a message in a PDU, possibly stuffing other interest messages into it.
 * The message may be in two pieces.
 */
PUBLIC void
r_link_stuff_and_send(struct ccnr_handle *h, struct fdholder *fdholder,
               const unsigned char *data1, size_t size1,
               const unsigned char *data2, size_t size2) {
    struct ccn_charbuf *c = NULL;
    
    if ((fdholder->flags & CCNR_FACE_LINK) != 0) {
        c = r_util_charbuf_obtain(h);
        ccn_charbuf_reserve(c, size1 + size2 + 5 + 8);
        ccn_charbuf_append_tt(c, CCN_DTAG_CCNProtocolDataUnit, CCN_DTAG);
        ccn_charbuf_append(c, data1, size1);
        if (size2 != 0)
            ccn_charbuf_append(c, data2, size2);
        ccn_stuff_interest(h, fdholder, c);
        ccn_append_link_stuff(h, fdholder, c);
        ccn_charbuf_append_closer(c);
    }
    else if (size2 != 0 || 1 > size1 + size2 ||
             (fdholder->flags & (CCNR_FACE_SEQOK | CCNR_FACE_SEQPROBE)) != 0) {
        c = r_util_charbuf_obtain(h);
        ccn_charbuf_append(c, data1, size1);
        if (size2 != 0)
            ccn_charbuf_append(c, data2, size2);
        ccn_stuff_interest(h, fdholder, c);
        ccn_append_link_stuff(h, fdholder, c);
    }
    else {
        /* avoid a copy in this case */
        r_io_send(h, fdholder, data1, size1);
        return;
    }
    r_io_send(h, fdholder, c->buf, c->length);
    r_util_charbuf_release(h, c);
    return;
}

/**
 * Stuff a PDU with interest messages that will fit.
 *
 * Note by default stuffing does not happen due to the setting of h->mtu.
 * @returns the number of messages that were stuffed.
 */
static int
ccn_stuff_interest(struct ccnr_handle *h,
                   struct fdholder *fdholder, struct ccn_charbuf *c)
{
    int n_stuffed = 0;
    return(n_stuffed);
}

PUBLIC void
r_link_ccn_link_state_init(struct ccnr_handle *h, struct fdholder *fdholder)
{
    int checkflags;
    int matchflags;
    
    matchflags = CCNR_FACE_DGRAM;
    checkflags = matchflags | CCNR_FACE_MCAST | CCNR_FACE_GG | CCNR_FACE_SEQOK |                  CCNR_FACE_PASSIVE;
    if ((fdholder->flags & checkflags) != matchflags)
        return;
    /* Send one sequence number to see if the other side wants to play. */
    fdholder->pktseq = nrand48(h->seed);
    fdholder->flags |= CCNR_FACE_SEQPROBE;
}

static void
ccn_append_link_stuff(struct ccnr_handle *h,
                      struct fdholder *fdholder,
                      struct ccn_charbuf *c)
{
    if ((fdholder->flags & (CCNR_FACE_SEQOK | CCNR_FACE_SEQPROBE)) == 0)
        return;
    ccn_charbuf_append_tt(c, CCN_DTAG_SequenceNumber, CCN_DTAG);
    ccn_charbuf_append_tt(c, 2, CCN_BLOB);
    ccn_charbuf_append_value(c, fdholder->pktseq, 2);
    ccnb_element_end(c);
    if (0)
        ccnr_msg(h, "debug.%d pkt_to %u seq %u",
                 __LINE__, fdholder->filedesc, (unsigned)fdholder->pktseq);
    fdholder->pktseq++;
    fdholder->flags &= ~CCNR_FACE_SEQPROBE;
}

PUBLIC int
r_link_process_incoming_link_message(struct ccnr_handle *h,
                              struct fdholder *fdholder, enum ccn_dtag dtag,
                              unsigned char *msg, size_t size)
{
    uintmax_t s;
    int checkflags;
    int matchflags;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, msg, size);

    switch (dtag) {
        case CCN_DTAG_SequenceNumber:
            s = ccn_parse_required_tagged_binary_number(d, dtag, 1, 6);
            if (d->decoder.state < 0)
                return(d->decoder.state);
            /*
             * If the other side is unicast and sends sequence numbers,
             * then it is OK for us to send numbers as well.
             */
            matchflags = CCNR_FACE_DGRAM;
            checkflags = matchflags | CCNR_FACE_MCAST | CCNR_FACE_SEQOK;
            if ((fdholder->flags & checkflags) == matchflags)
                fdholder->flags |= CCNR_FACE_SEQOK;
            if (fdholder->rrun == 0) {
                fdholder->rseq = s;
                fdholder->rrun = 1;
                return(0);
            }
            if (s == fdholder->rseq + 1) {
                fdholder->rseq = s;
                if (fdholder->rrun < 255)
                    fdholder->rrun++;
                return(0);
            }
            if (s > fdholder->rseq && s - fdholder->rseq < 255) {
                ccnr_msg(h, "seq_gap %u %ju to %ju",
                         fdholder->filedesc, fdholder->rseq, s);
                fdholder->rseq = s;
                fdholder->rrun = 1;
                return(0);
            }
            if (s <= fdholder->rseq) {
                if (fdholder->rseq - s < fdholder->rrun) {
                    ccnr_msg(h, "seq_dup %u %ju", fdholder->filedesc, s);
                    return(0);
                }
                if (fdholder->rseq - s < 255) {
                    /* Received out of order */
                    ccnr_msg(h, "seq_ooo %u %ju", fdholder->filedesc, s);
                    if (s == fdholder->rseq - fdholder->rrun) {
                        fdholder->rrun++;
                        return(0);
                    }
                }
            }
            fdholder->rseq = s;
            fdholder->rrun = 1;
            break;
        default:
            return(-1);
    }
    return(0);
}
PUBLIC void
r_link_do_deferred_write(struct ccnr_handle *h, int fd)
{
    /* This only happens on connected sockets */
    ssize_t res;
    struct fdholder *fdholder = r_io_fdholder_from_fd(h, fd);
    if (fdholder == NULL)
        return;
    if (fdholder->outbuf != NULL) {
        ssize_t sendlen = fdholder->outbuf->length - fdholder->outbufindex;
        if (sendlen > 0) {
            res = send(fd, fdholder->outbuf->buf + fdholder->outbufindex, sendlen, 0);
            if (res == -1) {
                if (errno == EPIPE) {
                    fdholder->flags |= CCNR_FACE_NOSEND;
                    fdholder->outbufindex = 0;
                    ccn_charbuf_destroy(&fdholder->outbuf);
                    return;
                }
                ccnr_msg(h, "send: %s (errno = %d)", strerror(errno), errno);
                r_io_shutdown_client_fd(h, fd);
                return;
            }
            if (res == sendlen) {
                fdholder->outbufindex = 0;
                ccn_charbuf_destroy(&fdholder->outbuf);
                if ((fdholder->flags & CCNR_FACE_CLOSING) != 0)
                    r_io_shutdown_client_fd(h, fd);
                return;
            }
            fdholder->outbufindex += res;
            return;
        }
        fdholder->outbufindex = 0;
        ccn_charbuf_destroy(&fdholder->outbuf);
    }
    if ((fdholder->flags & CCNR_FACE_CLOSING) != 0)
        r_io_shutdown_client_fd(h, fd);
    else if ((fdholder->flags & CCNR_FACE_CONNECTING) != 0) {
        fdholder->flags &= ~CCNR_FACE_CONNECTING;
        ccnr_face_status_change(h, fdholder->filedesc);
    }
    else
        ccnr_msg(h, "ccnr:r_link_do_deferred_write: something fishy on %d", fd);
}
