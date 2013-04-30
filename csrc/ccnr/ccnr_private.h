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
struct ccn_btree;

struct sync_plumbing;
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

/* Repository-specific content identifiers */

#if (defined(CCNLINT) && (CCNLINT == 1))
/* This is where we probably want to end up for declaring this type */
typedef uint_least64_t ccnr_accession;
#define CCNR_NULL_ACCESSION ((ccnr_accession)(0))
#define CCNR_MIN_ACCESSION ((ccnr_accession)(1))
#define CCNR_MAX_ACCESSION ((ccnr_accession)(~CCNR_NULL_ACCESSION))
#elif (defined(CCNLINT) && (CCNLINT == 2))
#error "Not expected to work - this is for detecting illegitimate comparisons"
struct intentionally_incomplete;
typedef struct intentionally_incomplete *ccnr_accession;
#define CCNR_NULL_ACCESSION ((ccnr_accession)(0))
#define CCNR_MIN_ACCESSION ((ccnr_accession)(0x10000000))
#define CCNR_MAX_ACCESSION ((ccnr_accession)(0x7fffffff))
#elif (defined(CCNLINT) && (CCNLINT == 3))
#error "Not expected to work - this is for detecting illegitimate casts"
typedef struct ccnr_accession_rep {unsigned a; unsigned b;} ccnr_accession;
struct ccnr_accession_rep ccnr_null_accession;
struct ccnr_accession_rep ccnr_min_accession;
struct ccnr_accession_rep ccnr_max_accession;
#define CCNR_NULL_ACCESSION ccnr_null_accession
#define CCNR_MIN_ACCESSION ccnr_min_accession
#define CCNR_MAX_ACCESSION ccnr_max_accession
#else
typedef uint_least64_t ccnr_accession;
#define CCNR_NULL_ACCESSION ((ccnr_accession)(0))
#define CCNR_MIN_ACCESSION ((ccnr_accession)(1))
#define CCNR_MAX_ACCESSION ((ccnr_accession)(~CCNR_NULL_ACCESSION))
#endif

#define CCNR_NOT_COMPARABLE (-2)
 
/* Encode/decode a ccnr_accession as an unsigned number. */
uintmax_t ccnr_accession_encode(struct ccnr_handle *, ccnr_accession);
ccnr_accession ccnr_accession_decode(struct ccnr_handle *, uintmax_t);

/* Return 1 if x dominates (is newer than) y, 0 if x equals y, -1 if y dominates x,
 * and CCNR_NOT_COMPARABLE if the ordering is not determined
 */
int ccnr_accession_compare(struct ccnr_handle *ccnr, ccnr_accession x, ccnr_accession y);

/* Repository-specific high water marks */

/* XXX - ccnr_hwm should be a distinct type */
typedef uintmax_t ccnr_hwm;
#define CCNR_NULL_HWM ((ccnr_hwm)(0))

/* Encode a high water mark as an unsigned number */
uintmax_t ccnr_hwm_encode(struct ccnr_handle *, ccnr_hwm);
ccnr_hwm ccnr_hwm_decode(struct ccnr_handle *, uintmax_t);

/* Return 1 if a is in the hwm set, 0 if not, -1 if unknown. */
int ccnr_acc_in_hwm(struct ccnr_handle *, ccnr_accession a, ccnr_hwm hwm);

/* Produce a new high water mark that includes the given content */
ccnr_hwm ccnr_hwm_update(struct ccnr_handle *, ccnr_hwm, ccnr_accession);
ccnr_hwm ccnr_hwm_merge(struct ccnr_handle *, ccnr_hwm, ccnr_hwm);

/* Return 1 if x dominates y, 0 if x equals y, -1 if y dominates x,
 * and CCNR_NOT_COMPARABLE if the ordering is not determined
 */
int ccnr_hwm_compare(struct ccnr_handle *ccnr, ccnr_hwm x, ccnr_hwm y);

/**
 * A cookie is used as a more ephemeral way of holding a reference to a
 * content object, without the danger of an undetected dangling reference
 * when the in-memory content handle is destroyed.  This is for internal
 * data structures such as queues or enumeration states, but should not
 * be stored in any long-term way.  Use a ccnr_accession, content name, or
 * digest for that.
 *
 * Holding a cookie does not prevent the in-memory content handle from being
 * destroyed, either explicitly or to conserve resources.
 */
typedef unsigned ccnr_cookie;


/** Logger type (ccnr_logger) */
typedef int (*ccnr_logger)(void *loggerdata, const char *format, va_list ap);

/**
 * This is true if we should log at the given level.
 * 
 */
#define CCNSHOULDLOG(h, who, level) (((h)->debug >= (level)) != 0)

