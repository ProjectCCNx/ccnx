/**
 * @file ccnd_private.h
 *
 * Private definitions for ccnd - the CCNx daemon.
 * Data structures are described here so that logging and status
 * routines can be compiled separately.
 *
 * Part of ccnd - the CCNx Daemon.
 */
/*
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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
 
#ifndef CCND_PRIVATE_DEFINED
#define CCND_PRIVATE_DEFINED

#include <poll.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/types.h>

#include <ccn/ccn_private.h>
#include <ccn/coding.h>
#include <ccn/nametree.h>
#include <ccn/reg_mgmt.h>
#include <ccn/schedule.h>
#include <ccn/seqwriter.h>

/* For strategy API */
#include "ccnd_strategy.h"

/*
 * These are defined in other ccn headers, but the incomplete types suffice
 * for the purposes of this header.
 */
struct ccn_charbuf;
struct ccn_indexbuf;
struct hashtb;
struct ccnd_meter;

/*
 * These are defined in this header.
 */
struct ccnd_handle;
struct face;
struct content_entry;
struct nameprefix_entry;
struct interest_entry;
struct guest_entry;
struct ccn_forwarding;
typedef int (*ccnd_logger)(void *loggerdata, const char *format, va_list ap);

/* see nonce_entry */
struct ncelinks {
    struct ncelinks *next;           /**< next in list */
    struct ncelinks *prev;           /**< previous in list */
};

/**
 * We pass this handle almost everywhere within ccnd
 */
struct ccnd_handle {
    unsigned char ccnd_id[32];      /**< sha256 digest of our public key */
    struct hashtb *nonce_tab;       /**< keyed by interest Nonce */
    struct hashtb *faces_by_fd;     /**< keyed by fd */
    struct hashtb *dgram_faces;     /**< keyed by sockaddr */
    struct hashtb *faceid_by_guid;  /**< keyed by guid */
    struct hashtb *nameprefix_tab;  /**< keyed by name prefix components */
    struct hashtb *interest_tab;    /**< keyed by interest msg sans Nonce */
    struct hashtb *guest_tab;       /**< keyed by faceid */
    struct hashtb *faceattr_index_tab; /**< keyed by faceattr name */
    unsigned faceattr_packed;       /**< Allocation mask for first 32 */
    int nlfaceattr;                  /**< number of large face attributes */
    unsigned forward_to_gen;        /**< for forward_to updates */
    unsigned face_gen;              /**< faceid generation number */
    unsigned face_rover;            /**< for faceid allocation */
    unsigned face_limit;            /**< current number of face slots */
    struct face **faces_by_faceid;  /**< array with face_limit elements */
    struct ncelinks ncehead;        /**< list head for expiry-sorted nonces */
    struct ccn_scheduled_event *reaper;
    struct ccn_scheduled_event *age;
    struct ccn_scheduled_event *clean;
    struct ccn_scheduled_event *age_forwarding;
    const char *portstr;            /**< "main" port number */
    unsigned ipv4_faceid;           /**< wildcard IPv4, bound to port */
    unsigned ipv6_faceid;           /**< wildcard IPv6, bound to port */
    nfds_t nfds;                    /**< number of entries in fds array */
    struct pollfd *fds;             /**< used for poll system call */
    struct ccn_gettime ticktock;    /**< our time generator */
    long sec;                       /**< cached gettime seconds */
    unsigned usec;                  /**< cached gettime microseconds */
    ccn_wrappedtime wtnow;          /**< corresponding wrapped time */
    int sliver;                     /**< extra microseconds beyond wtnow */
    long starttime;                 /**< ccnd start time, in seconds */
    unsigned starttime_usec;        /**< ccnd start time fractional part */
    unsigned iserial;               /**< interest serial number (for logs) */
    struct ccn_schedule *sched;     /**< our schedule */
    struct ccn_charbuf *errbuf;     /**< for strategy error reporting */
    struct ccn_charbuf *send_interest_scratch; /**< for use by send_interest */
    struct ccn_charbuf *scratch_charbuf; /**< one-slot scratch cache */
    struct ccn_indexbuf *scratch_indexbuf; /**< one-slot scratch cache */
    struct ccn_nametree *content_tree; /**< content store */
    struct content_entry *headx;    /**< list head for expiry queue */
    unsigned capacity;              /**< may toss content if there more than
                                     this many content objects in the store */
    struct ccn_nametree *ex_index;  /**< for speedy adds to expiry queue */
    unsigned long accessioned;
    unsigned long oldformatcontent;
    unsigned long oldformatcontentgrumble;
    unsigned long oldformatinterests;
    unsigned long oldformatinterestgrumble;
    unsigned long content_accessions;
    unsigned long content_dups_recvd;
    unsigned long content_items_sent;
    unsigned long interests_accepted;
    unsigned long interests_dropped;
    unsigned long interests_sent;
    unsigned long interests_stuffed;
    unsigned short seed[3];         /**< for PRNG */
    int running;                    /**< true while should be running */
    int debug;                      /**< For controlling debug output */
    ccnd_logger logger;             /**< For debug output */
    void *loggerdata;               /**< Passed to logger */
    int logbreak;                   /**< see ccn_msg() */
    unsigned long logtime;          /**< see ccn_msg() */
    int logpid;                     /**< see ccn_msg() */
    int mtu;                        /**< Target size for stuffing interests */
    int flood;                      /**< Internal control for auto-reg */
    struct ccn_charbuf *autoreg;    /**< URIs to auto-register */
    unsigned interest_faceid;       /**< for self_reg internal client */
    const char *progname;           /**< our name, for locating helpers */
    struct ccn *internal_client;    /**< internal client */
    struct face *face0;             /**< special face for internal client */
    struct ccn_charbuf *service_ccnb; /**< for local service discovery */
    struct ccn_charbuf *neighbor_ccnb; /**< for neighbor service discovery */
    struct ccn_seqwriter *notice;   /**< for notices of status changes */
    struct ccn_indexbuf *chface;    /**< faceids w/ recent status changes */
    struct ccn_scheduled_event *internal_client_refresh;
    struct ccn_scheduled_event *notice_push;
    unsigned data_pause_microsec;   /**< tunable, see choose_face_delay() */
    int (*noncegen)(struct ccnd_handle *, struct face *, unsigned char *);
                                    /**< pluggable nonce generation */
    int tts_default;                /**< CCND_DEFAULT_TIME_TO_STALE (seconds) */
    int tts_limit;                  /**< CCND_MAX_TIME_TO_STALE (seconds) */
    int predicted_response_limit;   /**< CCND_MAX_RTE_MICROSEC */
};

