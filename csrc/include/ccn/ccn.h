/**
 * @file ccn/ccn.h
 *
 * This is the low-level interface for CCNx clients.
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

#ifndef CCN_CCN_DEFINED
#define CCN_CCN_DEFINED

#include <stdint.h>
#include <ccn/coding.h>
#include <ccn/charbuf.h>
#include <ccn/indexbuf.h>

/**
 * A macro that clients may use to cope with an evolving API.
 *
 * The decimal digits of this use the pattern MMVVXXX, where MM is the
 * major release number and VV is the minor version level.
 * XXX will be bumped when an API change is made, but it will not be
 * directly tied to the patch level in a release number.
 * Thus CCN_API_VERSION=1000 would have corresponded to the first public
 * release (0.1.0), but that version did not have this macro defined.
 */
#define CCN_API_VERSION 7001

/**
 * Interest lifetime default.
 *
 * If the interest lifetime is not explicit, this is the default value.
 */
#define CCN_INTEREST_LIFETIME_SEC 4
#define CCN_INTEREST_LIFETIME_MICROSEC (CCN_INTEREST_LIFETIME_SEC * 1000000)

/* opaque declarations */
struct ccn;
struct ccn_pkey;

/* forward declarations */
struct ccn_closure;
struct ccn_upcall_info;
struct ccn_parsed_interest;
struct ccn_parsed_ContentObject;
struct ccn_parsed_Link;

/*
 * Types for implementing upcalls
 * To receive notifications of incoming interests and content, the
 * client creates closures (using client-managed memory).
 */

/**
 * This tells what kind of event the upcall is handling.
 *
 * The KEYMISSING and RAW codes are used only if deferred verification has been
 * requested.
 */
enum ccn_upcall_kind {
    CCN_UPCALL_FINAL,             /**< handler is about to be deregistered */
    CCN_UPCALL_INTEREST,          /**< incoming interest */
    CCN_UPCALL_CONSUMED_INTEREST, /**< incoming interest, someone has answered */
    CCN_UPCALL_CONTENT,           /**< incoming verified content */
    CCN_UPCALL_INTEREST_TIMED_OUT,/**< interest timed out */
    CCN_UPCALL_CONTENT_UNVERIFIED,/**< content that has not been verified */
    CCN_UPCALL_CONTENT_BAD,       /**< verification failed */
    CCN_UPCALL_CONTENT_KEYMISSING,/**< key has not been fetched */
    CCN_UPCALL_CONTENT_RAW        /**< verification has not been attempted */
};

/**
 * Upcalls return one of these values.
 */
enum ccn_upcall_res {
    CCN_UPCALL_RESULT_ERR = -1, /**< upcall detected an error */
    CCN_UPCALL_RESULT_OK = 0,   /**< normal upcall return */
    CCN_UPCALL_RESULT_REEXPRESS = 1, /**< reexpress the same interest again */
    CCN_UPCALL_RESULT_INTEREST_CONSUMED = 2,/**< upcall claims to consume interest */
    CCN_UPCALL_RESULT_VERIFY = 3, /**< force an unverified result to be verified */
    CCN_UPCALL_RESULT_FETCHKEY = 4 /**< request fetching of an unfetched key */
};

/**
 * @type ccn_handler
 * This is the procedure type for the closure's implementation.
 */
typedef enum ccn_upcall_res (*ccn_handler)(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info  /**< details about the event */
);

/**
 * Handle for upcalls that allow clients receive notifications of
 * incoming interests and content.
 *
 * The client is responsible for managing this piece of memory and the
 * data therein. The refcount should be initially zero, and is used by the
 * library to keep to track of multiple registrations of the same closure.
 * When the count drops back to 0, the closure will be called with
 * kind = CCN_UPCALL_FINAL so that it has an opportunity to clean up.
 */
struct ccn_closure {
    ccn_handler p;      /**< client-supplied handler */
    void *data;         /**< for client use */
    intptr_t intdata;   /**< for client use */
    int refcount;       /**< client should not update this directly */
};

/**
 * Additional information provided in the upcall.
 *
 * The client is responsible for managing this piece of memory and the
 * data therein. The refcount should be initially zero, and is used by the
 * library to keep to track of multiple registrations of the same closure.
 * When the count drops back to 0, the closure will be called with
 * kind = CCN_UPCALL_FINAL so that it has an opportunity to clean up.
 */