/* XXX - these are the historical bitfields. */
#define LM_2    2
#define LM_4    4
#define LM_8    8
#define LM_16    16
#define LM_32    32
#define LM_64    64
#define LM_128    128

/**
 * Limit on how many active sync enumerations we are willing to have going.
 */
#define CCNR_MAX_ENUM 64

/**
 * We pass this handle almost everywhere within ccnr
 */
struct ccnr_handle {
    unsigned char ccnr_id[32];      /**< sha256 digest of our public key */
    struct ccn_charbuf *ccnr_keyid; /**< public key digest in keyid format %C1.M.K.%00... */
    struct hashtb *nameprefix_tab;  /**< keyed by name prefix components */
    struct hashtb *propagating_tab; /**< keyed by nonce */
    struct hashtb *enum_state_tab;  /**< keyed by enumeration interest */
    struct ccn_indexbuf *skiplinks; /**< skiplist for content-ordered ops */
    struct ccn_btree *btree;        /**< btree index of content */
    unsigned forward_to_gen;        /**< for forward_to updates */
    unsigned face_gen;              /**< filedesc generation number */
    unsigned face_rover;            /**< for filedesc allocation */
    unsigned face_limit;            /**< current number of fdholder slots */
    struct fdholder **fdholder_by_fd;  /**< array with face_limit elements */
    int active_in_fd;               /**< data currently being indexed */
    int active_out_fd;              /**< repo file we will write to */
    int repofile1_fd;               /**< read-only access to repoFile1 */
    off_t startupbytes;             /**< repoFile1 size at startup */
    off_t stable;                   /**< repoFile1 size at shutdown */
    struct ccn_scheduled_event *reaper;
    struct ccn_scheduled_event *age;
    struct ccn_scheduled_event *clean;
    struct ccn_scheduled_event *age_forwarding;
    struct ccn_scheduled_event *reap_enumerations; /**< cleans out old enumeration state */
    struct ccn_scheduled_event *index_cleaner; /**< writes out btree nodes */
    struct ccn_indexbuf *toclean;   /**< for index_cleaner use */
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
    /** Next two fields are used for direct cookie-to-content table */
    unsigned cookie_limit;          /**< content_by_cookie size(power of 2)*/
    struct content_entry **content_by_cookie; /**< cookie-to-content table */
    struct hashtb *content_by_accession_tab; /**< keyed by accession */
    ccnr_cookie cookie;      /**< newest used cookie number */
    ccnr_cookie min_stale;      /**< smallest cookie of stale content */
    ccnr_cookie max_stale;      /**< largest cookie of stale content */
    ccnr_cookie trim_rover;     /**< where we left off trimming */
    unsigned long n_stale;          /**< Number of stale content objects */
    struct ccn_indexbuf *unsol;     /**< unsolicited content */
    unsigned long cob_count;  /**< count of accessioned content objects in memory */
    unsigned long cob_limit;  /**< trim when we get beyond this */
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
    unsigned long content_from_accession_hits;
    unsigned long content_from_accession_misses;
    unsigned long count_lmc_found;
    unsigned long count_lmc_found_iters;
    unsigned long count_lmc_notfound;
    unsigned long count_lmc_notfound_iters;
    unsigned long count_rmc_found;
    unsigned long count_rmc_found_iters;
    unsigned long count_rmc_notfound;
    unsigned long count_rmc_notfound_iters;
    /* Control switches and knobs */
    unsigned start_write_scope_limit;    /**< Scope on start-write must be <= this value.  3 indicates unlimited */
    unsigned short seed[3];         /**< for PRNG */
    int running;                    /**< true while should be running */
    int debug;                      /**< For controlling debug output */
    int syncdebug;                  /**< For controlling debug output from sync */
    ccnr_logger logger;             /**< For debug output */
    void *loggerdata;               /**< Passed to logger */
    int logbreak;                   /**< see ccnr_msg() */
    unsigned long logtime;          /**< see ccnr_msg() */
    int logpid;                     /**< see ccnr_msg() */
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
    struct ccn_charbuf *policy_link_cob;
    struct ccn_seqwriter *notice;   /**< for notices of status changes */
    struct ccn_indexbuf *chface;    /**< faceids w/ recent status changes */
    struct ccn_scheduled_event *internal_client_refresh;
    struct ccn_scheduled_event *direct_client_refresh;
    struct ccn_scheduled_event *notice_push;
    /* items related to sync/repo integration */
    struct sync_plumbing *sync_plumbing;  /**< encapsulates methods and data */
    struct SyncBaseStruct *sync_base;
    ccnr_accession notify_after;  /**< starting item for notifying sync */
    ccnr_accession active_enum[CCNR_MAX_ENUM]; /**< active sync enumerations */
    
