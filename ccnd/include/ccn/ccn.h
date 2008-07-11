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
#include <ccn/indexbuf.h>

#define CCN_INTEREST_HALFLIFE_MICROSEC 4000000

/* opaque declarations */
struct ccn;

/* forward declarations */
struct ccn_closure;
enum ccn_upcall_kind;

/*
 * Types for implementing upcalls
 * To receive notifications of incoming interests and content, the
 * client is expected to create closures (using client-managed memory).
 * 
 */
typedef int (*ccn_handler)(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn *h,
    const unsigned char *ccnb,    /* binary-format Interest or ContentObject */
    size_t ccnb_size,             /* size in bytes */
    struct ccn_indexbuf *comps,   /* component boundaries within ccnb */
    int matched_comps             /* number of components in registration */
);
enum ccn_upcall_kind {
    CCN_UPCALL_FINAL,       /* handler is about to be deregistered */
    CCN_UPCALL_INTEREST,    /* incoming interest */
    CCN_UPCALL_CONSUMED_INTEREST, /* incoming interest, someone has answered */
    CCN_UPCALL_CONTENT      /* incoming content */
};
struct ccn_closure {
    ccn_handler p; 
    void *data;    /* for client use */
    int refcount;  /* client is not expected to update this directly */
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
 * Writing Names:
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

/*
 * ccn_name_append_str: add a Component that is a \0 terminated string.
 * The component added is the bytes of the string without the \0.
 * This function is convenient for those applications that construct 
 * component names from simple strings.
 * Return value is 0, or -1 for error
 */
int 
ccn_name_append_str(struct ccn_charbuf *c, const char *s);

/***********************************
 * Authenticators and signatures for content are constructed in charbufs
 * using the following routines.
 */

enum ccn_content_type {
    CCN_CONTENT_FRAGMENT,
    CCN_CONTENT_LINK,
    CCN_CONTENT_COLLECTION,
    CCN_CONTENT_LEAF,
    CCN_CONTENT_SESSION,
    CCN_CONTENT_HEADER
};

/*
 * ccn_auth_create_default: create authenticator in a charbuf 
 * with defaults.
 * Return value is 0, or -1 for error.
 */
int
ccn_auth_create_default(struct ccn_charbuf *c, /* output authenticator */
			struct ccn_charbuf *signature, /* output signature */
			enum ccn_content_type Type, /* input content type */
			struct ccn_charbuf *path, /* input path */
			int path_count, /* number of path components */
			char * content, /* input: raw content */
			size_t length /* length of raw content */
			);

/*
 * ccn_auth_create: create authenticator in a charbuf 
 * Note that KeyLocator is optional (may be NULL) and is ignored 
 * even if set in initial placeholder implementation
 * Return value is 0, or -1 for error.
 */
int
ccn_auth_create(struct ccn_charbuf *c,
	      struct ccn_charbuf *PublisherKeyID,
	      int NameComponentCount,
	      struct ccn_charbuf *Timestamp,
	      enum ccn_content_type Type,
	      struct ccn_charbuf *KeyLocator,
	      struct ccn_charbuf *ContentDigest);

/*
 * ccn_sign: placeholder to create a signature value.
 */
int
ccn_sign(struct ccn_charbuf *c, 
	 const struct ccn_charbuf *input);

/*
 * ccn_digest: placeholder to create a digest value.
 */
int
ccn_digest(struct ccn_charbuf *c, 
	   const struct ccn_charbuf *input);

/***********************************
 * ccn_express_interest: 
 * repeat: -1 - keep expressing until cancelled
 *          0 - cancel this interest
 *         >0 - express this many times (not counting timeouts)
 * Use the above routines to set up namebuf.
 * The namebuf may be reused or destroyed after the call.
 * If action is not NULL, it is invoked when matching data comes back.
 * If interest_template is supplied, it should contain a ccnb formatted
 * interest message to provide the other portions of the interest.
 */
int ccn_express_interest(struct ccn *h, struct ccn_charbuf *namebuf,
                         int repeat, struct ccn_closure *action,
                         struct ccn_charbuf *interest_template);
/*
 * ccn_set_default_content_handler:
 * Sets default content handler, replacing any in effect.
 * This is used when content comes in that does not match any
 * expressed interest that has a handler.
 */
int ccn_set_default_content_handler(struct ccn *h,
                                    struct ccn_closure *action);

/***********************************
 * ccn_set_interest_filter: 
 * The action, if provided, will be called when an interest arrives that
 * has the given name as a prefix.
 * If action is NULL, any existing filter is removed.
 * The namebuf may be reused or destroyed after the call.
 * Handler should return -1 if it cannot produce new content in response.
 * The upcall kind passed to the handler will be CCN_UPCALL_INTEREST
 * if no other handler has claimed to produce content, or else
 * CCN_UPCALL_CONSUMED_INTEREST.
 */
int ccn_set_interest_filter(struct ccn *h, struct ccn_charbuf *namebuf,
                            struct ccn_closure *action);

/*
 * ccn_set_default_interest_handler:
 * Sets default interest handler, replacing any in effect.
 */
int ccn_set_default_interest_handler(struct ccn *h,
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

/*
 * ccn_run: process incoming
 * This may serve as the main event loop for simple apps by passing 
 * a timeout value of -1.
 * The timeout is in milliseconds.
 */
int ccn_run(struct ccn *h, int timeout);

/***********************************
 * Binary decoding
 * These routines require the whole binary object be buffered.
 */

struct ccn_buf_decoder {
    struct ccn_skeleton_decoder decoder;
    const unsigned char *buf;
    size_t size;
};

struct ccn_buf_decoder *ccn_buf_decoder_start(struct ccn_buf_decoder *d,
    const unsigned char *buf, size_t size);

void ccn_buf_advance(struct ccn_buf_decoder *d);

/* The match routines return a boolean - true for match */
int ccn_buf_match_dtag(struct ccn_buf_decoder *d, enum ccn_dtag dtag);

int ccn_buf_match_some_dtag(struct ccn_buf_decoder *d);

int ccn_buf_match_some_blob(struct ccn_buf_decoder *d);
int ccn_buf_match_blob(struct ccn_buf_decoder *d,
                       const unsigned char **bufp, size_t *sizep);

int ccn_buf_match_udata(struct ccn_buf_decoder *d, const char *s);

int ccn_buf_match_attr(struct ccn_buf_decoder *d, const char *s);

/* ccn_buf_check_close enters an error state if element closer not found */
void ccn_buf_check_close(struct ccn_buf_decoder *d);

/*
 * ccn_ref_tagged_BLOB: Get address and size associated with a blob-valued element
 * Returns 0 for success, negative value for error.
 */
int ccn_ref_tagged_BLOB(enum ccn_dtag tt,
                        const unsigned char *buf,
                        size_t start, size_t stop,
                        const unsigned char **presult, size_t *psize);

int ccn_fetch_tagged_nonNegativeInteger(enum ccn_dtag tt,
            const unsigned char *buf, size_t start, size_t stop);

/*********** Interest parsing ***********/

/*
 * The parse of an interest results in an array of offsets into the 
 * wire representation, with the start and end of each major element and
 * a few of the inportant sub-elements.  The following enum allows those
 * array items to be referred to symbolically.  The *_B_* indices correspond
 * to beginning offsets and the *_E_* indices correspond to ending offests.
 * An omitted element has its beginning and ending offset equal to each other.
 * Normally these offsets will end up in non-decreasing order.
 * Some aliasing tricks may be played here, e.g. since
 * offset[CCN_PI_B_NameComponentCount] is always equal to offset[CCN_PI_E_Name],
 * we may define CCN_PI_B_NameComponentCount = CCN_PI_E_Name.  However, code
 * should not rely on that, since it may change from time to time as the
 * interest schema evolves.
 */
enum ccn_parsed_interest_offsetid {
    CCN_PI_B_Name,
    CCN_PI_B_Component0,
    CCN_PI_E_ComponentLast,
    CCN_PI_E_Name,
    CCN_PI_B_NameComponentCount /* = CCN_PI_E_Name */,
    CCN_PI_E_NameComponentCount,
    CCN_PI_B_PublisherID,
    CCN_PI_E_PublisherID,
    CCN_PI_B_Exclude,
    CCN_PI_E_Exclude,
    CCN_PI_B_OrderPreference,
    CCN_PI_E_OrderPreference,
    CCN_PI_B_AnswerOriginKind,
    CCN_PI_E_AnswerOriginKind,
    CCN_PI_B_Scope,
    CCN_PI_E_Scope,
    CCN_PI_B_Count,
    CCN_PI_E_Count,
    CCN_PI_B_Nonce,
    CCN_PI_E_Nonce,
    CCN_PI_B_OTHER,
    CCN_PI_E_OTHER,
    CCN_PI_E
};

struct ccn_parsed_interest {
    int prefix_comps;
    int orderpref;
    int answerfrom;
    int scope;
    unsigned short offset[CCN_PI_E+1];
};

/*
 * ccn_parse_interest:
 * Returns number of name components, or a negative value for an error.
 * Fills in *interest.
 * If components is not NULL, it is filled with byte indexes of
 * the start of each Component of the Name of the Interest,
 * plus one additional value for the index of the end of the last component.
 */
int
ccn_parse_interest(const unsigned char *msg, size_t size,
                   struct ccn_parsed_interest *interest,
                   struct ccn_indexbuf *components);

/*********** ContentObject parsing ***********/
/* Analogous to enum ccn_parsed_interest_offsetid, but for content */
enum ccn_parsed_content_object_offsetid {
    CCN_PCO_B_Signature,
    CCN_PCO_E_Signature,
    CCN_PCO_B_Name,
    CCN_PCO_B_Component0,
    CCN_PCO_E_ComponentN,
    CCN_PCO_E_Name,
    CCN_PCO_B_ContentAuthenticator,
    CCN_PCO_B_CAUTH_PublisherKeyID,
    CCN_PCO_E_CAUTH_PublisherKeyID,
    CCN_PCO_B_CAUTH_Timestamp,
    CCN_PCO_E_CAUTH_Timestamp,
    CCN_PCO_B_CAUTH_Type,
    CCN_PCO_E_CAUTH_Type,
    CCN_PCO_B_CAUTH_KeyLocator,
    /* Exactly one of Key, Certificate, or KeyName will be present */
    CCN_PCO_B_CAUTH_Key_Certificate_KeyName,
    CCN_PCO_E_CAUTH_Key_Certificate_KeyName,
    CCN_PCO_E_CAUTH_KeyLocator,
    CCN_PCO_E_ContentAuthenticator,
    CCN_PCO_B_Content,
    CCN_PCO_E_Content,
    CCN_PCO_E
};

struct ccn_parsed_ContentObject {
    int magic;
    unsigned short offset[CCN_PCO_E+1];
};

/*
 * ccn_parse_ContentObject:
 * Returns 0, or a negative value for an error.
 * Fills in *x with offsets of constituent elements.
 * If components is not NULL, it is filled with byte indexes
 * of the start of each Component of the Name of the ContentObject,
 * plus one additional value for the index of the end of the last component.
 */
int ccn_parse_ContentObject(const unsigned char *msg, size_t size,
                   struct ccn_parsed_ContentObject *x,
                   struct ccn_indexbuf *components);

/*
 * ccn_compare_names:
 * Returns a value that is negative, zero, or positive depending upon whether
 * the Name element of a is less, equal, or greater than the Name element of b.
 * a and b may point to the start of ccnb-encoded elements of type Name,
 * Interest, or ContentObject.  The size values should be large enough to
 * encompass the entire Name element.
 * The ordering used is the canonical ordering of the ccn name hierarchy.
 */
int ccn_compare_names(const unsigned char *a, size_t asize,
                      const unsigned char *b, size_t bsize);

/***********************************
 * Reading Names:
 * Names may be (minimally) read using the following routines, b
 * based on the component boundary markers generated from a parse.
 */

/*
 * ccn_indexbuf_comp_strcmp: perform strcmp of given val against 
 * component.  Returns -1, 0, or 1 if val is less than, equal to,
 * or greater than the component at given index i (counting from 0).
 * Safe even on binary components, though the result may not be useful.
 * NOTE - this ordering may be different from the canonical ordering
 * used by ccn_compare_names();
 */
int ccn_name_comp_strcmp(const unsigned char *data,
                         const struct ccn_indexbuf* indexbuf,
                         unsigned int i,
                         const char *val);

/*
 * ccn_indexbuf_comp_strdup: return a copy of component at given index i
 * as a string, that is, it will be terminated by \0.
 * The first component is index 0.
 * Caller is responsible to free returned buffer containing copy.
 */
char * ccn_name_comp_strdup(const unsigned char *data,
                            const struct ccn_indexbuf *indexbuf,
                            unsigned int i);

/***********************************
 * Reading content objects
 */

int ccn_content_get_value(const unsigned char *data, size_t data_size,
                          const struct ccn_parsed_ContentObject *content,
                          const unsigned char **value, size_t *size);

/***********************************
 * Binary encoding
 */

/*
 * ccn_encode_ContentObject:
 *    buf: output buffer where encoded object is written
 *    Name: encoded name from ccn_name_init
 *    ContentAuthenticator: encoded authenticator from ccn_auth_create
 *    Signature: encoded signature from ccn_sign
 *    Content: raw content 
 *    len: length of the content
 */

int ccn_encode_ContentObject(struct ccn_charbuf *buf,
			     struct ccn_charbuf *Name,
			     struct ccn_charbuf *ContentAuthenticator,
			     struct ccn_charbuf *Signature,
			     const char *Content, int len);

const char * ccn_content_name(enum ccn_content_type type);

/***********************************
 * Debugging
 */

/*
 * ccn_perror: produce message on standard error output describing the last
 * error encountered during a call using the given handle.
 */
void ccn_perror(struct ccn *h, const char * s);


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
