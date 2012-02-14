/**
 * @file ccn_face_mgmt.c
 * @brief Support for parsing and creating FaceInstance elements.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/face_mgmt.h>
#include <ccn/sockcreate.h>

/**
 * Parse a ccnb-ecoded FaceInstance into an internal representation
 *
 * The space used for the various strings is held by the charbuf.
 * A client may replace the strings with other pointers, but then
 * assumes responsibilty for managing those pointers.
 * @returns pointer to newly allocated structure describing the face, or
 *          NULL if there is an error.
 */
struct ccn_face_instance *
ccn_face_instance_parse(const unsigned char *p, size_t size)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, p, size);
    struct ccn_charbuf *store = ccn_charbuf_create();
    struct ccn_face_instance *result;
    const unsigned char *val;
    size_t sz;
    int action_off = -1;
    int ccnd_id_off = -1;
    int host_off = -1;
    int port_off = -1;
    int mcast_off = -1;
    
    if (store == NULL)
        return(NULL);
    result = calloc(1, sizeof(*result));
    if (result == NULL) {
        ccn_charbuf_destroy(&store);
        return(NULL);
    }
    result->store = store;
    if (ccn_buf_match_dtag(d, CCN_DTAG_FaceInstance)) {
        ccn_buf_advance(d);
        action_off = ccn_parse_tagged_string(d, CCN_DTAG_Action, store);
        if (ccn_buf_match_dtag(d, CCN_DTAG_PublisherPublicKeyDigest)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, &val, &sz)) {
                ccn_buf_advance(d);
                if (sz != 32)
                    d->decoder.state = -__LINE__;
            }
            ccn_buf_check_close(d);
            if (d->decoder.state >= 0) {
                ccnd_id_off = store->length;
                ccn_charbuf_append(store, val, sz);
                result->ccnd_id_size = sz;
            }
        }
        result->faceid = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_FaceID);
        result->descr.ipproto = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_IPProto);
        host_off = ccn_parse_tagged_string(d, CCN_DTAG_Host, store);
        port_off = ccn_parse_tagged_string(d, CCN_DTAG_Port, store);
        mcast_off = ccn_parse_tagged_string(d, CCN_DTAG_MulticastInterface, store);
        result->descr.mcast_ttl = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_MulticastTTL);
        result->lifetime = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_FreshnessSeconds);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        ccn_face_instance_destroy(&result);
    else {
        char *b = (char *)store->buf;
        result->action = (action_off == -1) ? NULL : b + action_off;
        result->ccnd_id = (ccnd_id_off == -1) ? NULL : store->buf + ccnd_id_off;
        result->descr.address = (host_off == -1) ? NULL : b + host_off;
        result->descr.port = (port_off == -1) ? NULL : b + port_off;
        result->descr.source_address = (mcast_off == -1) ? NULL : b + mcast_off;
    }
    return(result);
}

/**
 * Destroy the result of ccn_face_instance_parse().
 */
void
ccn_face_instance_destroy(struct ccn_face_instance **pfi)
{
    if (*pfi == NULL)
        return;
    ccn_charbuf_destroy(&(*pfi)->store);
    free(*pfi);
    *pfi = NULL;
}

//<!ELEMENT FaceInstance  (Action?, PublisherPublicKeyDigest?, FaceID?, IPProto?, Host?, Port?, MulticastInterface?, MulticastTTL?, FreshnessSeconds?)>
/**
 * Marshal an internal face instance representation into ccnb form
 */
int
ccnb_append_face_instance(struct ccn_charbuf *c,
                          const struct ccn_face_instance *fi)
{
    int res;
    res = ccnb_element_begin(c, CCN_DTAG_FaceInstance);
    if (fi->action != NULL)
        res |= ccnb_tagged_putf(c, CCN_DTAG_Action, "%s",
                                fi->action);
    if (fi->ccnd_id_size != 0)
        res |= ccnb_append_tagged_blob(c, CCN_DTAG_PublisherPublicKeyDigest,
                                          fi->ccnd_id, fi->ccnd_id_size);
    if (fi->faceid != ~0)
        res |= ccnb_tagged_putf(c, CCN_DTAG_FaceID, "%u",
                                   fi->faceid);
    if (fi->descr.ipproto >= 0)
        res |= ccnb_tagged_putf(c, CCN_DTAG_IPProto, "%d",
                                   fi->descr.ipproto);
    if (fi->descr.address != NULL)
        res |= ccnb_tagged_putf(c, CCN_DTAG_Host, "%s",
                                   fi->descr.address);
    if (fi->descr.port != NULL)
        res |= ccnb_tagged_putf(c, CCN_DTAG_Port, "%s",
                                   fi->descr.port);    
    if (fi->descr.source_address != NULL)
        res |= ccnb_tagged_putf(c, CCN_DTAG_MulticastInterface, "%s",
                                   fi->descr.source_address);
    if (fi->descr.mcast_ttl >= 0 && fi->descr.mcast_ttl != 1)
        res |= ccnb_tagged_putf(c, CCN_DTAG_MulticastTTL, "%d",
                                   fi->descr.mcast_ttl);
    if (fi->lifetime >= 0)
        res |= ccnb_tagged_putf(c, CCN_DTAG_FreshnessSeconds, "%d",
                                   fi->lifetime);    
    res |= ccnb_element_end(c);
    return(res);
}
