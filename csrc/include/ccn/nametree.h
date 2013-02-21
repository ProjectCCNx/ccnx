/**
 * @file include/ccn/nametree.h
 *
 */
/*
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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

#ifndef CCN_NAMETREE_DEFINED
#define CCN_NAMETREE_DEFINED

/**
 * A cookie is used as a more ephemeral way of holding a reference to a
 * content object, without the danger of an undetected dangling reference
 * when the in-memory content handle is destroyed.  This is for internal
 * data structures such as queues or enumeration states, but should not
 * be stored in any long-term way.  Use a ccnr_accession, content name, or
 * digest for that.
 *
 * Holding a cookie does not prevent the in-memory content handle from being
 * destroyed, either explicitly or to conserve resources.
 *
 * The value 0 is used to denote no entry.
 */
typedef unsigned ccn_cookie;

struct ccn_nametree;
struct ccny;

struct ccn_nametree {
    ccn_cookie cookie;      /**< newest used cookie number */
    unsigned cookiemask;    /**< one less than a power of two */
    struct ccny **nmentry_by_cookie; /**< for direct lookup by cookie */
    struct ccny *last;      /**< link to last */
    int skipdim;            /**< dimension of skiplinks array */
    ccn_cookie *skiplinks;  /**< skiplist for name-ordered ops */
    unsigned short seed[3]; /**< for PRNG */
};

struct ccny {
    ccn_cookie cookie;      /**< cookie for this entry */
    struct ccn_charbuf *flatname; /**< for skiplist, et. al. */
    struct ccny *prev;      /**< link to previous, in name order */
    int skipdim;            /**< dimension of skiplinks array */
    ccn_cookie *skiplinks;  /**< skiplist links */
};

#endif