/**
 * Each face is referenced by a number, the faceid.  The low-order
 * bits (under the MAXFACES) constitute a slot number that is
 * unique (for this ccnd) among the faces that are alive at a given time.
 * The rest of the bits form a generation number that make the
 * entire faceid unique over time, even for faces that are defunct.
 */
#define FACESLOTBITS 18
#define MAXFACES ((1U << FACESLOTBITS) - 1)

struct content_queue {
    unsigned burst_nsec;             /**< nsec per KByte, limits burst rate */
    unsigned min_usec;               /**< minimum delay for this queue */
    unsigned rand_usec;              /**< randomization range */
    unsigned ready;                  /**< # that have waited enough */
    unsigned nrun;                   /**< # sent since last randomized delay */
    struct ccn_indexbuf *send_queue; /**< accession numbers of pending content */
    struct ccn_scheduled_event *sender;
};

enum cq_delay_class {
    CCN_CQ_ASAP,
    CCN_CQ_NORMAL,
    CCN_CQ_SLOW,
    CCN_CQ_N
};

/**
 * Face meter index
 */
enum ccnd_face_meter_index {
    FM_BYTI,
    FM_BYTO,
    FM_DATI,
    FM_INTO,
    FM_DATO,
    FM_INTI,
    CCND_FACE_METER_N
};

/**
 * One of our active faces
 */
struct face {
    int recv_fd;                /**< socket for receiving */
    unsigned sendface;          /**< faceid for sending (maybe == faceid) */
    int flags;                  /**< CCN_FACE_* face flags */
    int surplus;                /**< sends since last successful recv */
    unsigned faceid;            /**< internal face id */
    unsigned recvcount;         /**< for activity level monitoring */
    const unsigned char *guid;  /**< guid name for channel, shared w/ peers */
    struct ccn_charbuf *guid_cob; /**< content object publishing face guid */
    struct content_queue *q[CCN_CQ_N]; /**< outgoing content, per delay class */
    struct ccn_charbuf *inbuf;
    struct ccn_skeleton_decoder decoder;
    size_t outbufindex;
    struct ccn_charbuf *outbuf;
    const struct sockaddr *addr;
    socklen_t addrlen;
    int pending_interests;      /**< received and not yet consumed */
    int outstanding_interests;  /**< sent and not yet consumed */
    unsigned rrun;
    uintmax_t rseq;
    unsigned faceattr_packed;   /**< First 32 face attributes (single bits) */
    int nlfaceattr;             /**< number of large face attributes */
    unsigned *lfaceattrs;       /**< storage for large face attributes */
    struct ccnd_meter *meter[CCND_FACE_METER_N];
    unsigned short pktseq;      /**< sequence number for sent packets */
    unsigned short adjstate;    /**< state of adjacency negotiotiation */
};