struct ccn_upcall_info {
    struct ccn *h;              /**< The ccn library handle */
    /* Interest (incoming or matched) */
    const unsigned char *interest_ccnb;
    struct ccn_parsed_interest *pi;
    struct ccn_indexbuf *interest_comps;
    int matched_comps;
    /* Incoming content for CCN_UPCALL_CONTENT* - otherwise NULL */
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
 * file descriptor, e.g. for use in select/poll.
 * The client should not use this fd for actual I/O.
 * Normal return value is the fd for the connection.
 * Returns -1 if the handle is not connected.
 */ 
int ccn_get_connection_fd(struct ccn *h);

/*
 * ccn_disconnect: disconnect from local ccnd
 * This breaks the connection and discards buffered I/O,
 * but leaves other state intact.  Interests that are pending at disconnect
 * will be reported as timed out, and interest filters active at disconnect
 * will be re-registered if a subsequent ccn_connect on the handle succeeds.
 */ 
int ccn_disconnect(struct ccn *h);

/*
 * ccn_destroy: destroy handle
 * Releases all resources associated with *hp and sets it to NULL.
 */ 
void ccn_destroy(struct ccn **hp);

/* Control where verification happens */
int ccn_defer_verification(struct ccn *h, int defer);

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

enum ccn_marker {
    CCN_MARKER_NONE = -1,
    CCN_MARKER_SEQNUM  = 0x00, /**< consecutive block sequence numbers */
    CCN_MARKER_CONTROL = 0xC1, /**< commands, etc. */ 
    CCN_MARKER_OSEQNUM = 0xF8, /**< deprecated */
    CCN_MARKER_BLKID   = 0xFB, /**< nonconsecutive block ids */
    CCN_MARKER_VERSION = 0xFD  /**< timestamp-based versioning */
};

/*
 * ccn_name_append_numeric: add binary Component to ccnb-encoded Name
 * These are special components used for marking versions, fragments, etc.
 * Return value is 0, or -1 for error
 * see doc/technical/NameConventions.html
 */
int ccn_name_append_numeric(struct ccn_charbuf *c,
                            enum ccn_marker tag, uintmax_t value);

/*
 * ccn_name_append_nonce: add nonce Component to ccnb-encoded Name
 * Uses %C1.N.n marker.
 * see doc/technical/NameConventions.html
 */
int ccn_name_append_nonce(struct ccn_charbuf *c);

/*
 * ccn_name_split: find Component boundaries in a ccnb-encoded Name
 * Thin veneer over ccn_parse_Name().
 * returns -1 for error, otherwise the number of Components
 * components arg may be NULL to just do a validity check
 */
int ccn_name_split(const struct ccn_charbuf *c,
                   struct ccn_indexbuf* components);

/*
 * ccn_name_chop: Chop the name down to n components.
 * returns -1 for error, otherwise the new number of Components
 * components arg may be NULL; if provided it must be consistent with
 * some prefix of the name, and is updated accordingly.
 * n may be negative to say how many components to remove instead of how
 * many to leave, e.g. -1 will remove just the last component.
 */
int ccn_name_chop(struct ccn_charbuf *c,
                  struct ccn_indexbuf* components, int n);


/***********************************
 * Authenticators and signatures for content are constructed in charbufs
 * using the following routines.
 */

enum ccn_content_type {
    CCN_CONTENT_DATA = 0x0C04C0,
    CCN_CONTENT_ENCR = 0x10D091,
    CCN_CONTENT_GONE = 0x18E344,
    CCN_CONTENT_KEY  = 0x28463F,
    CCN_CONTENT_LINK = 0x2C834A,
    CCN_CONTENT_NACK = 0x34008A
};

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
                         struct ccn_closure *action,
                         struct ccn_charbuf *interest_template);

/*
 * Register to receive interests on a prefix
 */
int ccn_set_interest_filter(struct ccn *h, struct ccn_charbuf *namebuf,
                            struct ccn_closure *action);

/*
 * Variation allows non-default forwarding flags
 */
