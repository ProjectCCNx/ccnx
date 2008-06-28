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

/*
 * This macro documents what's happening in the state machine by
 * hinting what XML syntax would be emitted in a re-encoder.
 * But it actually does nothing.
 */
#define XML(goop) ((void)0)

ssize_t
ccn_skeleton_decode(struct ccn_skeleton_decoder *d, const unsigned char *p, size_t n)
{
    enum ccn_decoder_state state = d->state;
    int tagstate = 0;
    size_t numval = d->numval;
    ssize_t i = 0;
    unsigned char c;
    size_t chunk;
    int pause = 0;
    if (d->state >= 0) {
        pause = d->state & CCN_DSTATE_PAUSE;
        tagstate = (d->state >> 8) & 3;
        state = d->state & 0xFF;
    }
    while (i < n) {
        switch (state) {
            case CCN_DSTATE_INITIAL:
            case CCN_DSTATE_NEWTOKEN: /* start new thing */
                d->token_index = i + d->index;
                if (tagstate > 1 && tagstate-- == 2) {
                    XML("\""); /* close off the attribute value */
                } 
                if (p[i] == CCN_CLOSE) {
                    i++;
                    if (d->nest <= 0 || tagstate > 1) {
                        state = CCN_DSTATE_ERR_NEST;
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
                    if (d->nest == 0) {
                        state = CCN_DSTATE_INITIAL;
                        n = i;
                    }
                    if (pause) {
                        state |= (((int)CCN_NO_TOKEN) << 16);
                        n = i;
                    }
                    break;
                }
                numval = 0;
                state = CCN_DSTATE_NUMVAL;
                /* FALLTHRU */
            case CCN_DSTATE_NUMVAL: /* parsing numval */
                c = p[i++];
                if ((c & CCN_TT_HBIT) == CCN_CLOSE) {
                    if (numval > ((~(size_t)0U) >> (7 + CCN_TT_BITS)))
                        state = CCN_DSTATE_ERR_OVERFLOW;
                    numval = (numval << 7) + (c & 127);
                }
                else {
                    numval = (numval << (7-CCN_TT_BITS)) +
                             ((c >> CCN_TT_BITS) & CCN_MAX_TINY);
                    c &= CCN_TT_MASK;
                    switch (c) {
                        case CCN_EXT:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(">");
                            }
                            d->nest += 1;
                            d->element_index = d->token_index;
                            state = CCN_DSTATE_NEWTOKEN;
                            break;
                        case CCN_DTAG:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(">");
                            }
                            d->nest += 1;
                            d->element_index = d->token_index;
                            XML("<%s");
                            tagstate = 1;
                            state = CCN_DSTATE_NEWTOKEN;
                            break;
                        case CCN_BLOB:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(" ccnbencoding=\"base64Binary\">");
                            }
                            state = CCN_DSTATE_BLOB;
                            if (numval == 0)
                                state = CCN_DSTATE_NEWTOKEN;
                            break;
                        case CCN_UDATA:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(">");
                            }
                            state = CCN_DSTATE_UDATA;
                            if (numval == 0)
                                state = CCN_DSTATE_NEWTOKEN;
                            break;
                        case CCN_DATTR:
                            if (tagstate != 1) {
                                state = CCN_DSTATE_ERR_ATTR;
                                break;
                            }
                            tagstate = 3;
                            state = CCN_DSTATE_NEWTOKEN;
                            break;
                        case CCN_ATTR:
                            if (tagstate != 1) {
                                state = CCN_DSTATE_ERR_ATTR;
                                break;
                            }
                            numval += 1; /* encoded as length-1 */
                            state = CCN_DSTATE_ATTRNAME;
                            break;
                        case CCN_TAG:
                            if (tagstate == 1) {
                                tagstate = 0;
                                XML(">");
                            }
                            numval += 1; /* encoded as length-1 */
                            d->nest += 1;
                            d->element_index = d->token_index;
                            state = CCN_DSTATE_TAGNAME;
                            break;
                        default:
                            state = CCN_DSTATE_ERR_CODING;
                    }
                    if (pause) {
                        state |= (c << 16);
                        n = i;
                    }
                }
                break;
            case CCN_DSTATE_TAGNAME: /* parsing tag name */
                chunk = n - i;
                if (chunk > numval)
                    chunk = numval;
                if (chunk == 0) {
                    state = CCN_DSTATE_ERR_BUG;
                    break;
                }
                numval -= chunk;
                i += chunk;
                if (numval == 0) {
                    if (d->nest == 0) {
                        state = CCN_DSTATE_ERR_NEST;
                        break;
                    }
                    XML("<%s");
                    tagstate = 1;
                    state = CCN_DSTATE_NEWTOKEN;
                }
                break;                
            case CCN_DSTATE_ATTRNAME: /* parsing attribute name */
                chunk = n - i;
                if (chunk > numval)
                    chunk = numval;
                if (chunk == 0) {
                    state = CCN_DSTATE_ERR_BUG;
                    break;
                }
                numval -= chunk;
                i += chunk;
                if (numval == 0) {
                    if (d->nest == 0) {
                        state = CCN_DSTATE_ERR_ATTR;
                        break;
                    }
                    XML(" %s=\"");
                    tagstate = 3;
                    state = CCN_DSTATE_NEWTOKEN;
                }
                break;
            case CCN_DSTATE_UDATA: /* utf-8 data */
            case CCN_DSTATE_BLOB: /* BLOB */
                chunk = n - i;
                if (chunk > numval)
                    chunk = numval;
                if (chunk == 0) {
                    state = CCN_DSTATE_ERR_BUG;
                    break;
                }
                numval -= chunk;
                i += chunk;
                if (numval == 0)
                    state = CCN_DSTATE_NEWTOKEN;
                break;
            default:
                n = i;
        }
    }
    if (state < 0)
        tagstate = pause = 0;
    d->state = state | pause | (tagstate << 8); 
    d->numval = numval;
    d->index += i;
    return(i);
}

