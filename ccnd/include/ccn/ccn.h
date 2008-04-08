/*
 * ccn/ccn.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 * This is the low-level interface for CCN clients
 *
 * $Id$
 */

#ifndef CCN_CCN_DEFINED
#define CCN_CCN_DEFINED

#include <ccn/coding.h>
#include <ccn/charbuf.h>

struct ccn;
enum ccn_upcall_kind {
    CCN_UPCALL_FINAL,       /* handler is about to be deregistered */
    CCN_UPCALL_INTEREST,    /* incoming interest */
    CCN_UPCALL_CONTENT      /* incoming content */
};
struct ccn_closure;
typedef int (*ccn_handler)(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind,
    struct ccn *h,
    const unsigned char *ccnb,    /* binary-format Interest or ContentObject */
    size_t ccnb_size,
    const unsigned char *matched, /* ccnb formatted name that was matched */
    size_t matched_size
);
struct ccn_closure {
    ccn_handler p;
    void *data;
    int refcount;
};

/*
 * ccn_create: create a client handle
 * Creates and initializes a client handle, not yet connected.
 * On error, returns NULL and sets errno.
 * Errors: ENOMEM
 */ 
struct ccn *ccn_create(void);

/*
 * ccn_connect: connect to local ccnd
 * Use NULL for name to get the default.
 * Normal return value is the fd for the connection.
 * On error, returns -1.
 */ 
int ccn_connect(struct ccn *h, const char *name);

/*
 * ccn_get_connection_fd: get connection socket fd
 * This is in case the client needs to know the associated
 * fd, e.g. for use in select/poll.
 * The client should not use this fd for actual I/O.
 * Normal return value is the fd for the connection.
 * Returns -1 if the handle is not connected.
 */ 
int ccn_get_connection_fd(struct ccn *h);

/*
 * ccn_disconnect: disconnect from local ccnd
 * Breaks the connection, but leaves other state intact.
 */ 
int ccn_disconnect(struct ccn *h);

/*
 * ccn_destroy: destroy handle
 * Releases all resources associated with *hp and sets it to NULL.
 */ 
void ccn_destroy(struct ccn **hp);

/***********************************
 * Names for interests are constructed in charbufs using 
 * the following routines.
 */

/*
 * ccn_name_init: reset charbuf to represent an empty Name in binary format
 * Return value is 0, or -1 for error.
 */
int ccn_name_init(struct ccn_charbuf *c);

/*
 * ccn_name_append: add a Component to a Name
 * The component is an arbitrary string of n octets, no escaping required.
 * Return value is 0, or -1 for error.
 */
int ccn_name_append(struct ccn_charbuf *c, const void *component, size_t n);

/***********************************
 * ccn_express_interest: 
 * repeat: -1 - keep expressing until cancelled
 *          0 - cancel this interest
 *         >0 - express this many times (not counting timeouts)
 * Use the above routines to set up namebuf.
 * The namebuf may be reused or destroyed after the call.
 * If action is not NULL, it is invoked when matching data comes back.
 */
int ccn_express_interest(struct ccn *h, struct ccn_charbuf *namebuf,
                         int repeat, struct ccn_closure *action);
/*
 * ccn_set_default_content_handler:
 * Sets default content_handler, replacing any in effect.
 * This is used when content comes in that does not match any
 * expressed interest that has a handler.
 */
int ccn_set_default_content_handler(struct ccn *h,
                                    struct ccn_closure *action);

/***********************************
 * ccn_set_interest_filter: 
 * The namebuf may be reused or destroyed after the call.
 * If action is NULL, any existing filter is removed.
 * Otherwise action will be called when an interest arrives that is
 * either a prefix of the given name or which has the given name
 * as a prefix.
 * Handler should return -1 if it cannot produce new content in response.
 */
int /*NYI*/
ccn_set_interest_filter(struct ccn *h, struct ccn_charbuf *namebuf,
                        struct ccn_closure *action);
/*
 * ccn_set_default_interest_handler:
 * Sets default interest_handler, replacing any in effect.
 */
int /*NYI*/
ccn_set_default_interest_handler(struct ccn *h,
                                 struct ccn_closure *action);

/*
 * ccn_put: send ccn binary
 * This checks for a single well-formed ccn binary object and 
 * sends it out (or queues it to be sent).  For normal clients,
 * this should be a ContentObject sent in response to an Interest,
 * but ccn_put does not check for that.
 * Returns -1 for error, 0 if sent completely, 1 if queued.
 */
int ccn_put(struct ccn *h, const void *p, size_t length);

/*
 * ccn_output_is_pending:
 * This is for client-managed select or poll.
 * Returns 1 if there is data waiting to be sent, else 0.
 */
int ccn_output_is_pending(struct ccn *h);

/***********************************
 * Binary decoding
 * These routines require the whole binary object be buffered.
 */

struct ccn_buf_decoder {
    struct ccn_skeleton_decoder decoder;
    unsigned char *buf;
    size_t size;
};

struct ccn_buf_decoder *ccn_buf_decoder_start(struct ccn_buf_decoder *d,
    unsigned char *buf, size_t size);

void ccn_buf_advance(struct ccn_buf_decoder *d);

/* The match routines return a boolean - true for match */
int ccn_buf_match_dtag(struct ccn_buf_decoder *d, enum ccn_dtag dtag);

int ccn_buf_match_blob(struct ccn_buf_decoder *d,
                       unsigned char **bufp, size_t *sizep);

int ccn_buf_match_udata(struct ccn_buf_decoder *d, const char *s);

int ccn_buf_match_attr(struct ccn_buf_decoder *d, const char *s);

/* ccn_buf_check_close enters an error state if element closer not found */
void ccn_buf_check_close(struct ccn_buf_decoder *d);

struct ccn_parsed_interest {
    size_t name_start;
    size_t name_size;
    size_t pubid_start;
    size_t pubid_size;
    size_t nonce_start;
    size_t nonce_size;
};

/*
 * ccn_parse_interest:
 * Returns number of name components, or 
 * a negative value for an error.
 * Fills in *interest.
 */
int
ccn_parse_interest(unsigned char *msg, size_t size,
                   struct ccn_parsed_interest *interest);

/***********************************
 * Low-level binary formatting
 */

/*
 * ccn_charbuf_append_tt: append a token start
 * Return value is 0, or -1 for error.
 */
int ccn_charbuf_append_tt(struct ccn_charbuf *c, size_t val, enum ccn_tt tt);

/*
 * ccn_charbuf_append_tt: append a CCN_CLOSE
 * Return value is 0, or -1 for error.
 */
int ccn_charbuf_append_closer(struct ccn_charbuf *c);

#endif
