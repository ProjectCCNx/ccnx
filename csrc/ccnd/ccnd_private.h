/**
 * @file ccnd_private.h
 *
 * Private definitions for ccnd - the CCNx daemon.
 * Data structures are described here so that logging and status
 * routines can be compiled separately.
 *
 * Part of ccnd - the CCNx Daemon.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
#include <ccn/reg_mgmt.h>
#include <ccn/schedule.h>
#include <ccn/seqwriter.h>

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
struct pit_face_item;
struct content_tree_node;
struct ccn_forwarding;
struct ccn_strategy;

//typedef uint_least64_t ccn_accession_t;
typedef unsigned ccn_accession_t;

/**
 * Used for keeping track of interest expiry.
 *
 * Modulo 2**32, time units and origin are abitrary and private.
 */
typedef uint32_t ccn_wrappedtime;

typedef int (*ccnd_logger)(void *loggerdata, const char *format, va_list ap);

/**
 * We pass this handle almost everywhere within ccnd
 */
struct ccnd_handle {
    unsigned char ccnd_id[32];      /**< sha256 digest of our public key */
    struct hashtb *faces_by_fd;     /**< keyed by fd */
    struct hashtb *dgram_faces;     /**< keyed by sockaddr */
    struct hashtb *faceid_by_guid;  /**< keyed by guid */
    struct hashtb *content_tab;     /**< keyed by portion of ContentObject */
    struct hashtb *nameprefix_tab;  /**< keyed by name prefix components */
    struct hashtb *interest_tab;    /**< keyed by interest msg sans Nonce */
    struct ccn_indexbuf *skiplinks; /**< skiplist for content-ordered ops */
    unsigned forward_to_gen;        /**< for forward_to updates */
    unsigned face_gen;              /**< faceid generation number */
    unsigned face_rover;            /**< for faceid allocation */
    unsigned face_limit;            /**< current number of face slots */
    struct face **faces_by_faceid;  /**< array with face_limit elements */
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
    struct ccn_charbuf *send_interest_scratch; /**< for use by send_interest */
    struct ccn_charbuf *scratch_charbuf; /**< one-slot scratch cache */
    struct ccn_indexbuf *scratch_indexbuf; /**< one-slot scratch cache */
    /** Next three fields are used for direct accession-to-content table */
    ccn_accession_t accession_base;
    unsigned content_by_accession_window;
    struct content_entry **content_by_accession;
    /** The following holds stragglers that would otherwise bloat the above */
    struct hashtb *sparse_straggler_tab; /* keyed by accession */
    ccn_accession_t accession;      /**< newest used accession number */
    ccn_accession_t min_stale;      /**< smallest accession of stale content */
    ccn_accession_t max_stale;      /**< largest accession of stale content */
    unsigned long capacity;         /**< may toss content if there more than
                                     this many content objects in the store */
    unsigned long n_stale;          /**< Number of stale content objects */
    struct ccn_indexbuf *unsol;     /**< unsolicited content */
    unsigned long oldformatcontent;
    unsigned long oldformatcontentgrumble;
    unsigned long oldformatinterests;
    unsigned long oldformatinterestgrumble;
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
    int force_zero_freshness;       /**< Simulate freshness=0 on all content */
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
    int pending_interests;
    unsigned rrun;
    uintmax_t rseq;
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
#define CCN_NOFACEID    (~0U)    /** denotes no face */

/**
 *  The content hash table is keyed by the initial portion of the ContentObject
 *  that contains all the parts of the complete name.  The extdata of the hash
 *  table holds the rest of the object, so that the whole ContentObject is
 *  stored contiguously.  The internal form differs from the on-wire form in
 *  that the final content-digest name component is represented explicitly,
 *  which simplifies the matching logic.
 *  The original ContentObject may be reconstructed simply by excising this
 *  last name component, which is easily located via the comps array.
 */
struct content_entry {
    ccn_accession_t accession;  /**< assigned in arrival order */
    unsigned arrival_faceid;    /**< the faceid of first arrival */
    unsigned short *comps;      /**< Name Component byte boundary offsets */
    int ncomps;                 /**< Number of name components plus one */
    int flags;                  /**< see below */
    const unsigned char *key;   /**< ccnb-encoded ContentObject */
    int key_size;               /**< Size of fragment prior to Content */
    int size;                   /**< Size of ContentObject */
    struct ccn_indexbuf *skiplinks; /**< skiplist for name-ordered ops */
};

/**
 * content_entry flags
 */
#define CCN_CONTENT_ENTRY_SLOWSEND  1
#define CCN_CONTENT_ENTRY_STALE     2
#define CCN_CONTENT_ENTRY_PRECIOUS  4

/**
 * The sparse_straggler hash table, keyed by accession, holds scattered
 * entries that would otherwise bloat the direct content_by_accession table.
 */
struct sparse_straggler_entry {
    struct content_entry *content;
};

/**
 * State for the strategy engine
 *
 * This is still quite embryonic.
 */
struct ccn_strategy {
    struct ccn_scheduled_event *ev; /**< for time-based strategy event */
    int state;
    ccn_wrappedtime birth;          /**< when interest entry was created */
    ccn_wrappedtime renewed;        /**< when interest entry was renewed */
    unsigned renewals;              /**< number of times renewed */
};

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
    struct pit_face_item *pfl;      /**< upstream and downstream faces */
    struct ccn_scheduled_event *ev; /**< next interest timeout */
    const unsigned char *interest_msg; /**< pending interest message */
    unsigned size;                  /**< size of interest message */
    unsigned serial;                /**< used for logging */
};