int ccn_set_interest_filter_with_flags(struct ccn *h,
                                       struct ccn_charbuf *namebuf,
                                       struct ccn_closure *action,
                                       int forw_flags);

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

/*
 * ccn_get: Get a single matching ContentObject
 * This is a convenience for getting a single matching ContentObject.
 * Blocks until a matching ContentObject arrives or there is a timeout.
 * If h is NULL or ccn_get is called from inside an upcall, a new connection
 * will be used and upcalls from other requests will not be processed while
 * ccn_get is active.
 * The pcobuf and compsbuf arguments may be supplied to save the work of
 * re-parsing the ContentObject.  Either or both may be NULL if this
 * information is not actually needed.
 * flags are not currently used, should be 0.
 * Returns 0 for success, -1 for an error.
 */
int ccn_get(struct ccn *h,
            struct ccn_charbuf *name,
            struct ccn_charbuf *interest_template,
            int timeout_ms,
            struct ccn_charbuf *resultbuf,
            struct ccn_parsed_ContentObject *pcobuf,
            struct ccn_indexbuf *compsbuf,
            int flags);

#define CCN_GET_NOKEYWAIT 1

/* Handy if the content object didn't arrive in the usual way. */
int ccn_verify_content(struct ccn *h,
                       const unsigned char *msg,
                       struct ccn_parsed_ContentObject *pco);

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
/* XXX - note, ccn_buf_match_blob doesn't match - it extracts the blob! */
int ccn_buf_match_dtag(struct ccn_buf_decoder *d, enum ccn_dtag dtag);

int ccn_buf_match_some_dtag(struct ccn_buf_decoder *d);

int ccn_buf_match_some_blob(struct ccn_buf_decoder *d);
int ccn_buf_match_blob(struct ccn_buf_decoder *d,
                       const unsigned char **bufp, size_t *sizep);

int ccn_buf_match_udata(struct ccn_buf_decoder *d, const char *s);

int ccn_buf_match_attr(struct ccn_buf_decoder *d, const char *s);

/* On error, the parse routines enter an error state and return a negative value. */
int ccn_parse_required_tagged_BLOB(struct ccn_buf_decoder *d,
                                   enum ccn_dtag dtag,
                                   int minlen, int maxlen);
int ccn_parse_optional_tagged_BLOB(struct ccn_buf_decoder *d,
                                   enum ccn_dtag dtag,
                                   int minlen, int maxlen);
int ccn_parse_nonNegativeInteger(struct ccn_buf_decoder *d);
int ccn_parse_optional_tagged_nonNegativeInteger(struct ccn_buf_decoder *d,
                                                 enum ccn_dtag dtag);
int ccn_parse_uintmax(struct ccn_buf_decoder *d, uintmax_t *result);
int ccn_parse_tagged_string(struct ccn_buf_decoder *d,
                            enum ccn_dtag dtag, struct ccn_charbuf *store);
/* check the decoder error state for these two - result can't be negative */
uintmax_t ccn_parse_required_tagged_binary_number(struct ccn_buf_decoder *d,
                                                  enum ccn_dtag dtag,
                                                  int minlen, int maxlen);
uintmax_t ccn_parse_optional_tagged_binary_number(struct ccn_buf_decoder *d,
                                                  enum ccn_dtag dtag,
                                                  int minlen, int maxlen,
                                                  uintmax_t default_value);
/**
 * Enter an error state if element closer not found.
 */
void ccn_buf_check_close(struct ccn_buf_decoder *d);

/*
 * ccn_ref_tagged_BLOB: Get address & size associated with blob-valued element
 * Returns 0 for success, negative value for error.
 */
int ccn_ref_tagged_BLOB(enum ccn_dtag tt,
                        const unsigned char *buf,
                        size_t start, size_t stop,
                        const unsigned char **presult, size_t *psize);

/*
 * ccn_ref_tagged_string: Get address & size associated with
 * string(UDATA)-valued element.   Note that since the element closer
 * is a 0 byte, the string result will be correctly interpreted as a C string.
 * Returns 0 for success, negative value for error.
 */
