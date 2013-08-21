/**
 * @file ccn_versioning.c
 * @brief Versioning support.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
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
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>
#include <ccn/ccn_private.h>
#include <sys/time.h>

#define FF 0xff

/**
 * This appends a filter useful for
 * excluding everything between two 'fenceposts' in an Exclude construct.
 */
static void
append_filter_all(struct ccn_charbuf *c)
{
    ccnb_element_begin(c, CCN_DTAG_Any);
    ccnb_element_end(c);
}

/**
 * Append AnswerOriginKind=1 to partially constructed Interest, meaning
 * do not generate new content.
 */
static void
answer_passive(struct ccn_charbuf *templ)
{
    ccnb_append_tagged_udata(templ, CCN_DTAG_AnswerOriginKind, "1", 1);
}

/**
 * Append ChildSelector to partially constructed Interest, meaning
 * prefer to send rightmost available.
 */
static void
answer_highest(struct ccn_charbuf *templ)
{
    ccnb_tagged_putf(templ, CCN_DTAG_ChildSelector, "1");
}

static void
append_future_vcomp(struct ccn_charbuf *templ)
{
    /* One beyond a distant future version stamp */
    const unsigned char b[7] = {CCN_MARKER_VERSION + 1, 0, 0, 0, 0, 0, 0};
    ccnb_append_tagged_blob(templ, CCN_DTAG_Component, b, sizeof(b));
}

