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

#include <stdint.h>
#include <ccn/coding.h>
#include <ccn/charbuf.h>
#include <ccn/indexbuf.h>

// XXX - this is no longer a halflife, it should be renamed
#define CCN_INTEREST_HALFLIFE_MICROSEC 4000000

/* opaque declarations */
struct ccn;

/* forward declarations */
struct ccn_closure;
struct ccn_upcall_info;
struct ccn_parsed_interest;
struct ccn_parsed_ContentObject;

/*
 * Types for implementing upcalls
 * To receive notifications of incoming interests and content, the
 * client creates closures (using client-managed memory).
 */

/*
 * This tells what kind of event the upcall is handling.
 */
enum ccn_upcall_kind {
    CCN_UPCALL_FINAL,             /* handler is about to be deregistered */
    CCN_UPCALL_INTEREST,          /* incoming interest */
    CCN_UPCALL_CONSUMED_INTEREST, /* incoming interest, someone has answered */
    CCN_UPCALL_CONTENT,           /* incoming content */
    CCN_UPCALL_INTEREST_TIMED_OUT /* interest timed out */
};

/*
 * Upcalls return one of these values
 * XXX - need more documentation
 */
enum ccn_upcall_res {
    CCN_UPCALL_RESULT_ERR = -1,
    CCN_UPCALL_RESULT_OK = 0,
    CCN_UPCALL_RESULT_REEXPRESS = 1
};

/*
 * This is the procedure type for the closure's implementation.
 */
typedef enum ccn_upcall_res (*ccn_handler)(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info  /* details about the event */
);

/*
 * The client is responsible for managing this piece of memory and the
 * data therein. The refcount should be initially zero, and is used by the
 * library to keep to track of multiple registrations of the same closure.
 * When the count drops back to 0, the closure will be called with
 * kind = CCN_UPCALL_FINAL so that it has an opportunity to clean up.
 */
struct ccn_closure {
    ccn_handler p; 
    void *data;         /* for client use */
    intptr_t intdata;   /* for client use */
    int refcount;       /* client should not update this directly */
};

struct ccn_upcall_info {
    struct ccn *h;              /* The ccn library handle */
    /* Interest (incoming or matched) */
    const unsigned char *interest_ccnb;
    struct ccn_parsed_interest *pi;
    struct ccn_indexbuf *interest_comps;
    int matched_comps;
    /* Incoming content for CCN_UPCALL_CONTENT - otherwise NULL */
    const unsigned char *content_ccnb;
    struct ccn_parsed_ContentObject *pco;
    struct ccn_indexbuf *content_comps;
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
 * Writing Names
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
int ccn_name_append_str(struct ccn_charbuf *c, const char *s);

/*
 * ccn_name_append_components: add sequence of ccnb-encoded Components
 *    to a ccnb-encoded Name
 * start and stop are offsets from ccnb
 * Return value is 0, or -1 for obvious error
 */
int ccn_name_append_components(struct ccn_charbuf *c,
                               const unsigned char *ccnb,
                               size_t start, size_t stop);


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
    CCN_CONTENT_HEADER,
    CCN_CONTENT_KEY
};

/*
 * ccn_signed_info_create_default: create signed info in a charbuf 
 * with defaults.
 * Return value is 0, or -1 for error.
 */
int
ccn_signed_info_create_default(struct ccn_charbuf *c, /* output signed info */
                               enum ccn_content_type Type);

/*
 * ccn_signed_info_create: create signed info in a charbuf 
 * Note that key_locator is optional (may be NULL) and is ccnb encoded
 * Note that freshness is optional (-1 means omit)
 * Return value is 0, or -1 for error.
 */
int
ccn_signed_info_create(
    struct ccn_charbuf *c,              /* filled with result */
    const void *publisher_key_id,	/* input, (sha256) hash */
    size_t publisher_key_id_size, 	/* input, 32 for sha256 hashes */
    const char *datetime,		/* input, NULL for "now" */
    enum ccn_content_type type,         /* input */
    int freshness,			/* input, -1 means omit */
    const struct ccn_charbuf *key_locator); /* input, optional, ccnb encoded */

