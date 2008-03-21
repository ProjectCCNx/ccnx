#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/charbuf.h>
#include <ccn/coding.h>

#define ARRAY_N(arr) (sizeof(arr)/sizeof(arr[0]))
static const struct ccn_dict_entry ccn_tagdict[] = {
    {CCN_DTAG_Certificate, "Certificate"},
    {CCN_DTAG_Collection, "Collection"},
    {CCN_DTAG_CompleteName, "CompleteName"},
    {CCN_DTAG_Component, "Component"},
    {CCN_DTAG_Content, "Content"},
    {CCN_DTAG_ContentAuthenticator, "ContentAuthenticator"},
    {CCN_DTAG_ContentDigest, "ContentDigest"},
    {CCN_DTAG_ContentHash, "ContentHash"},
    {CCN_DTAG_ContentObject, "ContentObject"},
    {CCN_DTAG_Count, "Count"},
    {CCN_DTAG_Header, "Header"},
    {CCN_DTAG_Interest, "Interest"},
    {CCN_DTAG_Key, "Key"},
    {CCN_DTAG_KeyLocator, "KeyLocator"},
    {CCN_DTAG_KeyName, "KeyName"},
    {CCN_DTAG_Length, "Length"},
    {CCN_DTAG_Link, "Link"},
    {CCN_DTAG_LinkAuthenticator, "LinkAuthenticator"},
    {CCN_DTAG_Name, "Name"},
    {CCN_DTAG_NameComponentCount, "NameComponentCount"},
    {CCN_DTAG_PublisherID, "PublisherID"},
    {CCN_DTAG_PublisherKeyID, "PublisherKeyID"},
    {CCN_DTAG_RootDigest, "RootDigest"},
    {CCN_DTAG_Signature, "Signature"},
    {CCN_DTAG_Start, "Start"},
    {CCN_DTAG_Timestamp, "Timestamp"},
    {CCN_DTAG_Type, "Type"},
};

const struct ccn_dict ccn_dtag_dict = {ARRAY_N(ccn_tagdict), ccn_tagdict};

enum ccn_decoder_state {
    CCN_DSTATE_0 = 0,
    CCN_DSTATE_1,
    CCN_DSTATE_2,
    CCN_DSTATE_3,
    CCN_DSTATE_4,
    CCN_DSTATE_5,
    CCN_DSTATE_6,
    CCN_DSTATE_7,
    CCN_DSTATE_8,
    CCN_DSTATE_10
};

/*
 * This macro documents what's happening in the state machine by
 * hinting what XML syntax would be emitted in a re-encoder.
 * But it actually does nothing.
 */
#define XML(goop) ((void)0)

ssize_t
ccn_skeleton_decode(struct ccn_skeleton_decoder *d, unsigned char p[], size_t n)
{
    enum ccn_decoder_state state = d->state;
    int tagstate = 0;
    uintmax_t numval = d->numval;
    ssize_t i = 0;
    unsigned char c;
    size_t chunk;
    while (i < n) {
        switch (state) {
            case CCN_DSTATE_0: /* start new thing */
                if (tagstate > 1 && tagstate-- == 2) {
                    XML("\""); /* close off the attribute value */
                } 
                if (p[i] == CCN_CLOSE) {
                    i++;
                    if (d->nest <= 0 || tagstate > 1) {
                        state = -__LINE__;
                        break;
                    }
                    if (tagstate == 1) {
                        tagstate = 0;
                        XML("/>");
                    }
                    else {
                        XML("</%s>");
                    }
                    d->nest -= 1;
                    if (d->nest == 0)
                        n = i;
                    break;
                }
                numval = 0;
                state = 1;
                /* FALLTHRU */
            case CCN_DSTATE_1: /* parsing numval */
                c = p[i++];
                if (c != (c & 127)) {
                    if (numval > (SIZE_T_MAX >> (7 + CCN_TT_BITS)))
                        state = -__LINE__;
                    numval = (numval << 7) + (c & 127);
                }
                else {
                    numval = (numval << (7-CCN_TT_BITS)) + (c >> CCN_TT_BITS);
                    c &= CCN_TT_MASK;
                    switch (c) {
                        case CCN_EXT:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(">");
                            }
                            d->nest += 1;
                            state = 0;
                            break;
                        case CCN_DTAG:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(">");
                            }
                            d->nest += 1;
                            XML("<%s");
                            tagstate = 1;
                            state = 0;
                            break;
                        case CCN_BLOB:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(" ccnbencoding=\"base64Binary\">");
                            }
                            state = CCN_DSTATE_10;
                            if (numval == 0)
                                state = 0;
                            break;
                        case CCN_UDATA:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(">");
                            }
                            state = CCN_DSTATE_3;
                            if (numval == 0)
                                state = 0;
                            break;
                        case CCN_DATTR:
                            if (tagstate != 1) {
                                state = -__LINE__;
                                break;
                            }
                            tagstate = 3;
                            state = 0;
                            break;
                        case CCN_ATTR:
                            if (tagstate != 1) {
                                state = -__LINE__;
                                break;
                            }
                            numval += 1; /* encoded as length-1 */
                            state = CCN_DSTATE_5;
                            break;
                        case CCN_TAG:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(">");
                            }
                            numval += 1; /* encoded as length-1 */
                            d->nest += 1;
                            state = CCN_DSTATE_4;
                            break;
                        default:
                            state = -__LINE__;
                    }
                }
                break;
            case CCN_DSTATE_4: /* parsing tag name */
                chunk = n - i;
                if (chunk > numval)
                    chunk = numval;
                if (chunk == 0) {
                    state = -__LINE__;
                    break;
                }
                numval -= chunk;
                i += chunk;
                if (numval == 0) {
                    if (d->nest == 0) {
                        state = -__LINE__;
                        break;
                    }
                    XML("<%s");
                    tagstate = 1;
                    state = CCN_DSTATE_0;
                }
                break;                
            case CCN_DSTATE_5: /* parsing attribute name */
                chunk = n - i;
                if (chunk > numval)
                    chunk = numval;
                if (chunk == 0) {
                    state = -__LINE__;
                    break;
                }
                numval -= chunk;
                i += chunk;
                if (numval == 0) {
                    if (d->nest == 0) {
                        state = -__LINE__;
                        break;
                    }
                    XML(" %s=\"");
                    tagstate = 3;
                    state = CCN_DSTATE_0;
                }
                break;
            case CCN_DSTATE_3: /* utf-8 data */
            case CCN_DSTATE_6: /* processing instructions, etc. */
            case CCN_DSTATE_10: /* BLOB */
                chunk = n - i;
                if (chunk > numval)
                    chunk = numval;
                if (chunk == 0) {
                    state = -__LINE__;
                    break;
                }
                numval -= chunk;
                i += chunk;
                if (numval == 0)
                    state = CCN_DSTATE_0;
                break;
            default:
                n = i;
        }
    }
    d->state = state;
    d->tagstate = tagstate;
    d->numval = numval;
    d->index += i;
    return(i);
}