    const char *directory;           /**< the repository directory */
};

struct content_queue {
    unsigned burst_nsec;             /**< nsec per KByte, limits burst rate */
    unsigned min_usec;               /**< minimum delay for this queue */
    unsigned rand_usec;              /**< randomization range */
    unsigned ready;                  /**< # that have waited enough */
    unsigned nrun;                   /**< # sent since last randomized delay */
    struct ccn_indexbuf *send_queue; /**< cookie numbers of pending content */
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
    unsigned filedesc;          /**< file descriptor */
    int flags;                  /**< CCNR_FACE_* fdholder flags */
    unsigned recvcount;         /**< for activity level monitoring */
    struct content_queue *q[CCN_CQ_N]; /**< outgoing content, per delay class */
    off_t bufoffset;
    struct ccn_charbuf *inbuf;  /** Buffered input data */
    struct ccn_skeleton_decoder decoder;
    size_t outbufindex;         /** Buffered output data */
    struct ccn_charbuf *outbuf;
    struct ccn_charbuf *name;   /** a sockaddr or file name, depending on flags */
    int pending_interests;
    struct ccnr_meter *meter[CCNR_FACE_METER_N];
};

/** fdholder flags */

#define CCNR_FACE_DGRAM  (1 << 1) /**< Datagram interface, respect packets */
#define CCNR_FACE_GG     (1 << 2) /**< Considered friendly */
#define CCNR_FACE_LOCAL  (1 << 3) /**< PF_UNIX socket */
#define CCNR_FACE_INET   (1 << 4) /**< IPv4 */
#define CCNR_FACE_INET6  (1 << 6) /**< IPv6 */
#define CCNR_FACE_NOSEND (1 << 8) /**< Don't send anymore */
#define CCNR_FACE_UNDECIDED (1 << 9) /**< Might not be talking ccn */
#define CCNR_FACE_PERMANENT (1 << 10) /**< No timeout for inactivity */
#define CCNR_FACE_CONNECTING (1 << 11) /**< Connect in progress */
#define CCNR_FACE_LOOPBACK (1 << 12) /**< v4 or v6 loopback address */
#define CCNR_FACE_CLOSING (1 << 13) /**< close stream when output is done */
#define CCNR_FACE_PASSIVE (1 << 14) /**< a listener or a bound dgram socket */
#define CCNR_FACE_NORECV (1 << 15) /**< use for sending only */
#define CCNR_FACE_REPODATA (1 << 19) /** A repository log-structured data file */
#define CCNR_FACE_CCND (1 << 20) /** A connection to our ccnd */
#define CCNR_FACE_SOCKMASK (CCNR_FACE_DGRAM | CCNR_FACE_INET | CCNR_FACE_INET6 | CCNR_FACE_LOCAL)

#define CCN_NOFACEID    (-1)    /** denotes no fdholder */

/**
 *  A pointer to this is used as a handle for a content object that we
 *  currently care about.  Most details are private to the implementation.
 */
struct content_entry;

/**
 * content_entry flags
 */
#define CCN_CONTENT_ENTRY_SLOWSEND  1
#define CCN_CONTENT_ENTRY_STALE     2
#define CCN_CONTENT_ENTRY_PRECIOUS  4
#define CCN_CONTENT_ENTRY_STABLE    8 /**< Repository-backed */

/**
 * The content_by_accession hash table, keyed by accession, holds
 * entries that have a known accession.
 */
struct content_by_accession_entry {
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
enum es_active_state {
    ES_PENDING = -1,
    ES_INACTIVE = 0,
    ES_ACTIVE = 1,
    ES_ACTIVE_PENDING_INACTIVE = 2
};
#define ENUM_N_COBS 9
struct enum_state {
    struct ccn_charbuf *name;
    struct content_entry *content;
    struct ccn_charbuf *reply_body;
    struct ccn_charbuf *interest;
    struct ccn_indexbuf *interest_comps;
    struct ccn_charbuf *cob[ENUM_N_COBS];
    int cob_deferred[ENUM_N_COBS];
    intmax_t next_segment;
    ccnr_cookie starting_cookie;
    enum es_active_state active;
    long lifetime;
    long lastuse_sec;
    unsigned lastuse_usec;
};

/**
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
#define CCNRID_POLICY_URI "ccnx:/%C1.M.S.localhost/%C1.M.SRV/repository/POLICY"

#define PUBLIC

struct ccnr_handle *r_init_create(const char *, ccnr_logger, void *);
void r_init_run(struct ccnr_handle *h);
void r_init_destroy(struct ccnr_handle **);

#endif
