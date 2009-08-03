/*
 * ccn_face_mgmt.c
 *  
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
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

/**
 * Marshal an internal face instance representation into ccnb form
 */
int
ccnb_append_face_instance(struct ccn_charbuf *c,
                          const struct ccn_face_instance *fi)
{
    return -1;
}
