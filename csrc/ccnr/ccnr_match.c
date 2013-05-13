/**
 * @file ccnr_match.c
 * 
 * Part of ccnr - CCNx Repository Daemon.
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
#include <ccn/btree_content.h>
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

#include "ccnr_match.h"

#include "ccnr_forwarding.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"
#include "ccnr_sendq.h"
#include "ccnr_store.h"

PUBLIC void
r_match_consume_interest(struct ccnr_handle *h, struct propagating_entry *pe)
{
    struct fdholder *fdholder = NULL;
    ccn_indexbuf_destroy(&pe->outbound);
    if (pe->interest_msg != NULL) {
        free(pe->interest_msg);
        pe->interest_msg = NULL;
        fdholder = r_io_fdholder_from_fd(h, pe->filedesc);
        if (fdholder != NULL)
            fdholder->pending_interests -= 1;
    }
    if (pe->next != NULL) {
        pe->next->prev = pe->prev;
        pe->prev->next = pe->next;
        pe->next = pe->prev = NULL;
    }
    pe->usec = 0;
}

/**
 * Consume matching interests
 * given a nameprefix_entry and a piece of content.
 *
 * If fdholder is not NULL, pay attention only to interests from that fdholder.
 * It is allowed to pass NULL for pc, but if you have a (valid) one it
 * will avoid a re-parse.
 * @returns number of matches found.
 */
PUBLIC int
r_match_consume_matching_interests(struct ccnr_handle *h,
                           struct nameprefix_entry *npe,
                           struct content_entry *content,
                           struct ccn_parsed_ContentObject *pc,
                           struct fdholder *fdholder)
{
    int matches = 0;
    struct propagating_entry *head;
    struct propagating_entry *next;
    struct propagating_entry *p;
    const unsigned char *content_msg;
    size_t content_size;
    struct fdholder *f;
    
    head = &npe->pe_head;
    // XXX - i do not think this is called in practice
    content_msg = r_store_content_base(h, content);
    content_size = r_store_content_size(h, content);
    f = fdholder;
    for (p = head->next; p != head; p = next) {
        next = p->next;
        if (p->interest_msg != NULL &&
            ((fdholder == NULL && (f = r_io_fdholder_from_fd(h, p->filedesc)) != NULL) ||
             (fdholder != NULL && p->filedesc == fdholder->filedesc))) {
            if (ccn_content_matches_interest(content_msg, content_size, 1, pc,
                                             p->interest_msg, p->size, NULL)) {
                r_sendq_face_send_queue_insert(h, f, content);
                if (CCNSHOULDLOG(h, (32 | 8), CCNL_FINE))
                    ccnr_debug_ccnb(h, __LINE__, "consume", f,
                                    p->interest_msg, p->size);
                matches += 1;
                r_match_consume_interest(h, p);
            }
        }
    }
    return(matches);
}

/**
 * Find and consume interests that match given content.
 *
 * Schedules the sending of the content.
 * If fdholder is not NULL, pay attention only to interests from that fdholder.
 * It is allowed to pass NULL for pc, but if you have a (valid) one it
 * will avoid a re-parse.
 * For new content, from_face is the source; for old content, from_face is NULL.
 * @returns number of matches, or -1 if the new content should be dropped.
 */
PUBLIC int
r_match_match_interests(struct ccnr_handle *h, struct content_entry *content,
                           struct ccn_parsed_ContentObject *pc,
                           struct fdholder *fdholder, struct fdholder *from_face)
{
    int n_matched = 0;
    int new_matches;
    struct nameprefix_entry *npe = NULL;
    int ci = 0;
//    int cm = 0;
    struct ccn_charbuf *name = NULL;
    struct ccn_indexbuf *namecomps = NULL;
    unsigned c0 = 0;
    
    name = ccn_charbuf_create();
    ccn_name_init(name);
    r_store_name_append_components(name, h, content, 0, -1);
    namecomps = ccn_indexbuf_create();
    ccn_name_split(name, namecomps);
    c0 = namecomps->buf[0];
    
    for (ci = namecomps->n - 1; ci >= 0; ci--) {
        int size = namecomps->buf[ci] - c0;
        npe = hashtb_lookup(h->nameprefix_tab, name->buf + c0, size);
        if (npe != NULL)
            break;
    }
    
    ccn_charbuf_destroy(&name);
    ccn_indexbuf_destroy(&namecomps);
    for (; npe != NULL; npe = npe->parent, ci--) {
//        if (npe->fgen != h->forward_to_gen)
//            r_fwd_update_forward_to(h, npe);
        if (from_face != NULL && (npe->flags & CCN_FORW_LOCAL) != 0 &&
            (from_face->flags & CCNR_FACE_GG) == 0)
            return(-1);
        new_matches = r_match_consume_matching_interests(h, npe, content, pc, fdholder);
//        if (from_face != NULL && (new_matches != 0 || ci + 1 == cm))
//            note_content_from(h, npe, from_face->filedesc, ci);
        if (new_matches != 0) {
//            cm = ci; /* update stats for this prefix and one shorter */
            n_matched += new_matches;
        }
    }
    return(n_matched);
}
