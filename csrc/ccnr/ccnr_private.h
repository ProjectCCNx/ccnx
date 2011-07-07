/**
 * @file ccnr_private.h
 *
 * Private definitions for ccnr - the CCNx daemon.
 * Data structures are described here so that logging and status
 * routines can be compiled separately.
 *
 * Part of ccnr - the CCNx Repository Daemon.
 *
 * Copyright (C) 2008-2011 Palo Alto Research Center, Inc.
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
 
#ifndef CCNR_PRIVATE_DEFINED
#define CCNR_PRIVATE_DEFINED

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
struct ccnr_meter;

struct SyncBaseStruct;
/*
 * These are defined in this header.
 */
struct ccnr_handle;
struct fdholder;
struct content_entry;
struct nameprefix_entry;
struct propagating_entry;
struct content_tree_node;
struct ccn_forwarding;
struct enum_state;
struct ccnr_parsed_policy;

//typedef uint_least64_t ccn_accession_t;
typedef unsigned ccn_accession_t;

typedef int (*ccnr_logger)(void *loggerdata, const char *format, va_list ap);

/**
 * We pass this handle almost everywhere within ccnr
 */
struct ccnr_handle {
    unsigned char ccnr_id[32];      /**< sha256 digest of our public key */
    struct ccn_charbuf *ccnr_keyid; /**< public key digest in keyid format %C1.M.K.%00... */
    struct hashtb *content_tab;     /**< keyed by portion of ContentObject */
    struct hashtb *nameprefix_tab;  /**< keyed by name prefix components */
    struct hashtb *propagating_tab; /**< keyed by nonce */
    //XXX probably need an event for cleaning up the enum_state_tab
    struct hashtb *enum_state_tab;  /**< keyed by enumeration interest */
    struct ccn_indexbuf *skiplinks; /**< skiplist for content-ordered ops */
    unsigned forward_to_gen;        /**< for forward_to updates */
    unsigned face_gen;              /**< filedesc generation number */
    unsigned face_rover;            /**< for filedesc allocation */
    unsigned face_limit;            /**< current number of fdholder slots */
    struct fdholder **fdholder_by_fd;  /**< array with face_limit elements */
    int active_out_fd;
    struct ccn_scheduled_event *reaper;
    struct ccn_scheduled_event *age;
    struct ccn_scheduled_event *clean;
    struct ccn_scheduled_event *age_forwarding;
    const char *portstr;            /**< port number for status display */
    nfds_t nfds;                    /**< number of entries in fds array */
    struct pollfd *fds;             /**< used for poll system call */
    struct ccn_gettime ticktock;    /**< our time generator */
    long sec;                       /**< cached gettime seconds */
    unsigned usec;                  /**< cached gettime microseconds */
    long starttime;                 /**< ccnr start time, in seconds */
    unsigned starttime_usec;        /**< ccnr start time fractional part */
    struct ccn_schedule *sched;     /**< our schedule */
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
    ccnr_logger logger;             /**< For debug output */
    void *loggerdata;               /**< Passed to logger */
    int logbreak;                   /**< see ccn_msg() */
    unsigned long logtime;          /**< see ccn_msg() */
    int logpid;                     /**< see ccn_msg() */
    int flood;                      /**< Internal control for auto-reg */
    unsigned interest_faceid;       /**< for self_reg internal client */
    const char *progname;           /**< our name, for locating helpers */
    struct ccn *direct_client;      /**< this talks directly with ccnd */
    struct ccn *internal_client;    /**< internal client */
    struct fdholder *face0;         /**< special fdholder for internal client */
    struct ccn_charbuf *service_ccnb; /**< for local service discovery */
    struct ccn_charbuf *neighbor_ccnb; /**< for neighbor service discovery */
    struct ccnr_parsed_policy *parsed_policy;  /**< offsets for parsed fields of policy */
    struct ccn_charbuf *policy_name;
    struct ccn_seqwriter *notice;   /**< for notices of status changes */
    struct ccn_indexbuf *chface;    /**< faceids w/ recent status changes */
    struct ccn_scheduled_event *internal_client_refresh;
    struct ccn_scheduled_event *direct_client_refresh;
    struct ccn_scheduled_event *notice_push;
    void (*appnonce)(struct ccnr_handle *, struct fdholder *, struct ccn_charbuf *);
                                    /**< pluggable nonce generation */
    /* items related to sync/repo integration */
    struct SyncBaseStruct *sync_handle;  /**< handle to pass to the sync code */
    ccn_accession_t notify_after;             /**< starting item number for notifying sync */
};

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
 * fdholder meter index
 */
enum ccnr_face_meter_index {
    FM_BYTI,
    FM_BYTO,
    FM_DATI,
    FM_INTO,
    FM_DATO,
    FM_INTI,
    CCNR_FACE_METER_N
};

/**
 * Each fdholder is referenced by its file descriptor.
 */