static struct ccn_charbuf *
resolve_templ(struct ccn_charbuf *templ, unsigned const char *vcomp,
              int size, int lifetime, int versioning_flags, int allow_unversioned)
{
    if (templ == NULL)
        templ = ccn_charbuf_create();
    if (size < 3 || size > 16) {
        ccn_charbuf_destroy(&templ);
        return(NULL);
    }
    templ->length = 0;
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    /* exclude: [%01,]*,lowver,highver,*     depending on allow_unversioned */
    ccnb_element_begin(templ, CCN_DTAG_Exclude);
    if (allow_unversioned) {
        ccnb_append_tagged_blob(templ, CCN_DTAG_Component, "\x01", 1);
    }
    append_filter_all(templ);
    ccnb_append_tagged_blob(templ, CCN_DTAG_Component, vcomp, size);
    append_future_vcomp(templ);
    append_filter_all(templ);
    ccnb_element_end(templ); /* </Exclude> */
    answer_highest(templ);
    answer_passive(templ);
    if ((versioning_flags & CCN_V_SCOPE2) != 0)
        ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%d", 2);
    else if ((versioning_flags & CCN_V_SCOPE1) != 0)
        ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%d", 1);
    else if ((versioning_flags & CCN_V_SCOPE0) != 0)
        ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%d", 0);
    if (lifetime > 0)
        ccnb_append_tagged_binary_number(templ, CCN_DTAG_InterestLifetime, lifetime);
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

static int
ms_to_tu(int m)
{
    return ((m * 4096) / 1000);
}

/**
 * Resolve the version, based on existing ccn content.
 * @param h is the the ccn handle; it may be NULL, but it is preferable to
 *        use the handle that the client probably already has.
 * @param name is a ccnb-encoded Name prefix. It gets extended in-place with
 *        one additional Component such that it names highest extant
 *        version that can be found, subject to the supplied timeout.
 * @param versioning_flags presently must be CCN_V_HIGH or CCN_V_HIGHEST,
 *        possibly combined with CCN_V_NESTOK.  If CCN_V_NESTOK is not present
 *        and the ending component appears to be a version, the routine
 *        returns 0 immediately, on the assumption that an explicit
 *        version has already been provided.
 * @param timeout_ms is a time value in milliseconds. This is the total time
 *        that the caller can wait.
 * @returns -1 if an error occurred, or no content was found,
 *           0 if name was not extended and unversioned content was found,
 *           1 if name was extended with a version when content was found.
 */
int
ccn_resolve_version(struct ccn *h, struct ccn_charbuf *name,
                    int versioning_flags, int timeout_ms)
{
    int res;
    int myres = -1;
    struct ccn_parsed_ContentObject pco_space = { 0 };
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *prefix = ccn_charbuf_create();
    struct ccn_charbuf *cobj = ccn_charbuf_create();
    struct ccn_parsed_ContentObject *pco = &pco_space;
    struct ccn_indexbuf *ndx = ccn_indexbuf_create();
    const unsigned char *vers = NULL;
    size_t vers_size = 0;
    struct timeval start, prev, now;
    int n;
    int rtt_max = 0;
    int rtt;
    int ttimeout;
    struct ccn_indexbuf *nix = ccn_indexbuf_create();
    const unsigned char lowtime[7] = {CCN_MARKER_VERSION, 0, FF, FF, FF, FF, FF};
    
    if ((versioning_flags & ~CCN_V_NESTOK & ~CCN_V_EST) != CCN_V_HIGH) {
        ccn_seterror(h, EINVAL);
        ccn_perror(h, "ccn_resolve_version is only implemented for versioning_flags = CCN_V_HIGH(EST)");
        goto Finish;
    }
    n = ccn_name_split(name, nix);
    if (n < 0)
        goto Finish;
    if ((versioning_flags & CCN_V_NESTOK) == 0) {
        res = ccn_name_comp_get(name->buf, nix, n - 1, &vers, &vers_size);
        if (res >= 0 && vers_size == 7 && vers[0] == CCN_MARKER_VERSION) {
            myres = 0;
            goto Finish;
        }    
    }
    templ = resolve_templ(templ, lowtime, sizeof(lowtime),
                          ms_to_tu(timeout_ms) * 7 / 8, versioning_flags, 1);
    ccn_charbuf_append(prefix, name->buf, name->length); /* our copy */
    cobj->length = 0;
    gettimeofday(&start, NULL);
    prev = start;
    /*
     * the algorithm for CCN_V_HIGHEST is to send the initial Interest with
     * a lifetime that will ensure 1 resend before the timeout, and to keep
     * keep sending an Interest, excluding earlier versions, tracking the
     * maximum round trip time and using a timeout of 4*RTT, and an interest
     * lifetime that should get a retransmit.   If there is no response,
     * return the highest version found so far.
     */
    myres = -1;
    res = ccn_get(h, prefix, templ, timeout_ms, cobj, pco, ndx, 0);
    while (cobj->length != 0) {
        if (pco->type == CCN_CONTENT_NACK) // XXX - also check for number of components
            break;
        res = ccn_name_comp_get(cobj->buf, ndx, n, &vers, &vers_size);
        if (res < 0)
            break;
        if (vers_size == 7 && vers[0] == CCN_MARKER_VERSION) {
            name->length = 0;
            ccn_charbuf_append(name, prefix->buf, prefix->length);
            ccn_name_append(name, vers, vers_size);
            myres = 1;
            if ((versioning_flags & CCN_V_EST) == 0)
                break;
        } else if (vers_size == 1 && vers[0] == CCN_MARKER_SEQNUM) {
            /* this branch is only entered once as the next template disallows 
             * unversioned responses
             */
            myres = 0;
            vers = lowtime;
            vers_size = sizeof(lowtime);
        } else
            break;
        gettimeofday(&now, NULL);
        rtt = (now.tv_sec - prev.tv_sec) * 1000000 + (now.tv_usec - prev.tv_usec);
        if (rtt > rtt_max) rtt_max = rtt;
        prev = now;
        timeout_ms -= (now.tv_sec - start.tv_sec) * 1000 + (now.tv_usec - start.tv_usec) / 1000;
        if (timeout_ms <= 0)
            break;
        ttimeout = timeout_ms < (rtt_max/250) ? timeout_ms : (rtt_max/250);
        templ = resolve_templ(templ, vers, vers_size, ms_to_tu(ttimeout) * 7 / 8, versioning_flags, 0);
        if (templ == NULL) {
            break;
        }        cobj->length = 0;
        res = ccn_get(h, prefix, templ, ttimeout, cobj, pco, ndx,
                      CCN_GET_NOKEYWAIT);
    }
    
Finish:
    ccn_charbuf_destroy(&prefix);
    ccn_charbuf_destroy(&cobj);
    ccn_indexbuf_destroy(&ndx);
    ccn_indexbuf_destroy(&nix);
    ccn_charbuf_destroy(&templ);
    return(myres);
}

/**
 * Extend a Name with a new version stamp
 * @param h is the the ccn handle.
 *        May be NULL.  This procedure does not use the connection.
 * @param name is a ccnb-encoded Name prefix. By default it gets extended
 *        in-place with one additional Component that conforms to the
 *        versioning profile and is based on the supplied time, unless a
 *        version component is already present.
 * @param versioning_flags modifies the default behavior:
 *        CCN_V_REPLACE causes the last component to be replaced if it
 *        appears to be a version stamp.  If CCN_V_HIGH is set as well, an
 *        attempt will be made to generate a new version stamp that is
 *        later than the existing one, or to return an error.
 *        CCN_V_NOW bases the version on the current time rather than the
 *        supplied time.
 *        CCN_V_NESTOK will allow the new version component to be appended
 *        even if there is one there (this makes no difference if CCN_V_REPLACE
 *        is also set).
 * @param secs is the desired time, in seconds since epoch
 *        (ignored if CCN_V_NOW is set).
 * @param nsecs is the number of nanoseconds.
 * @returns -1 for error, 0 for success.
 */
int
ccn_create_version(struct ccn *h, struct ccn_charbuf *name,
                   int versioning_flags, intmax_t secs, int nsecs)
{
    size_t i;
    size_t j;
    size_t lc = 0;
    size_t oc = 0;
    int n;
    struct ccn_indexbuf *nix = NULL;
    int myres = -1;
    int already_versioned = 0;
    int ok_flags = (CCN_V_REPLACE | CCN_V_HIGH | CCN_V_NOW | CCN_V_NESTOK);
    // XXX - right now we ignore h, but in the future we may use it to try to avoid non-monotonicies in the versions.
    
    nix = ccn_indexbuf_create();
    n = ccn_name_split(name, nix);
    if (n < 0)
        goto Finish;
    if ((versioning_flags & ~ok_flags) != 0)
        goto Finish;        
    /* Check for existing version component */
    if (n >= 1) {
        oc = nix->buf[n-1];
        lc = nix->buf[n] - oc;
        if (lc <= 11 && lc >= 6 && name->buf[oc + 2] == CCN_MARKER_VERSION)
            already_versioned = 1;
    }
    myres = 0;
    if (already_versioned &&
        (versioning_flags & (CCN_V_REPLACE | CCN_V_NESTOK)) == 0)
        goto Finish;
    name->length -= 1; /* Strip name closer */
    i = name->length;
    myres |= ccnb_element_begin(name, CCN_DTAG_Component);
    if ((versioning_flags & CCN_V_NOW) != 0)
        myres |= ccnb_append_now_blob(name, CCN_MARKER_VERSION);
    else {
        myres |= ccnb_append_timestamp_blob(name, CCN_MARKER_VERSION, secs, nsecs);
    }
    myres |= ccnb_element_end(name); /* </Component> */
    if (myres < 0) {
        name->length = i;
        goto CloseName;
    }
    j = name->length;
    if (already_versioned && (versioning_flags & CCN_V_REPLACE) != 0) {
        oc = nix->buf[n-1];
        lc = nix->buf[n] - oc;
        if ((versioning_flags & CCN_V_HIGH) != 0 &&
            memcmp(name->buf + oc, name->buf + i, j - i) > 0) {
            /* Supplied version is in the future. */
            name->length = i;
            // XXX - we could try harder to make this work, for now just error out
            myres = -1;
            goto CloseName;
        }
        memmove(name->buf + oc, name->buf + i, j - i);
        name->length -= lc;
    }
CloseName:
    myres |= ccnb_element_end(name); /* </Name> */
Finish:
    myres = (myres < 0) ? -1 : 0;
    ccn_indexbuf_destroy(&nix);
    return(myres);
}
