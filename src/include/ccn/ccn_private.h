/*
 * ccn/ccn_private.h
 * 
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
 *
 * Additional operations that are irrevalent for most clients
 *
 */

#ifndef CCN_PRIVATE_DEFINED
#define CCN_PRIVATE_DEFINED

#include <sys/types.h>
#include <stdint.h>

struct ccn;
struct ccn_charbuf;

/*
 * Dispatch a message as if it had arrived on the socket
 */
void ccn_dispatch_message(struct ccn *h, unsigned char *msg, size_t size);

/*
 * Do any time-based operations
 * Returns number of microseconds before next call needed
 */
int ccn_process_scheduled_operations(struct ccn *h);

/*
 * Grab buffered output
 * Caller should destroy returned buffer.
 */
struct ccn_charbuf *ccn_grab_buffered_output(struct ccn *h);

int ccn_charbuf_append_timestamp_blob(struct ccn_charbuf *c, int marker, intmax_t secs, int nsecs);

int ccn_charbuf_append_now_blob(struct ccn_charbuf *c, int marker);

#endif