struct fdholder {
    int recv_fd;                /**< socket for receiving */
    unsigned sendface;          /**< filedesc for sending (maybe == filedesc) */
    int flags;                  /**< CCNR_FACE_* fdholder flags */
    unsigned filedesc;            /**< internal fdholder id */
    unsigned recvcount;         /**< for activity level monitoring */
    struct content_queue *q[CCN_CQ_N]; /**< outgoing content, per delay class */
    struct ccn_charbuf *inbuf;	/** Buffered input data */
    struct ccn_skeleton_decoder decoder;
    size_t outbufindex;			/** Buffered output data */
    struct ccn_charbuf *outbuf;
    struct ccn_charbuf *name;	/** a sockaddr or file name, depending on flags */
	int pending_interests;
    unsigned rrun;
    uintmax_t rseq;
    struct ccnr_meter *meter[CCNR_FACE_METER_N];
    unsigned short pktseq;     /**< sequence number for sent packets */
};

/** fdholder flags */


// XXX - remove
#define CCNR_FACE_LINK   (1 << 0) /**< Elements wrapped by CCNProtocolDataUnit */
#define CCNR_FACE_DGRAM  (1 << 1) /**< Datagram interface, respect packets */
#define CCNR_FACE_GG     (1 << 2) /**< Considered friendly */
#define CCNR_FACE_LOCAL  (1 << 3) /**< PF_UNIX socket */
#define CCNR_FACE_INET   (1 << 4) /**< IPv4 */
// XXX - remove
#define CCNR_FACE_MCAST  (1 << 5) /**< a party line (e.g. multicast) */
#define CCNR_FACE_INET6  (1 << 6) /**< IPv6 */
// XXX - remove
#define CCNR_FACE_DC     (1 << 7) /**< Direct control fdholder */
#define CCNR_FACE_NOSEND (1 << 8) /**< Don't send anymore */
#define CCNR_FACE_UNDECIDED (1 << 9) /**< Might not be talking ccn */
#define CCNR_FACE_PERMANENT (1 << 10) /**< No timeout for inactivity */
#define CCNR_FACE_CONNECTING (1 << 11) /**< Connect in progress */
#define CCNR_FACE_LOOPBACK (1 << 12) /**< v4 or v6 loopback address */
#define CCNR_FACE_CLOSING (1 << 13) /**< close stream when output is done */
#define CCNR_FACE_PASSIVE (1 << 14) /**< a listener or a bound dgram socket */
#define CCNR_FACE_NORECV (1 << 15) /**< use for sending only */
// XXX - remove
#define CCNR_FACE_REGOK (1 << 16) /**< Allowed to do prefix registration */
// XXX - remove
#define CCNR_FACE_SEQOK (1 << 17) /** OK to send SequenceNumber link messages */
// XXX - remove
#define CCNR_FACE_SEQPROBE (1 << 18) /** SequenceNumber probe */
#define CCNR_FACE_REPODATA (1 << 19) /** A repository log-structured data file */
#define CCNR_FACE_CCND (1 << 20) /** A connection to our ccnd */
#define CCNR_FACE_SOCKMASK (CCNR_FACE_DGRAM | CCNR_FACE_INET | CCNR_FACE_INET6 | CCNR_FACE_LOCAL)

#define CCN_NOFACEID    (-1)    /** denotes no fdholder */

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
#define CCN_CONTENT_ENTRY_STABLE    8 /**< Has been written to repository */

/**
 * The sparse_straggler hash table, keyed by accession, holds scattered
 * entries that would otherwise bloat the direct content_by_accession table.
 */
struct sparse_straggler_entry {
    struct content_entry *content;
};

/**
 * The propagating interest hash table is keyed by Nonce.
 *
 * While the interest is pending, the pe is also kept in a doubly-linked
 * list off of a nameprefix_entry.
 *
 * When the interest is consumed, the pe is removed from the doubly-linked
 * list and is cleaned up by freeing unnecessary bits (including the interest
 * message itself).  It remains in the hash table for a time, in order to catch
 * duplicate nonces.
 */
struct propagating_entry {
    struct propagating_entry *next;
    struct propagating_entry *prev;
    unsigned flags;             /**< CCN_PR_xxx */
    unsigned filedesc;            /**< origin of the interest, dest for matches */
    int usec;                   /**< usec until timeout */
    int sent;                   /**< leading faceids of outbound processed */
    struct ccn_indexbuf *outbound; /**< in order of use */
    unsigned char *interest_msg; /**< pending interest message */
    unsigned size;              /**< size in bytes of interest_msg */
    int fgen;                   /**< decide if outbound is stale */
};
// XXX - with new outbound/sent repr, some of these flags may not be needed.
#define CCN_PR_UNSENT   0x01 /**< interest has not been sent anywhere yet */
#define CCN_PR_WAIT1    0x02 /**< interest has been sent to one place */
#define CCN_PR_STUFFED1 0x04 /**< was stuffed before sent anywhere else */
#define CCN_PR_TAP      0x08 /**< at least one tap fdholder is present */
#define CCN_PR_EQV      0x10 /**< a younger similar interest exists */
#define CCN_PR_SCOPE0   0x20 /**< interest scope is 0 */
#define CCN_PR_SCOPE1   0x40 /**< interest scope is 1 (this host) */
#define CCN_PR_SCOPE2   0x80 /**< interest scope is 2 (immediate neighborhood) */