#define ARRAY_N(arr) (sizeof(arr)/sizeof(arr[0]))
static const struct ccn_dict_entry ccn_tagdict[] = {
    {CCN_DTAG_Name, "Name"},
    {CCN_DTAG_Component, "Component"},
    {CCN_DTAG_Certificate, "Certificate"},
    {CCN_DTAG_Collection, "Collection"},
    {CCN_DTAG_CompleteName, "CompleteName"},
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
    {CCN_DTAG_NameComponentCount, "NameComponentCount"},
    {CCN_DTAG_PublisherID, "PublisherID"},
    {CCN_DTAG_PublisherKeyID, "PublisherKeyID"},
    {CCN_DTAG_RootDigest, "RootDigest"},
    {CCN_DTAG_Signature, "Signature"},
    {CCN_DTAG_Start, "Start"},
    {CCN_DTAG_Timestamp, "Timestamp"},
    {CCN_DTAG_Type, "Type"},
    {CCN_DTAG_Nonce, "Nonce"},
    {CCN_DTAG_Scope, "Scope"},
    {CCN_DTAG_MatchFirstAvailableDescendant, "MatchFirstAvailableDescendant"},
    {CCN_DTAG_MatchLastAvailableDescendant, "MatchLastAvailableDescendant"},
    {CCN_DTAG_MatchNextAvailableSibling, "MatchNextAvailableSibling"},
    {CCN_DTAG_MatchLastAvailableSibling, "MatchLastAvailableSibling"},
    {CCN_DTAG_MatchEntirePrefix, "MatchEntirePrefix"},
    {CCN_DTAG_CCNProtocolDataUnit, "CCNProtocolDataUnit"},
    {CCN_DTAG_ExperimentalResponseFilter, "ExperimentalResponseFilter"},
};

const struct ccn_dict ccn_dtag_dict = {ARRAY_N(ccn_tagdict), ccn_tagdict};
