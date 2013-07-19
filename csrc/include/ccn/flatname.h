/**
 * @file ccn/flatname.h
 *
 * Flattened representation of a name
 */
/*
 * Copyright (C) 2011-2013 Palo Alto Research Center, Inc.
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

#ifndef CCN_FLATNAME_DEFINED
#define CCN_FLATNAME_DEFINED

struct ccn_charbuf;

/**
 * Flat name representation
 *
 * Internally, a name may be stored in a representation
 * different than the ccnb encoding that is used on the wire.
 * This encoding is designed so that simple lexical ordering on
 * flatname byte arrays corresponds precisely with ccn's CanonicalOrdering
 * of Names.
 *
 * In the flatname representation, the bytes that constitute each
 * Component are prepended by a length indicator that occupies one or
 * more bytes.  The high-order bit is used to mark the end of the length
 * indicator, with 0 marking the last byte. The low order 7 bits of each
 * of these bytes are concatenated together, in big endian order, to form
 * the length.
 *
 * For example:
 * 0x00                => the zero-length component
 * 0x01 0x41           => the component "A"
 * 0x7F 0xC1 ...       => a component 127 bytes long that starts with "%C1"
 * 0x81 0x00 0x39 ...  => a component 128 bytes long that starts with "9"
 * 0xff 0x3F 0x30 ...  => a component 16383 bytes long that starts with "0"
 *
 * Length indicators larger than this are possible in theory, but unlikely
 * to come up in practice. Nonetheless, we do allow 3-byte length indicators.
 */

/* Name flattening */
int ccn_flatname_append_component(struct ccn_charbuf *dst,
                                  const unsigned char *comp, size_t size);
int ccn_flatname_append_from_ccnb(struct ccn_charbuf *dst,
                                  const unsigned char *ccnb, size_t size,
                                  int skip, int count);
int ccn_flatname_from_ccnb(struct ccn_charbuf *dst,
                           const unsigned char *ccnb, size_t size);

/* Name unflattening */
int ccn_name_append_flatname(struct ccn_charbuf *dst,
                             const unsigned char *flatname, size_t size,
                             int skip, int count);
int ccn_uri_append_flatname(struct ccn_charbuf *uri,
                            const unsigned char *flatname, size_t size,
                            int includescheme);
/* Flatname accessors */
int ccn_flatname_ncomps(const unsigned char *flatname, size_t size);

/* Flatname comparison */
#define CCN_STRICT_PREFIX (-9999)   /* a is a strict prefix of b */
#define CCN_STRICT_REV_PREFIX 9999  /* b is a strict prefix of a */
int ccn_flatname_charbuf_compare(struct ccn_charbuf *a, struct ccn_charbuf *b);
int ccn_flatname_compare(const unsigned char *a, size_t al,
                         const unsigned char *b, size_t bl);

/*
 * Parse the component delimiter from the start of a flatname
 * Returns -1 for error, 0 nothing left, or compsize * 4 + delimsize
 */
int ccn_flatname_next_comp(const unsigned char *flatname, size_t size);
/** Get delimiter size from return value of ccn_flatname_next_comp */
#define CCNFLATDELIMSZ(rnc) ((rnc) & 3)
/** Get data size from return value of ccn_flatname_next_comp */
#define CCNFLATDATASZ(rnc) ((rnc) >> 2)
/** Get total delimited size from return value of ccn_flatname_next_comp */
#define CCNFLATSKIP(rnc) (CCNFLATDELIMSZ(rnc) + CCNFLATDATASZ(rnc))

#endif