/** face flags */
#define CCN_FACE_LINK   (1 << 0) /**< Elements wrapped by CCNProtocolDataUnit */
#define CCN_FACE_DGRAM  (1 << 1) /**< Datagram interface, respect packets */
#define CCN_FACE_GG     (1 << 2) /**< Considered friendly */
#define CCN_FACE_LOCAL  (1 << 3) /**< PF_UNIX socket */
#define CCN_FACE_INET   (1 << 4) /**< IPv4 */
#define CCN_FACE_MCAST  (1 << 5) /**< a party line (e.g. multicast) */
#define CCN_FACE_INET6  (1 << 6) /**< IPv6 */
#define CCN_FACE_DC     (1 << 7) /**< Direct control face */
#define CCN_FACE_NOSEND (1 << 8) /**< Don't send anymore */
#define CCN_FACE_UNDECIDED (1 << 9) /**< Might not be talking ccn */
#define CCN_FACE_PERMANENT (1 << 10) /**< No timeout for inactivity */
#define CCN_FACE_CONNECTING (1 << 11) /**< Connect in progress */
#define CCN_FACE_LOOPBACK (1 << 12) /**< v4 or v6 loopback address */
#define CCN_FACE_CLOSING (1 << 13) /**< close stream when output is done */
#define CCN_FACE_PASSIVE (1 << 14) /**< a listener or a bound dgram socket */
#define CCN_FACE_NORECV (1 << 15) /**< use for sending only */
#define CCN_FACE_REGOK (1 << 16) /**< Allowed to do prefix registration */
#define CCN_FACE_SEQOK (1 << 17) /** OK to send SequenceNumber link messages */
#define CCN_FACE_SEQPROBE (1 << 18) /** SequenceNumber probe */
#define CCN_FACE_LC    (1 << 19) /** A link check has been issued recently */
#define CCN_FACE_BC    (1 << 20) /** Needs SO_BROADCAST to send */
#define CCN_FACE_NBC   (1 << 21) /** Don't use SO_BROADCAST to send */
#define CCN_FACE_ADJ   (1 << 22) /** Adjacency guid has been negotiatied */

/**
 *  Entry in faceattr_index_tab
 *
 * This keeps track of the index values that are used to access the per-face
 * attributes.  We use an index so that the lookup by attribute name only
 * needs to happen at setup time, rather than each time the attribute is
 * accessed.  Small values of fa_index (< 32) refer to the single-bit attributes
 * that are stored in face->faceattr_packed.  Larger values are used to
 * index into face->faceattrs (after deducting 32, of course).
 *
 * The "anonymous" attributes are stored under a name that is the decimal
 * representation of the index.  This is an implementation detail,
 * not part of the strategy API.
 */
struct faceattr_index_entry {
    int fa_index;       /**< index for accessing faceattr value */
};

/**
 * Content table entry
 *
 * The content table is built on a nametree that is keyed by the flatname
 * representation of the content name (including the implicit digest).
 */
struct content_entry {
    ccn_cookie accession;       /**< for associated nametree entry */
    unsigned arrival_faceid;    /**< the faceid of first arrival */
    short refs;                 /**< number of queues we are on */
    short ncomps;               /**< Number of name components plus one */
    int flags;                  /**< see defines below */
    unsigned char *ccnb;        /**< ccnb-encoded ContentObject */
    int size;                   /**< Size of ContentObject */
    int staletime;              /**< Time in seconds, relative to starttime */
    struct content_entry *nextx; /**< Next to expire after us */
    struct content_entry *prevx; /**< Expiry doubly linked for fast removal */
};

/**
 * content_entry flags
 */
#define CCN_CONTENT_ENTRY_SLOWSEND  1

struct ielinks;
struct ielinks {
    struct ielinks *next;           /**< next in list */
    struct ielinks *prev;           /**< previous in list */
    struct nameprefix_entry *npe;   /**< owning npe, or NULL for head */
};