#define TYPICAL_NONCE_SIZE 12       /**< actual allocated size may differ */
/**
 * Per-face PIT information
 *
 * This is used to track the pending interest info that is specific to
 * a face.  The list may contain up to two entries for a given face - one
 * to track the most recent arrival on that face (the downstream), and
 * one to track the most recently sent (the upstream).
 */
struct pit_face_item {
    struct pit_face_item *next;     /**< next in list */
    unsigned faceid;                /**< face id */
    ccn_wrappedtime renewed;        /**< when entry was last refreshed */
    ccn_wrappedtime expiry;         /**< when entry expires */
    unsigned pfi_flags;             /**< CCND_PFI_x */
    unsigned char nonce[TYPICAL_NONCE_SIZE]; /**< nonce bytes */
};
#define CCND_PFI_NONCESZ  0x00FF    /**< Mask for actual nonce size */
#define CCND_PFI_UPSTREAM 0x0100    /**< Tracks upstream (sent interest) */
#define CCND_PFI_UPENDING 0x0200    /**< Has been sent upstream */
#define CCND_PFI_SENDUPST 0x0400    /**< Should be sent upstream */
#define CCND_PFI_UPHUNGRY 0x0800    /**< Upstream hungry, cupboard bare */
#define CCND_PFI_DNSTREAM 0x1000    /**< Tracks downstream (recvd interest) */
#define CCND_PFI_PENDING  0x2000    /**< Pending for immediate data */
#define CCND_PFI_SUPDATA  0x4000    /**< Suppressed data reply */
#define CCND_PFI_DCFACE  0x10000    /**< This upstream is a DC face */

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
    int fgen;                    /**< used to decide when forward_to is stale */
    unsigned src;                /**< faceid of recent content source */
    unsigned osrc;               /**< and of older matching content */
    unsigned usec;               /**< response-time prediction */
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
 * @def CCN_FORW_ACTIVE         1
 * @def CCN_FORW_CHILD_INHERIT  2
 * @def CCN_FORW_ADVERTISE      4
 * @def CCN_FORW_LAST           8
 * @def CCN_FORW_CAPTURE       16
 * @def CCN_FORW_LOCAL         32
 * @def CCN_FORW_TAP           64
 * @def CCN_FORW_CAPTURE_OK   128
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

/* Consider a separate header for these */
int ccnd_stats_handle_http_connection(struct ccnd_handle *, struct face *);
void ccnd_msg(struct ccnd_handle *, const char *, ...);
void ccnd_debug_ccnb(struct ccnd_handle *h,
                     int lineno,
                     const char *msg,
                     struct face *face,
                     const unsigned char *ccnb,
                     size_t ccnb_size);

struct ccnd_handle *ccnd_create(const char *, ccnd_logger, void *);
void ccnd_run(struct ccnd_handle *h);
void ccnd_destroy(struct ccnd_handle **);
extern const char *ccnd_usage_message;

#endif