int ccn_ref_tagged_string(enum ccn_dtag tt,
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
 * to beginning offsets and the *_E_* indices correspond to ending offsets.
 * An omitted element has its beginning and ending offset equal to each other.
 * Normally these offsets will end up in non-decreasing order.
 * Some aliasing tricks may be played here, e.g. since
 * offset[CCN_PI_E_ComponentLast] is always equal to
 * offset[CCN_PI_E_LastPrefixComponent],
 * we may define CCN_PI_E_ComponentLast = CCN_PI_E_LastPrefixComponent.
 * However, code should not rely on that,
 * since it may change from time to time as the
 * interest schema evolves.
 */
enum ccn_parsed_interest_offsetid {
    CCN_PI_B_Name,
    CCN_PI_B_Component0,
    CCN_PI_B_LastPrefixComponent,
    CCN_PI_E_LastPrefixComponent,
    CCN_PI_E_ComponentLast = CCN_PI_E_LastPrefixComponent,
    CCN_PI_E_Name,
    CCN_PI_B_MinSuffixComponents,
    CCN_PI_E_MinSuffixComponents,
    CCN_PI_B_MaxSuffixComponents,
    CCN_PI_E_MaxSuffixComponents,
    CCN_PI_B_PublisherID, // XXX - rename
    CCN_PI_B_PublisherIDKeyDigest,
    CCN_PI_E_PublisherIDKeyDigest,
    CCN_PI_E_PublisherID,
    CCN_PI_B_Exclude,
    CCN_PI_E_Exclude,
    CCN_PI_B_ChildSelector,
    CCN_PI_E_ChildSelector,
    CCN_PI_B_AnswerOriginKind,
    CCN_PI_E_AnswerOriginKind,
    CCN_PI_B_Scope,
    CCN_PI_E_Scope,
    CCN_PI_B_InterestLifetime,
    CCN_PI_E_InterestLifetime,
    CCN_PI_B_Nonce,
    CCN_PI_E_Nonce,
    CCN_PI_B_OTHER,
    CCN_PI_E_OTHER,
    CCN_PI_E
};

struct ccn_parsed_interest {
    int magic;
    int prefix_comps;
    int min_suffix_comps;
    int max_suffix_comps;
    int orderpref;
    int answerfrom;
    int scope;
    unsigned short offset[CCN_PI_E+1];
};

enum ccn_parsed_Link_offsetid {
    CCN_PL_B_Name,
    CCN_PL_B_Component0,
    CCN_PL_E_ComponentLast,
    CCN_PL_E_Name,
    CCN_PL_B_Label,
    CCN_PL_E_Label,
    CCN_PL_B_LinkAuthenticator,
    CCN_PL_B_PublisherID,
    CCN_PL_B_PublisherDigest,
    CCN_PL_E_PublisherDigest,
    CCN_PL_E_PublisherID,
    CCN_PL_B_NameComponentCount,
    CCN_PL_E_NameComponentCount,
    CCN_PL_B_Timestamp,
    CCN_PL_E_Timestamp,
    CCN_PL_B_Type,
    CCN_PL_E_Type,
    CCN_PL_B_ContentDigest,
    CCN_PL_E_ContentDigest,
    CCN_PL_E_LinkAuthenticator,
    CCN_PL_E
};

struct ccn_parsed_Link {
    int name_ncomps;
    int name_component_count;
    int publisher_digest_type;
    int type;
    unsigned short offset[CCN_PL_E+1];
};

/*
 * ccn_parse_Link:
 * Returns number of name components, or a negative value for an error.
 * Fills in *link.
 * If components is not NULL, it is filled with byte indexes of
 * the start of each Component of the Name of the Link,
 * plus one additional value for the index of the end of the last component.
 */
int
ccn_parse_Link(struct ccn_buf_decoder *d,
                   struct ccn_parsed_Link *link,
                   struct ccn_indexbuf *components);

/*
 * ccn_append_Link: TODO: fill in documentation
 */
int
ccnb_append_Link(struct ccn_charbuf *buf,
                 const struct ccn_charbuf *name,
                 const char *label,
                 const struct ccn_charbuf *linkAuthenticator
                 );

/*
 * ccn_parse_LinkAuthenticator:
 */
int
ccn_parse_LinkAuthenticator(struct ccn_buf_decoder *d,
               struct ccn_parsed_Link *link);

/*
 * ccn_parse_Collection_start: TODO: fill in documentation
 */

int
ccn_parse_Collection_start(struct ccn_buf_decoder *d);

/*
 * ccn_parse_Collection_next: TODO: fill in documentation
 */

int
ccn_parse_Collection_next(struct ccn_buf_decoder *d,
                          struct ccn_parsed_Link *link,
                          struct ccn_indexbuf *components);

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

/*
 * Returns the lifetime of the interest in units of 2**(-12) seconds
 * (the same units as timestamps).
 */
intmax_t ccn_interest_lifetime(const unsigned char *msg,
                               const struct ccn_parsed_interest *pi);
/*
 * As above, but result is in seconds.  Any fractional part is truncated, so
 * this is not useful for short-lived interests.
 */
int ccn_interest_lifetime_seconds(const unsigned char *msg,
                                  const struct ccn_parsed_interest *pi);

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
    CCN_PCO_B_PublisherPublicKeyDigest,
    CCN_PCO_E_PublisherPublicKeyDigest,
    CCN_PCO_B_Timestamp,
    CCN_PCO_E_Timestamp,
    CCN_PCO_B_Type,
    CCN_PCO_E_Type,
    CCN_PCO_B_FreshnessSeconds,
    CCN_PCO_E_FreshnessSeconds,
    CCN_PCO_B_FinalBlockID,
    CCN_PCO_E_FinalBlockID,
    CCN_PCO_B_KeyLocator,
    /* Exactly one of Key, Certificate, or KeyName will be present */
    CCN_PCO_B_Key_Certificate_KeyName,
    CCN_PCO_B_KeyName_Name,
    CCN_PCO_E_KeyName_Name,
    CCN_PCO_B_KeyName_Pub,
    CCN_PCO_E_KeyName_Pub,
    CCN_PCO_E_Key_Certificate_KeyName,
    CCN_PCO_E_KeyLocator,
    CCN_PCO_B_ExtOpt,
    CCN_PCO_E_ExtOpt,
    CCN_PCO_E_SignedInfo,
    CCN_PCO_B_Content,
    CCN_PCO_E_Content,
    CCN_PCO_E
};