/**
 * The interest hash table is keyed by the interest message
 *
 * The interest message has fields that do not participate in the
 * similarity test stripped out - in particular the nonce.
 *
 */
struct interest_entry {
    struct ielinks ll;
    struct ccn_strategy strategy;   /**< state of strategy engine */
    struct ccn_scheduled_event *stev; /**< for time-based strategy event */
    struct ccn_scheduled_event *ev; /**< next interest timeout */    
    const unsigned char *interest_msg; /**< pending interest message */
    unsigned size;                  /**< size of interest message */
    unsigned serial;                /**< used for logging */
};

/**
 * The nonce hash table is keyed by the interest nonce
 */
struct nonce_entry {
    struct ncelinks ll;             /** doubly-linked */
    const unsigned char *key;       /** owned by hashtb */
    unsigned size;                  /** size of key */
    unsigned faceid;                /** originating face */
    ccn_wrappedtime expiry;         /** when this should expire */
};

/**
 * The guest hash table is keyed by the faceid of the requestor
 *
 * The cob is an answer for the request.
 *
 */
struct guest_entry {
    struct ccn_charbuf *cob;
};

/**
 * The nameprefix hash table is keyed by the Component elements of
 * the Name prefix.
 */
struct nameprefix_entry {
    struct ielinks ie_head;      /**< list head for interest entries */
    struct ccn_indexbuf *forward_to; /**< faceids to forward to */
    struct ccn_indexbuf *tap;    /**< faceids to forward to as tap */
    struct ccn_forwarding *forwarding; /**< detailed forwarding info */
    struct nameprefix_entry *parent; /**< link to next-shorter prefix */
    int children;                /**< number of children */
    unsigned flags;              /**< CCN_FORW_* flags about namespace */
    int fgen;                    /**< to decide when cached fields are stale */
    struct strategy_instance *si;/**< explicit strategy for this prefix */
    struct nameprefix_state sst; /**< used by strategy layer */
};

/**
 * Keeps track of the faces that interests matching a given name prefix may be
 * forwarded to.
 */
struct ccn_forwarding {
    unsigned faceid;             /**< locally unique number identifying face */
    unsigned flags;              /**< CCN_FORW_* - c.f. <ccn/reg_mgnt.h> */
    int expires;                 /**< time remaining, in seconds */
    struct ccn_forwarding *next;
};

/* create and destroy procs for separately allocated meters */
struct ccnd_meter *ccnd_meter_create(struct ccnd_handle *h, const char *what);
void ccnd_meter_destroy(struct ccnd_meter **);

/* for meters kept within other structures */
void ccnd_meter_init(struct ccnd_handle *h, struct ccnd_meter *m, const char *what);

/* count something (messages, packets, bytes), getting time info from h */
void ccnd_meter_bump(struct ccnd_handle *h, struct ccnd_meter *m, unsigned amt);

unsigned ccnd_meter_rate(struct ccnd_handle *h, struct ccnd_meter *m);
uintmax_t ccnd_meter_total(struct ccnd_meter *m);


/**
 * Refer to doc/technical/Registration.txt for the meaning of these flags.
 *
 *  CCN_FORW_ACTIVE         1
 *  CCN_FORW_CHILD_INHERIT  2
 *  CCN_FORW_ADVERTISE      4
 *  CCN_FORW_LAST           8
 *  CCN_FORW_CAPTURE       16
 *  CCN_FORW_LOCAL         32
 *  CCN_FORW_TAP           64
 *  CCN_FORW_CAPTURE_OK   128
 */
#define CCN_FORW_PFXO (CCN_FORW_ADVERTISE | CCN_FORW_CAPTURE | CCN_FORW_LOCAL)
#define CCN_FORW_REFRESHED      (1 << 16) /**< private to ccnd */

/**
 * Determines how frequently we age our forwarding entries
 */
#define CCN_FWU_SECS 5

/*
 * Internal client
 * The internal client is for communication between the ccnd and other
 * components, using (of course) ccn protocols.
 */
int ccnd_init_internal_keystore(struct ccnd_handle *);
int ccnd_internal_client_start(struct ccnd_handle *);
void ccnd_internal_client_stop(struct ccnd_handle *);

/*
 * The internal client calls this with the argument portion ARG of
 * a face-creation request (/ccnx/CCNDID/newface/ARG)
 */
int ccnd_req_newface(struct ccnd_handle *h,
                     const unsigned char *msg, size_t size,
                     struct ccn_charbuf *reply_body);

/*
 * The internal client calls this with the argument portion ARG of
 * a face-destroy request (/ccnx/CCNDID/destroyface/ARG)
 */
