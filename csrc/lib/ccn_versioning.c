/**
 * @file ccn_versioning.c
 * @brief Versioning support.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009-2010 Palo Alto Research Center, Inc.
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

#define FF 0xff

/**
 * This appends a filter useful for
 * excluding everything between two 'fenceposts' in an Exclude construct.
 */
static void
append_filter_all(struct ccn_charbuf *c)
{
    ccn_charbuf_append_tt(c, CCN_DTAG_Any, CCN_DTAG);
    ccn_charbuf_append_closer(c);
}

/**
 * Append AnswerOriginKind=1 to partially constructed Interest, meaning
 * do not generate new content.
 */
static void
answer_passive(struct ccn_charbuf *templ)
{
    ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
    ccn_charbuf_append(templ, "1", 1);
    ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
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
    unsigned char b[7] = {CCN_MARKER_VERSION + 1, 0, 0, 0, 0, 0, 0};
    ccn_charbuf_append_tt(templ, CCN_DTAG_Component, CCN_DTAG);
    ccn_charbuf_append_tt(templ, sizeof(b), CCN_BLOB);
    ccn_charbuf_append(templ, b, sizeof(b));
    ccn_charbuf_append_closer(templ); /* </Component> */
}

static struct ccn_charbuf *
resolve_templ(struct ccn_charbuf *templ, unsigned const char *vcomp, int size)
{
    if (templ == NULL)
        templ = ccn_charbuf_create();
    if (size < 3 || size > 16) {
        ccn_charbuf_destroy(&templ);
        return(NULL);
    }
    templ->length = 0;
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_Exclude, CCN_DTAG);
    append_filter_all(templ);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Component, CCN_DTAG);
    ccn_charbuf_append_tt(templ, size, CCN_BLOB);
    ccn_charbuf_append(templ, vcomp, size);
    ccn_charbuf_append_closer(templ); /* </Component> */
    append_future_vcomp(templ);
    append_filter_all(templ);
    ccn_charbuf_append_closer(templ); /* </Exclude> */
    answer_highest(templ);
    answer_passive(templ);
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
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
 * @param timeout_ms is a time value in milliseconds. This is applied per
 *        fetch attempt, so the total time may be longer by a factor that
 *        depends on the number of (ccn) hops to the source(s).
 * @returns -1 for error, 0 if name could not be extended, 1 if was.
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
    int n;
    struct ccn_indexbuf *nix = ccn_indexbuf_create();
    unsigned char lowtime[7] = {CCN_MARKER_VERSION, 0, FF, FF, FF, FF, FF};
    
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
    templ = resolve_templ(templ, lowtime, sizeof(lowtime));
    ccn_charbuf_append(prefix, name->buf, name->length); /* our copy */
    cobj->length = 0;
    res = ccn_get(h, prefix, templ, timeout_ms, cobj, pco, ndx, 0);
    while (cobj->length != 0) {
        if (pco->type == CCN_CONTENT_NACK) // XXX - also check for number of components
            break;
        res = ccn_name_comp_get(cobj->buf, ndx, n, &vers, &vers_size);
        if (res < 0)
            break;
        if (vers_size == 7 && vers[0] == CCN_MARKER_VERSION) {
            /* Looks like we have versions. */
            name->length = 0;
            ccn_charbuf_append(name, prefix->buf, prefix->length);
            ccn_name_append(name, vers, vers_size);
            myres = 0;
            if ((versioning_flags & CCN_V_EST) == 0)
                break;
            templ = resolve_templ(templ, vers, vers_size);
            if (templ == NULL) break;
            cobj->length = 0;
            res = ccn_get(h, prefix, templ, timeout_ms, cobj, pco, ndx,
                          CCN_GET_NOKEYWAIT);
        }
        else break;
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
    myres |= ccn_charbuf_append_tt(name, CCN_DTAG_Component, CCN_DTAG);
    if ((versioning_flags & CCN_V_NOW) != 0)
        myres |= ccnb_append_now_blob(name, CCN_MARKER_VERSION);
    else {
        myres |= ccnb_append_timestamp_blob(name, CCN_MARKER_VERSION, secs, nsecs);
    }
    myres |= ccn_charbuf_append_closer(name); /* </Component> */
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
    myres |= ccn_charbuf_append_closer(name); /* </Name> */
Finish:
    myres = (myres < 0) ? -1 : 0;
    ccn_indexbuf_destroy(&nix);
    return(myres);
}
