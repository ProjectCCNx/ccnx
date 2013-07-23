/*
 * ccn/fetch.h
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2010-2013 Palo Alto Research Center, Inc.
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

/**
 * @file ccn/fetch.h
 * Streaming access for fetching segmented CCNx data.
 *
 * Supports multiple streams from a single connection and
 * seeking to an arbitrary position within the associated file.
 */

#ifndef CCN_FETCH_DEFINED
#define CCN_FETCH_DEFINED

#include <stdio.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>

/**
 * Creates a new ccn_fetch object using the given ccn connection.
 * If h == NULL, attempts to create a new connection automatically.
 * @returns NULL if the creation was not successful
 *    (only can happen for the h == NULL case).
 */
struct ccn_fetch *
ccn_fetch_new(struct ccn *h);

typedef enum {
	ccn_fetch_flags_None = 0,
	ccn_fetch_flags_NoteGlitch = 1,
	ccn_fetch_flags_NoteAddRem = 2,
	ccn_fetch_flags_NoteNeed = 4,
	ccn_fetch_flags_NoteFill = 8,
	ccn_fetch_flags_NoteFinal = 16,
	ccn_fetch_flags_NoteTimeout = 32,
	ccn_fetch_flags_NoteOpenClose = 64,
	ccn_fetch_flags_NoteAll = 0xffff
} ccn_fetch_flags;

#define CCN_FETCH_READ_ZERO (-3)
#define CCN_FETCH_READ_TIMEOUT (-2)
#define CCN_FETCH_READ_NONE (-1)
#define CCN_FETCH_READ_END (0)

/**
 * Sets the destination for debug output.  NULL disables debug output.
 */
void
ccn_fetch_set_debug(struct ccn_fetch *f, FILE *debug, ccn_fetch_flags flags);

/**
 * Destroys a ccn_fetch object.
 * Only destroys the underlying ccn connection if it was automatically created.
 * Forces all underlying streams to close immediately.
 * @returns NULL in all cases.
 */
struct ccn_fetch *
ccn_fetch_destroy(struct ccn_fetch *f);

/**
 * Polls the underlying streams and attempts to make progress.
 * Scans the streams for those that have data already present, or are at the end
 * of the stream.  If the count is 0, perfoms a ccn_poll on the underlying
 * ccn connection with a 0 timeout.
 *
 * NOTE: periodic calls to ccn_fetch_poll should be performed to update
 * the contents of the streams UNLESS the client is calling ccn_run for
 * the underlying ccn connection.
 * @returns the count of streams that have pending data or have ended.
 */
int
ccn_fetch_poll(struct ccn_fetch *f);

/**
 * Provides an iterator through the underlying streams.
 * Use fs == NULL to start the iteration, and an existing stream to continue
 * the iteration.
 * @returns the next stream in the iteration, or NULL at the end.
 * Note that providing a stale (closed) stream handle will return NULL.
 */
struct ccn_fetch_stream *
ccn_fetch_next(struct ccn_fetch *f, struct ccn_fetch_stream *fs);

/**
 * Sets caller's context for the stream.
 */
void 
ccn_fetch_set_context(struct ccn_fetch_stream *fs, void *context);

/**
 * @returns caller's context, as previously set for the stream.
 */
void *
ccn_fetch_get_context(struct ccn_fetch_stream *fs);

/**
 * @returns the underlying ccn connection.
 */
struct ccn *
ccn_fetch_get_ccn(struct ccn_fetch *f);

/**
 * Creates a stream for a named interest.
 * The name should be a ccnb encoded interest.
 * If resolveVersion, then we assume that the version is unresolved, 
 * and an attempt is made to determine the version number using the highest
 * version.  If interestTemplate == NULL then a suitable default is used.
 * The max number of buffers (maxBufs) is a hint, and may be clamped to an
 * implementation minimum or maximum.
 * If assumeFixed, then assume that the segment size is given by the first
 * segment fetched, otherwise segments may be of variable size. 
 * @returns NULL if the stream creation failed,
 *    otherwise returns the new stream.
 */
struct ccn_fetch_stream *
ccn_fetch_open(struct ccn_fetch *f, struct ccn_charbuf *name,
			   const char *id,
			   struct ccn_charbuf *interestTemplate,
			   int maxBufs,
			   int resolveVersion,
			   int assumeFixed);

/**
 * Closes the stream and reclaims any resources used by the stream.
 * The stream object will be freed, so the client must not access it again.
 * @returns NULL in all cases.
 */
struct ccn_fetch_stream *
ccn_fetch_close(struct ccn_fetch_stream *fs);

/**
 * Tests for available bytes in the stream.
 * Determines how many bytes can be read on the given stream
 * without waiting (via ccn_fetch_poll).
 * @returns
 *    CCN_FETCH_READ_TIMEOUT if a timeout occurred,
 *    CCN_FETCH_READ_ZERO if a zero-length segment was found
 *    CCN_FETCH_READ_NONE if no bytes are immediately available
 *    CCN_FETCH_READ_END if the stream is at the end,
 *    and N > 0 if N bytes can be read without performing a poll.
 */
intmax_t
ccn_fetch_avail(struct ccn_fetch_stream *fs);

/**
 * Reads bytes from a stream.
 * Reads at most len bytes into buf from the given stream.
 * Will not wait for bytes to arrive.
 * Advances the read position on a successful read.
 * @returns
 *    CCN_FETCH_READ_TIMEOUT if a timeout occurred,
 *    CCN_FETCH_READ_ZERO if a zero-length segment was found
 *    CCN_FETCH_READ_NONE if no bytes are immediately available
 *    CCN_FETCH_READ_END if the stream is at the end,
 *    and N > 0 if N bytes were read.
 */
intmax_t
ccn_fetch_read(struct ccn_fetch_stream *fs,
			   void *buf,
			   intmax_t len);

/**
 * Resets the timeout indicator, which will cause pending interests to be
 * retried.  The client determines conditions for a timeout to be considered
 * an unrecoverable error.
 */
void
ccn_reset_timeout(struct ccn_fetch_stream *fs);

/**
 * Seeks to a position in a stream.
 * Sets the read position.
 * It is strongly recommended that the seek is only done to a position that
 * is either 0 or has resulted from a successful read.  Otherwise
 * end of stream indicators may be returned for a seek beyond the end.
 * @returns -1 if the seek is to a bad position
 * or if the segment size is variable, otherwise returns 0.
 */
int
ccn_fetch_seek(struct ccn_fetch_stream *fs,
			   intmax_t pos);

/**
 * @returns the current read position (initially 0)
 */
intmax_t
ccn_fetch_position(struct ccn_fetch_stream *fs);

#endif