/**
 * The nameprefix hash table is keyed by the Component elements of
 * the Name prefix.
 */
struct nameprefix_entry {
    struct propagating_entry pe_head; /**< list head for propagating entries */
    struct ccn_indexbuf *forward_to; /**< faceids to forward to */
    struct ccn_indexbuf *tap;    /**< faceids to forward to as tap*/
    struct ccn_forwarding *forwarding; /**< detailed forwarding info */
    struct nameprefix_entry *parent; /**< link to next-shorter prefix */
    int children;                /**< number of children */
    unsigned flags;              /**< CCN_FORW_* flags about namespace */
    int fgen;                    /**< used to decide when forward_to is stale */
    unsigned src;                /**< filedesc of recent content source */
    unsigned osrc;               /**< and of older matching content */
    unsigned usec;               /**< response-time prediction */
};

/**
 * Keeps track of the faces that interests matching a given name prefix may be
 * forwarded to.
 */
struct ccn_forwarding {
    unsigned filedesc;             /**< locally unique number identifying fdholder */
    unsigned flags;              /**< CCN_FORW_* - c.f. <ccn/reg_mgnt.h> */
    int expires;                 /**< time remaining, in seconds */
    struct ccn_forwarding *next;
};

/**
 * Keeps track of the state of running and recently completed enumerations
 * The enum_state hash table is keyed by the interest up to the segment id
 */
struct enum_state {
    struct ccn_charbuf *name;
    struct ccn_seqwriter *w;
    struct content_entry *content;
    struct ccn_charbuf *reply_body;
    struct ccn_charbuf *interest;
    struct ccn_indexbuf *interest_comps;
    uintmax_t next_segment;
    ccn_accession_t starting_accession;
    int active;
};


#if 0
/* create and destroy procs for separately allocated meters */
struct ccnr_meter *ccnr_meter_create(struct ccnr_handle *h, const char *what);
void ccnr_meter_destroy(struct ccnr_meter **);

/* for meters kept within other structures */
void ccnr_meter_init(struct ccnr_handle *h, struct ccnr_meter *m, const char *what);

/* count something (messages, packets, bytes), getting time info from h */
void ccnr_meter_bump(struct ccnr_handle *h, struct ccnr_meter *m, unsigned amt);

unsigned ccnr_meter_rate(struct ccnr_handle *h, struct ccnr_meter *m);
uintmax_t ccnr_meter_total(struct ccnr_meter *m);
#endif
/**
 * @def CCN_FORW_ACTIVE         1
 * @def CCN_FORW_CHILD_INHERIT  2
 * @def CCN_FORW_ADVERTISE      4
 * @def CCN_FORW_LAST           8
 * @def CCN_FORW_CAPTURE       16
 * @def CCN_FORW_LOCAL         32
 */
#define CCN_FORW_PFXO (CCN_FORW_ADVERTISE | CCN_FORW_CAPTURE | CCN_FORW_LOCAL)
#define CCN_FORW_REFRESHED      (1 << 16) /**< private to ccnr */

 
/**
 * Determines how frequently we age our forwarding entries
 */
#define CCN_FWU_SECS 5

/**
 * URIs for prefixes served by the internal client
 */
#define CCNRID_LOCAL_URI "ccnx:/%C1.M.S.localhost/%C1.M.SRV/repository/KEY"
#define CCNRID_NEIGHBOR_URI "ccnx:/%C1.M.S.neighborhood/%C1.M.SRV/repository/KEY"

#if 0
int r_fwd_reg_uri(struct ccnr_handle *h,
                 const char *uri,
                 unsigned filedesc,
                 int flags,
                 int expires);

struct fdholder *ccnr_r_io_fdholder_from_fd(struct ccnr_handle *, unsigned);
void ccnr_face_status_change(struct ccnr_handle *, unsigned);
int r_io_destroy_face(struct ccnr_handle *h, unsigned filedesc);
void r_io_send(struct ccnr_handle *h, struct fdholder *fdholder,
               const void *data, size_t size);

/* Consider a separate header for these */
int ccnr_stats_handle_http_connection(struct ccnr_handle *, struct fdholder *);
void ccnr_msg(struct ccnr_handle *, const char *, ...);
void ccnr_debug_ccnb(struct ccnr_handle *h,
                     int lineno,
                     const char *msg,
                     struct fdholder *fdholder,
                     const unsigned char *ccnb,
                     size_t ccnb_size);

struct ccnr_handle *r_init_create(const char *, ccnr_logger, void *);
void r_dispatch_run(struct ccnr_handle *h);
void r_init_destroy(struct ccnr_handle **);
extern const char *ccnr_usage_message;
#endif

#define PUBLIC
#endif