/***********************************
 * ccn_express_interest: 
 * Use the above routines to set up namebuf.
 * Matching occurs only on the first prefix_comps components of
 * the name, or on all components if prefix_comps is -1.
 * Any remaining components serve to establish the starting point for
 * the search for matching content.
 * The namebuf may be reused or destroyed after the call.
 * If action is not NULL, it is invoked when matching data comes back.
 * If interest_template is supplied, it should contain a ccnb formatted
 * interest message to provide the other portions of the interest.
 * It may also be reused or destroyed after the call.
 * When an interest times out, the upcall may return
 * CCN_UPCALL_RESULT_REEXPRESS to simply re-express the interest.
 * The default is to unregister the handler.  The common use will be for
 * the upcall to register again with an interest modified to prevent matching
 * the same interest again.
 */
int ccn_express_interest(struct ccn *h,
                         struct ccn_charbuf *namebuf,
                         int prefix_comps,
                         struct ccn_closure *action,
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

/*
 * ccn_set_run_timeout: modify ccn_run timeout
 * This may be called from an upcall to change the timeout value.
 * The timeout is in milliseconds.  Returns old value.
 */
int ccn_set_run_timeout(struct ccn *h, int timeout);

/***********************************
 * Bulk data
 */

/*
 * The client provides a ccn_seqfunc * (and perhaps a matching param)
 * to specify the scheme for naming the content items in the sequence.
 * Given the sequence number x, it should place in resultbuf the
 * corresponding blob that that will be used in the final explicit
 * Component of the Name of item x in the sequence.  This should
 * act as a mathematical function, returning the same answer for a given x.
 * (Ususally param will be NULL, but is provided in case it is needed.)
 */
typedef void ccn_seqfunc(uintmax_t x, void *param,
                         struct ccn_charbuf *resultbuf);

/*
 * Ready-to-use sequencing functions
 */
extern ccn_seqfunc ccn_decimal_seqfunc;
extern ccn_seqfunc ccn_binary_seqfunc;




/***********************************
 * Binary decoding
 * These routines require that the whole binary object be buffered.
 */

struct ccn_buf_decoder {
    struct ccn_skeleton_decoder decoder;
    const unsigned char *buf;
    size_t size;
};

struct ccn_buf_decoder *ccn_buf_decoder_start(struct ccn_buf_decoder *d,
    const unsigned char *buf, size_t size);

void ccn_buf_advance(struct ccn_buf_decoder *d);
int ccn_buf_advance_past_element(struct ccn_buf_decoder *d);

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
 * ccn_ref_tagged_BLOB: Get address & size associated with blob-valued element
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
    CCN_PI_B_LastPrefixComponent,
    CCN_PI_E_LastPrefixComponent,
    // CCN_PI_B_ComponentLast,
    CCN_PI_E_ComponentLast,
    CCN_PI_E_Name,
    CCN_PI_B_NameComponentCount /* = CCN_PI_E_Name */,
    CCN_PI_E_NameComponentCount,
    CCN_PI_B_AdditionalNameComponents,
    CCN_PI_E_AdditionalNameComponents,
    CCN_PI_B_PublisherID,
    CCN_PI_B_PublisherIDKeyDigest,
    CCN_PI_E_PublisherIDKeyDigest,
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
    int count;
    unsigned short offset[CCN_PI_E+1];
};

/*
 * Bitmasks for AnswerOriginKind
 */
#define CCN_AOK_CS      0x1     /* Answer from content store */
#define CCN_AOK_NEW     0x2     /* OK to produce new content */
#define CCN_AOK_DEFAULT (CCN_AOK_CS | CCN_AOK_NEW)
#define CCN_AOK_STALE   0x4     /* OK to answer with stale data */
#define CCN_AOK_EXPIRE  0x10    /* Mark as stale (must have Scope 0) */

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
    CCN_PCO_B_DigestAlgorithm,
    CCN_PCO_E_DigestAlgorithm,
    CCN_PCO_B_Witness,
    CCN_PCO_E_Witness,
    CCN_PCO_B_SignatureBits,
    CCN_PCO_E_SignatureBits,
    CCN_PCO_E_Signature,
    CCN_PCO_B_Name,
    CCN_PCO_B_Component0,
    CCN_PCO_E_ComponentN,
    CCN_PCO_E_ComponentLast = CCN_PCO_E_ComponentN,
    CCN_PCO_E_Name,
    CCN_PCO_B_SignedInfo,
    CCN_PCO_B_PublisherKeyID,
    CCN_PCO_E_PublisherKeyID,
    CCN_PCO_B_Timestamp,
    CCN_PCO_E_Timestamp,
    CCN_PCO_B_Type,
    CCN_PCO_E_Type,
    CCN_PCO_B_FreshnessSeconds,
    CCN_PCO_E_FreshnessSeconds,
    CCN_PCO_B_KeyLocator,
    /* Exactly one of Key, Certificate, or KeyName will be present */
    CCN_PCO_B_Key_Certificate_KeyName,
    CCN_PCO_E_Key_Certificate_KeyName,
    CCN_PCO_E_KeyLocator,
    CCN_PCO_E_SignedInfo,
    CCN_PCO_B_Content,
    CCN_PCO_E_Content,
    CCN_PCO_E
};

