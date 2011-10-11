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

PUBLIC void
r_link_send_content(struct ccnr_handle *h, struct fdholder *fdholder, struct content_entry *content)
{
    if ((fdholder->flags & CCNR_FACE_NOSEND) != 0) {
        // XXX - should count this.
        return;
    }
    r_store_send_content(h, fdholder, content);
    ccnr_meter_bump(h, fdholder->meter[FM_DATO], 1);
    h->content_items_sent += 1;
}

/**
 * Send a message, which may be in two pieces.
 */
PUBLIC void
r_link_stuff_and_send(struct ccnr_handle *h, struct fdholder *fdholder,
               const unsigned char *data1, size_t size1,
               const unsigned char *data2, size_t size2,
               off_t *offsetp) {
    struct ccn_charbuf *c = NULL;
    
    if (size2 != 0 || 1 > size1 + size2) {
        c = r_util_charbuf_obtain(h);
        ccn_charbuf_append(c, data1, size1);
        if (size2 != 0)
            ccn_charbuf_append(c, data2, size2);
    }
    else {
        /* avoid a copy in this case */
        r_io_send(h, fdholder, data1, size1, offsetp);
        return;
    }
    r_io_send(h, fdholder, c->buf, c->length, offsetp);
    r_util_charbuf_release(h, c);
    return;
}

PUBLIC void
r_link_do_deferred_write(struct ccnr_handle *h, int fd)
{
    /* This only happens on connected sockets */
    ssize_t res;
    struct fdholder *fdholder = r_io_fdholder_from_fd(h, fd);
    if (fdholder == NULL)
        return;
    if ((fdholder->flags & CCNR_FACE_CCND) != 0) {
        /* The direct client has something to say. */
        if (CCNSHOULDLOG(h, xxx, CCNL_FINE))
            ccnr_msg(h, "sending deferred output from direct client");
        ccn_run(h->direct_client, 0);
        if (fdholder->outbuf != NULL)
            ccnr_msg(h, "URP r_link_do_deferred_write %d", __LINE__);
        return;
    }
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