int ccnd_req_destroyface(struct ccnd_handle *h,
                         const unsigned char *msg, size_t size,
                         struct ccn_charbuf *reply_body);

/*
 * The internal client calls this with the argument portion ARG of
 * a prefix-registration request (/ccnx/CCNDID/prefixreg/ARG)
 */
int ccnd_req_prefixreg(struct ccnd_handle *h,
                       const unsigned char *msg, size_t size,
                       struct ccn_charbuf *reply_body);

/*
 * The internal client calls this with the argument portion ARG of
 * a prefix-registration request for self (/ccnx/CCNDID/selfreg/ARG)
 */
int ccnd_req_selfreg(struct ccnd_handle *h,
                     const unsigned char *msg, size_t size,
                     struct ccn_charbuf *reply_body);

/*
 * The internal client calls this with the argument portion ARG of
 * a strategy selection request (e.g. /ccnx/CCNDID/setstrategy/ARG)
 */
int ccnd_req_strategy(struct ccnd_handle *h,
                      const unsigned char *msg, size_t size,
                      const char *action,
                      struct ccn_charbuf *reply_body);

/**
 * URIs for prefixes served by the internal client
 */
#define CCNDID_LOCAL_URI "ccnx:/%C1.M.S.localhost/%C1.M.SRV/ccnd/KEY"
#define CCNDID_NEIGHBOR_URI "ccnx:/%C1.M.S.neighborhood/%C1.M.SRV/ccnd/KEY"

/*
 * The internal client calls this with the argument portion ARG of
 * a prefix-unregistration request (/ccnx/CCNDID/unreg/ARG)
 */
int ccnd_req_unreg(struct ccnd_handle *h,
                   const unsigned char *msg, size_t size,
                   struct ccn_charbuf *reply_body);

int ccnd_reg_uri(struct ccnd_handle *h,
                 const char *uri,
                 unsigned faceid,
                 int flags,
                 int expires);

const struct strategy_class *
    strategy_class_from_id(const char *id);
struct strategy_instance *
    create_strategy_instance(struct ccnd_handle *h,
                             struct nameprefix_entry *npe,
                             const struct strategy_class *sclass,
                             const char *parameters);
struct strategy_instance *
    get_strategy_instance(struct ccnd_handle *h,
                          struct nameprefix_entry *npe);

void remove_strategy_instance(struct ccnd_handle *h,
                              struct nameprefix_entry *npe);

void ccnd_generate_face_guid(struct ccnd_handle *h, struct face *face, int size,
                             const unsigned char *lo, const unsigned char *hi);
int ccnd_set_face_guid(struct ccnd_handle *h, struct face *face,
                       const unsigned char *guid, size_t size);
void ccnd_forget_face_guid(struct ccnd_handle *h, struct face *face);
int ccnd_append_face_guid(struct ccnd_handle *h, struct ccn_charbuf *cb,
                          struct face *face);
unsigned ccnd_faceid_from_guid(struct ccnd_handle *h,
                               const unsigned char *guid, size_t size);
void ccnd_adjacency_offer_or_commit_req(struct ccnd_handle *ccnd,
                                        struct face *face);

void ccnd_internal_client_has_somthing_to_say(struct ccnd_handle *h);

struct face *ccnd_face_from_faceid(struct ccnd_handle *, unsigned);
void ccnd_face_status_change(struct ccnd_handle *, unsigned);
int ccnd_destroy_face(struct ccnd_handle *h, unsigned faceid);
void ccnd_send(struct ccnd_handle *h, struct face *face,
               const void *data, size_t size);

int ccnd_n_stale(struct ccnd_handle *h);

/* Consider a separate header for these */
int ccnd_stats_handle_http_connection(struct ccnd_handle *, struct face *);
void ccnd_msg(struct ccnd_handle *, const char *, ...);
void ccnd_debug_ccnb(struct ccnd_handle *h,
                     int lineno,
                     const char *msg,
                     struct face *face,
                     const unsigned char *ccnb,
                     size_t ccnb_size);
void ccnd_debug_content(struct ccnd_handle *h,
                        int lineno,
                        const char *msg,
                        struct face *face,
                        struct content_entry *content);
struct ccnd_handle *ccnd_create(const char *, ccnd_logger, void *);
void ccnd_run(struct ccnd_handle *h);
void ccnd_destroy(struct ccnd_handle **);
extern const char *ccnd_usage_message;

#endif