struct ccn_parsed_ContentObject {
    int magic;
    int name_ncomps;
    unsigned short offset[CCN_PCO_E+1];
    unsigned char digest[32];
    int digest_bytes;
};

/*
 * ccn_parse_ContentObject:
 * Returns 0, or a negative value for an error.
 * Fills in *x with offsets of constituent elements.
 * If components is not NULL, it is filled with byte indexes
 * of the start of each Component of the Name of the ContentObject,
 * plus one additional value for the index of the end of the last component.
 * Sets x->digest_bytes to 0; the digest is computed lazily by calling
 * ccn_digest_ContentObject.
 */
int ccn_parse_ContentObject(const unsigned char *msg, size_t size,
                   struct ccn_parsed_ContentObject *x,
                   struct ccn_indexbuf *components);

void ccn_digest_ContentObject(const unsigned char *msg,
                              struct ccn_parsed_ContentObject *pc);

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

/*
 * ccn_name_comp_get: return a pointer to and size of component at
 * given index i.  The first component is index 0.
 */
int
ccn_name_comp_get(const unsigned char *data,
                  const struct ccn_indexbuf *indexbuf,
                  unsigned int i,
                  const unsigned char **comp, size_t *size)

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
 *    SignedInfo: encoded info from ccn_signed_info_create
 *    data, size: the raw data to be encoded
 *    digest_algorithm: to be used for signing
 *    private_key: to be used for signing
 */

int ccn_encode_ContentObject(struct ccn_charbuf *buf,
                             const struct ccn_charbuf *Name,
                             const struct ccn_charbuf *SignedInfo,
                             const void *data,
                             size_t size,
                             const char *digest_algorithm,
                             const void *private_key);

/*
 * ccn_encode_Content:
 *    buf: output buffer where encoded object is written
 *    data: raw data
 *    size: size of raw data
 */

int ccn_encode_Content(struct ccn_charbuf *buf,
			     const void *data,
			     size_t size);

const char *ccn_content_name(enum ccn_content_type type);

/*
 * ccn_content_matches_interest: Test for a match
 * Return 1 if the ccnb-encoded content_object matches the 
 * ccnb-encoded interest_msg, otherwise 0.
 * The implicit_content_digest boolean says whether or not the
 * final name component is implicit (as in the on-wire format)
 * or explicit (as within ccnd's content store).
 * Valid parse information (pc and pi) may be provided to speed things
 * up; if NULL they will be reconstructed internally.
 */
int ccn_content_matches_interest(const unsigned char *content_object,
                                 size_t content_object_size,
                                 int implicit_content_digest,
                                 struct ccn_parsed_ContentObject *pc,
                                 const unsigned char *interest_msg,
                                 size_t interest_msg_size,
                                 const struct ccn_parsed_interest *pi);

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
 * ccn_charbuf_append_closer: append a CCN_CLOSE
 * Return value is 0, or -1 for error.
 */
int ccn_charbuf_append_closer(struct ccn_charbuf *c);

/*
 * ccn_charbuf_append_non_negative_integer: append a non-negative integer
 * as a UDATA for its string representation length, and the string for the
 * integer value itself.
 * Return value is 0, or -1 for error.
 */
int ccn_charbuf_append_non_negative_integer(struct ccn_charbuf *c, int nni);

#endif