struct ccn_parsed_ContentObject {
    int magic;
    enum ccn_content_type type;
    int name_ncomps;
    unsigned short offset[CCN_PCO_E+1];
    unsigned char digest[32];	/* Computed only when needed */
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
 * ccn_parse_Name: Parses a ccnb-encoded name
 * components may be NULL, otherwise is filled in with Component boundary offsets
 * Returns the number of Components in the Name, or -1 if there is an error.
 */
int ccn_parse_Name(struct ccn_buf_decoder *d, struct ccn_indexbuf *components);

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
 * Names may be (minimally) read using the following routines,
 * based on the component boundary markers generated from a parse.
 */

/*
 * ccn_indexbuf_comp_strcmp: perform strcmp of given val against 
 * name component at given index i (counting from 0).
 * Uses conventional string ordering, not the canonical CCNx ordering.
 * Returns negative, 0, or positive if val is less than, equal to,
 * or greater than the component.
 * Safe even on binary components, though the result may not be useful.
 * NOTE - this ordering is different from the canonical ordering
 * used by ccn_compare_names();
 */
int ccn_name_comp_strcmp(const unsigned char *data,
                         const struct ccn_indexbuf *indexbuf,
                         unsigned int i,
                         const char *val);

/*
 * ccn_name_comp_get: return a pointer to and size of component at
 * given index i.  The first component is index 0.
 */
int ccn_name_comp_get(const unsigned char *data,
                      const struct ccn_indexbuf *indexbuf,
                      unsigned int i,
                      const unsigned char **comp, size_t *size);

int ccn_name_next_sibling(struct ccn_charbuf *c);

/***********************************
 * Reading content objects
 */

int ccn_content_get_value(const unsigned char *data, size_t data_size,
                          const struct ccn_parsed_ContentObject *content,
                          const unsigned char **value, size_t *size);

/* checking for final block given upcall info */
int ccn_is_final_block(struct ccn_upcall_info *info);

/* checking for final block given parsed content object */
int ccn_is_final_pco(const unsigned char *ccnb,
                     struct ccn_parsed_ContentObject *pco,
                     struct ccn_indexbuf *comps);

/* content-object signing */

/**
 * Parameters for creating signed content objects.
 *
 * A pointer to one of these may be passed to ccn_sign_content() for
 * cases where the default signing behavior does not suffice.
 * For the default (sign with the user's default key pair), pass NULL
 * for the pointer.
 *
 * The recommended way to us this is to create a local variable:
 *
 *   struct ccn_signing_params myparams = CCN_SIGNING_PARAMS_INIT;
 *
 * and then fill in the desired fields.  This way if additional parameters
 * are added, it won't be necessary to go back and modify exiting clients.
 * 
 * The template_ccnb may contain a ccnb-encoded SignedInfo to supply
 * selected fields from under the direction of sp_flags.
 * It is permitted to omit unneeded fields from the template, even if the
 * schema says they are manditory.
 *
 * If the pubid is all zero, the user's default key pair is used for
 * signing.  Otherwise the corresponding private key must have already
 * been supplied to the handle using ccn_load_private_key() or equivalent.
 *
 * The default signing key is obtained from ~/.ccnx/.ccnx_keystore unless
 * the CCNX_DIR is used to override the directory location.
 */
 
struct ccn_signing_params {
    int api_version;
    int sp_flags;
    struct ccn_charbuf *template_ccnb;
    unsigned char pubid[32];
    enum ccn_content_type type;
    int freshness;
    // XXX where should digest_algorithm fit in?
};

#define CCN_SIGNING_PARAMS_INIT \
  { CCN_API_VERSION, 0, NULL, {0}, CCN_CONTENT_DATA, -1 }

#define CCN_SP_TEMPL_TIMESTAMP      0x0001
#define CCN_SP_TEMPL_FINAL_BLOCK_ID 0x0002
#define CCN_SP_TEMPL_FRESHNESS      0x0004
#define CCN_SP_TEMPL_KEY_LOCATOR    0x0008
#define CCN_SP_FINAL_BLOCK          0x0010
#define CCN_SP_OMIT_KEY_LOCATOR     0x0020
#define CCN_SP_TEMPL_EXT_OPT        0x0040

int ccn_sign_content(struct ccn *h,
                     struct ccn_charbuf *resultbuf,
                     const struct ccn_charbuf *name_prefix,
                     const struct ccn_signing_params *params,
                     const void *data, size_t size);

int ccn_load_private_key(struct ccn *h,
                         const char *keystore_path,
                         const char *keystore_passphrase,
                         struct ccn_charbuf *pubid_out);

int ccn_load_default_key(struct ccn *h,
                         const char *keystore_path,
                         const char *keystore_passphrase);

int ccn_get_public_key(struct ccn *h,
                       const struct ccn_signing_params *params,
                       struct ccn_charbuf *digest_result,
                       struct ccn_charbuf *result);

int ccn_chk_signing_params(struct ccn *h,
                           const struct ccn_signing_params *params,
                           struct ccn_signing_params *result,
                           struct ccn_charbuf **ptimestamp,
                           struct ccn_charbuf **pfinalblockid,
                           struct ccn_charbuf **pkeylocator,
                           struct ccn_charbuf **pextopt);

/* low-level content-object signing */

#define CCN_SIGNING_DEFAULT_DIGEST_ALGORITHM "SHA256"

int ccn_signed_info_create(
    struct ccn_charbuf *c,              /* filled with result */
    const void *publisher_key_id,	/* input, (sha256) hash */
    size_t publisher_key_id_size, 	/* input, 32 for sha256 hashes */
    const struct ccn_charbuf *timestamp,/* input ccnb blob, NULL for "now" */
    enum ccn_content_type type,         /* input */
    int freshness,			/* input, -1 means omit */
    const struct ccn_charbuf *finalblockid, /* input, NULL means omit */
    const struct ccn_charbuf *key_locator); /* input, optional, ccnb encoded */

int ccn_encode_ContentObject(struct ccn_charbuf *buf,
                             const struct ccn_charbuf *Name,
                             const struct ccn_charbuf *SignedInfo,
                             const void *data,
                             size_t size,
                             const char *digest_algorithm,
                             const struct ccn_pkey *private_key);

/***********************************
 * Matching
 */


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

/*
 * Test whether the given raw name is int the Exclude set.
 */
int ccn_excluded(const unsigned char *excl,
                 size_t excl_size,
                 const unsigned char *nextcomp,
                 size_t nextcomp_size);

/***********************************
 * StatusResponse
 */
int ccn_encode_StatusResponse(struct ccn_charbuf *buf,
                              int errcode, const char *errtext);

/***********************************
 * Debugging
 */

/*
 * ccn_perror: produce message on standard error output describing the last
 * error encountered during a call using the given handle.
 * ccn_seterror records error info, ccn_geterror gets it.
 */
void ccn_perror(struct ccn *h, const char *s);
int ccn_seterror(struct ccn *h, int error_code);
int ccn_geterror(struct ccn *h);

/***********************************
 * Low-level binary formatting
 */

/*
 * Append a ccnb start marker
 *
 * This forms the basic building block of ccnb-encoded data.
 * c is the buffer to append to.
 * Return value is 0, or -1 for error.
 */
int ccn_charbuf_append_tt(struct ccn_charbuf *c, size_t val, enum ccn_tt tt);

/**
 * Append a CCN_CLOSE
 *
 * Use this to close off an element in ccnb-encoded data.
 * @param c is the buffer to append to.
 * @returns 0 for success or -1 for error.
 */
int ccn_charbuf_append_closer(struct ccn_charbuf *c);

/***********************************
 * Slightly higher level binary formatting
 */

/*
 * Append a non-negative integer as a UDATA.
 */
int ccnb_append_number(struct ccn_charbuf *c, int nni);

/*
 * Append a binary timestamp
 * as a BLOB using the ccn binary Timestamp representation (12-bit fraction).
 */
int ccnb_append_timestamp_blob(struct ccn_charbuf *c,
                               enum ccn_marker marker,
                               intmax_t secs, int nsecs);

/*
 * Append a binary timestamp, using the current time.
 */
int ccnb_append_now_blob(struct ccn_charbuf *c, enum ccn_marker marker);

/*
 * Append a start-of-element marker.
 */
int ccnb_element_begin(struct ccn_charbuf *c, enum ccn_dtag dtag);

/*
 * Append an end-of-element marker.
 * This is the same as ccn_charbuf_append_closer()
 */
int ccnb_element_end(struct ccn_charbuf *c);

/*
 * Append a tagged BLOB
 */
int ccnb_append_tagged_blob(struct ccn_charbuf *c, enum ccn_dtag dtag,
                            const void *data, size_t size);

/*
 * Append a tagged binary number
 */
int ccnb_append_tagged_binary_number(struct ccn_charbuf *cb, enum ccn_dtag dtag,
                                      uintmax_t val);

/*
 * Append a tagged UDATA string, with printf-style formatting
 */
int ccnb_tagged_putf(struct ccn_charbuf *c, enum ccn_dtag dtag,
                     const char *fmt, ...);

/**
 * Versioning
 */

/* Not all of these flags make sense with all of the operations */
#define CCN_V_REPLACE  1 /**< if last component is version, replace it */
#define CCN_V_LOW      2 /**< look for early version */
#define CCN_V_HIGH     4 /**< look for newer version */
#define CCN_V_EST      8 /**< look for extreme */
#define CCN_V_LOWEST   (2|8)
#define CCN_V_HIGHEST  (4|8)
#define CCN_V_NEXT     (4|1)
#define CCN_V_PREV     (2|1)
#define CCN_V_NOW      16 /**< use current time */
#define CCN_V_NESTOK   32 /**< version within version is ok */
#define CCN_V_SCOPE0   64 /**< use scope 0 */
#define CCN_V_SCOPE1   128 /**< use scope 1 */
#define CCN_V_SCOPE2   256 /**< use scope 2 */

int ccn_resolve_version(struct ccn *h,
                        struct ccn_charbuf *name, /* ccnb encoded */
                        int versioning_flags,
                        int timeout_ms);

int ccn_create_version(struct ccn *h,
                       struct ccn_charbuf *name,
                       int versioning_flags,
                       intmax_t secs, int nsecs);

int ccn_guest_prefix(struct ccn *h, struct ccn_charbuf *result, int ms);

#endif
