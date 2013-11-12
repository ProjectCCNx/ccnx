/**
 * @file ccnd_strategy.h
 *
 * This header defines the API to be used by strategy callouts.
 *
 * Part of ccnd - the CCNx Daemon.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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
 
#ifndef CCND_STRATEGY_DEFINED
#define CCND_STRATEGY_DEFINED

#include <sys/types.h>
#include <stddef.h>
#include <stdint.h>

/* These types should remain opaque for strategy routines */
struct ccnd_handle;
struct interest_entry;
struct face;
struct nameprefix_entry;

/* Forward struct defined later in this header */
struct strategy_instance;


#define CCN_UNINIT    (~0U)     /**< initial value of strategy vars */
#define CCN_MAGIC_MASK 0x00FFFFFF /**< for magic number */
#define CCN_AGED       0x10000000 /**< for aging */
#define CCND_STRATEGY_STATE_N 4 /**< number of per-prefix strategy vars */

/**
 * This is a place for strategies to record state that is attached
 * to a given name prefix.
 *
 * At this level, we simply have an array of unsigned ints.
 * The leading value, s[0], has some special significance in
 * that it should be used hold a value that identifies the
 * interpretation of the remaining values, as well as an aging flag.
 *
 * When a name prefix entry is created, the associated state
 * is initialized to all CCN_UNINIT.
 */
struct nameprefix_state {
    unsigned s[CCND_STRATEGY_STATE_N];
};

/**
 * Use this macro to make sure that overlaying structures are not oversize
 *
 * XX is an otherwise unused identifier, and T is the overlaid struct type.
 * This is intended to cause a compilation error if T becomes too large.
 */
#define CCN_STATESIZECHECK(XX, T) \
    struct XX {char x[(int)((sizeof(T) > sizeof(struct nameprefix_state)) ? -1 : 1)];}

/**
 * Used for keeping track of interest expiry.
 *
 * Modulo 2**32 - time units and origin are arbitrary and private.
 */
typedef uint32_t ccn_wrappedtime;

/** A faceid is declared simply as unsigned. There is one special value. */
#define CCN_NOFACEID    (~0U)    /** denotes no face */

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
#define CCND_PFI_UPENDING 0x0200    /**< Has been sent upstream (initially cleared, set for tap face) */
#define CCND_PFI_SENDUPST 0x0400    /**< Should be sent upstream (send upstream at expiry) */
#define CCND_PFI_UPHUNGRY 0x0800    /**< Upstream hungry, cupboard bare (upstream expired, no unexpired downstream to refresh) */
#define CCND_PFI_DNSTREAM 0x1000    /**< Tracks downstream (recvd interest) */
#define CCND_PFI_PENDING  0x2000    /**< Pending for immediate data */
#define CCND_PFI_SUPDATA  0x4000    /**< Suppressed data reply */
#define CCND_PFI_ATTENTION 0x10000  /**< Upstream needs attention from strategy */
#define CCND_PFI_INACTIVE 0x20000   /**< Face is nonresponsive, may have lost communication */
#define CCND_PFI_DCFACE 0x100000    /**< This upstream is a Direct Control face (gets data if unanswered for a long time) */

/**
 * State for the strategy engine
 *
 * This is associated with each PIT entry, and keeps track of the associated
 * upstream and downstream faces.
 */
struct ccn_strategy {
    struct pit_face_item *pfl;      /**< upstream and downstream faces */
    ccn_wrappedtime birth;          /**< when interest entry was created */
    ccn_wrappedtime renewed;        /**< when interest entry was renewed */
    unsigned renewals;              /**< number of times renewed */
    struct interest_entry *ie;      /**< associated interest entry */
};

/**
 * Ops for strategy callout
 *
 * These are passed to the strategy callout to inform it of the current
 * situation.
 *
 * CCNST_NOP        is useful as an argument to pass to strategy_settimer()
 *                  when the callout wishes to cancel a pending strategy
 *                  timer.  CCNST_NOP is not expected to actually be passed
 *                  to a callout, but if it is, the strategy should take
 *                  no action that changes forwarding behavior.
 *
 * CCNST_INIT       provides an opportunity for the callout to allocate
 *                  and initialize any private instance state that it may
 *                  require.  This happens when a new strategy is attached to
 *                  a given prefix.  If the strategy uses parameters, this
 *                  call is the appropriate time to parse them and save the
 *                  resulting values in the private instance state for
 *                  rapid access in the more time-critical calls.
 *                  The callout should use strategy_init_error() to report
 *                  problems with the parameter string.
 *
 * CCNST_FIRST      indicates the creation of a new PIT entry due to an
 *                  arriving interest.  Since there was no existing state
 *                  for similar interests, there will be exactly one
 *                  downstream in the pfi list.  The upstreams in the list
 *                  are those that the FIB has indicated are eligible to
 *                  receive the interests.
 *                  The expiry times are initially set to the current time,
 *                  so if the strategy callout sets CCND_PFI_SENDUPST on,
 *                  an upstream, the interest will be sent to the marked
 *                  upstreams as soon as the callout returns.
 *                  At the time of the CCNST_FIRST call, some upstreams may have
 *                  already been fed (such as those with the TAP forwarding
 *                  flag).  These will have CCND_PFI_UPENDING set.
 *                  The strategy callout should generally ignore such entries.
 *                  The faceid indicates the initial downstream face.
 *
 * CCNST_UPDATE     indicates at least one upstream has become eligible
 *                  receive a new copy of the interest, because any previously
 *                  sent interest has expired and an unexpired downsream is
 *                  available; or a new upstream has been added,
 *                  because of a new prefix registration, or because
 *                  a second downstream has made the initial upstream
 *                  eligible.  The affected upstreams will have
 *                  CCND_PFI_ATTENTION set and an expiry in the near future.
 *                  For these entries, the strategy must
 *                  clear CCND_PFI_ATTENTION and may choose to set
 *                  CCND_PFI_SENDUPST on the subset of the affected upstreams
 *                  that it selects.
 *                  The value of faceid is not interesting.
 *
 * CCNST_EXPUP      indicates an upstream is expiring. This happens when and
 *                  only when an interest has been sent to the upstream face
 *                  and the associated lifetime has elapsed without the
 *                  receipt of matching content.
 *                  The faceid indicates the expiring upstream face.
 *
 * CCNST_EXPDN      indicates the downstream is expiring.
 *                  The faceid indicates the expiring downstream face.
 *
 * CCNST_REFRESH    indicates a new, similar, interest message has arrived.
 *                  a previously existing downstream face.  Its expiry
 *                  Its expiry will have been updated accordingly.  The strategy
 *                  likely does not need to do anything special with this
 *                  case, because it will get separate calls for each of
 *                  the upstreams when their respective expiries occur.
 *                  The faceid indicates the refreshed downstream face.
 *
 * CCNST_TIMER      is intended as an argument for strategy_settimer()
 *                  so that the strategy callout can get control at a time
 *                  that does not need to correspond with the expiry of
 *                  an upstream or downstream.
 *                  The value of faceid is not interesting.
 *
 * CCNST_SATISFIED  indicates the arrival of a matching content object.
 *                  After the strategy callout returns, all of the
 *                  downstreams that have CCND_PFI_PENDING set will
 *                  be sent copies of the data, and the PIT entry will
 *                  be removed.
 *                  The faceid indicates the source of the matching content.
 *
 * CCNST_TIMEOUT    indicates that all downstreams and upstreams have expired.
 *                  The PIT entry will go away as soon as the callout returns.
 *                  The value of faceid is not interesting.
 *
 * CCNST_FINALIZE   indicates the strategy instance is about to go away.
 *                  The strategy callout should deallocate any
 *                  strategy-private memory.
 *                  The value of faceid is not interesting.
 */
enum ccn_strategy_op {
    CCNST_NOP,      /* no-operation */
    CCNST_INIT,     /* initialize strategy, allocate instance state */
    CCNST_FIRST,    /* newly created interest entry (pit entry) */
    CCNST_UPDATE,   /* select upstreams to feed */
    CCNST_EXPUP,    /* upstream is expiring */
    CCNST_EXPDN,    /* downstream is expiring */
    CCNST_REFRESH,  /* downstream refreshed */
    CCNST_TIMER,    /* wakeup used by strategy */
    CCNST_SATISFIED, /* matching content has arrived, pit entry will go away */
    CCNST_TIMEOUT,  /* all downstreams timed out, pit entry will go away */
    CCNST_FINALIZE, /* destroy instance state */
};

/**
 *
 * Strategies are implemented by a procedure that is called at
 * critical junctures in the lifetime of a pending interest.
 *
 * If op is CCNST_FIRST, faceid tells the interest arrival face (downstream).
 * If op is CCNST_SATISFIED, faceid tells the content arrival face (upstream).
 *
 */
typedef void (*strategy_callout_proc)(struct ccnd_handle *h,
                                      struct strategy_instance *instance,
                                      struct ccn_strategy *s,
                                      enum ccn_strategy_op op,
                                      unsigned faceid);

struct strategy_class {
    char id[16];                   /* The name of the strategy */
    strategy_callout_proc callout; /* procedure implementing the strategy */
};

struct strategy_instance {
    const struct strategy_class *sclass; /* strategy class */
    const char *parameters;              /* passed in from outside */
    void *data;                          /* strategy private data */
    struct nameprefix_entry *npe;        /* where strategy is registered */
};

/**
 *  Note a strategy initialization error
 *
 * A call to this during the CCNST_INIT callout will do appropriate
 * logging and error reporting, and cause the instance to be removed after
 * the termination of the intialization callout.
 *
 * Do not call from other contexts.
 */
void strategy_init_error(struct ccnd_handle *h,
                         struct strategy_instance *instance,
                         const char *message);
/**
 *  Forward an interest message
 *
 *  The strategy routine may choose to call this directly, and/or
 *  update the pfi entries so that the interest will be forwarded
 *  on a schedule.  If send_interest is called, p is updated to
 *  reflect the new state.
 *
 *  x is downstream (the interest came from x).
 *  p is upstream (the interest is to be forwarded to p).
 *  @returns p (or its reallocated replacement).
 */
struct pit_face_item *
send_interest(struct ccnd_handle *h, struct interest_entry *ie,
              struct pit_face_item *x, struct pit_face_item *p);

/**
 * Set the expiry of the pit face item using a time in microseconds from present
 *
 * Does not set the renewed timestamp.
 */
void
pfi_set_expiry_from_micros(struct ccnd_handle *h, struct interest_entry *ie,
                           struct pit_face_item *p, unsigned micros);

/**
 * Return a pointer to the strategy state records for
 * the name prefix of the given interest entry and up to k-1 parents.
 *
 * The sst array is filled in; NULL values are provided as needed.
 * The item sst[0] corresponds with the name inside the interest, and is
 * never NULL unless s is NULL.
 * The remaining entries are for successively shorter prefixes.
 */
void strategy_getstate(struct ccnd_handle *h, struct ccn_strategy *s,
                       struct nameprefix_state **sst, int k);

/**
 * Schedule a strategy wakeup
 *
 * This causes the associated strategy callout to be called at
 * a later time.  The op will be passed to the deferred invocation.
 *
 * Any previously scheduled wakeup will be cancelled.
 * To just cancel any existing wakeup, pass CCNST_NOP.
 */
void strategy_settimer(struct ccnd_handle *h, struct interest_entry *ie,
                       int usec, enum ccn_strategy_op op);

/**
 * Get the face handle for a given faceid
 *
 * Strategy routines should use the accessors provided.
 *
 * @returns NULL if face does not exist.
 */
struct face *ccnd_face_from_faceid(struct ccnd_handle *, unsigned);

/** Accessors for things a strategy might want to know about a face. */
unsigned face_faceid(struct face *);
int face_pending_interests(struct face *);
int face_outstanding_interests(struct face *);

/**
 * Face attributes
 *
 * To help strategies do their work, there is provision for faces to carry
 * a collection of attributes.  These have associated values, which can be
 * either boolean or numeric (non-negative integers).  Strategies may set
 * and get these values using an attribute index to say which attribute is
 * desired.  Some attributes are set by ccnd, based upon things it
 * knows about the face.  Others have associated names, and may be set from
 * the outside (using the face managment protocol).  Still others are private
 * to strategy implementations, and need not have a name, only a dynamically
 * assigned index.
 *
 * The first 32 indices (0 through 31) are reserved for single-bit attributes.
 * These may be read all at once using faceattr_get_packed, but are set using
 * the general faceattr_set call.  They may also be read using faceattr_get.
 * In the packed form, the attribute with index 0 is stored in the low-order
 * bit, so the bits may be tested using straightforward shifts and masks.
 * After the first 32 single-bit attributes have been created, any additional
 * requests will be fullfulled with attributes capable of carrying numeric
 * values.
 *
 * Newly created attributes are initialized to 0/false.
 *
 * Some attributes are created and set by ccnd, reflecting things about faces
 * that may be relevant to the operation of strategies.  These are assigned
 * with predeclared indices, so it is not necessary to learn the index
 * from the name at runtime (although this is allowed).  All of the built-in
 * single-bit attributes have small indices, and so are accessible using
 * faceattr_get_packed.  Macros for corresponding bit masks are also provided.
 */
int faceattr_index_from_name(struct ccnd_handle *h, const char *name);
int faceattr_bool_index_from_name(struct ccnd_handle *h, const char *name);
int faceattr_index_allocate(struct ccnd_handle *h);
int faceattr_index_free(struct ccnd_handle *h, int faceattr_index);
unsigned faceattr_get(struct ccnd_handle *h, struct face *face, int faceattr_index);
int faceattr_set(struct ccnd_handle *h, struct face *face, int faceattr_index, unsigned value);
unsigned faceattr_get_packed(struct ccnd_handle *h, struct face *face);

/**
 *  Face attribute "valid"
 *
 * If true, the face may be used for interest/data exchange.
 */
#define FAI_VALID 0
#define FAM_VALID (1U << FAI_VALID)

/**
 *  Face attribute "application"
 *
 * If true, the face is deemed to be a local application, by virtue of
 * connection information (e.g., loopback interface or unix-domain socket).
 */
#define FAI_APPLICATION 1
#define FAM_APPLICATION (1U << FAI_APPLICATION)
#define FAM_APP FAM_APPLICATION

/**
 *  Face attribute "broadcastcapable"
 *
 * If true, the face can reach multiple peers via broadcast.
 */
#define FAI_BROADCAST_CAPABLE 2
#define FAM_BROADCAST_CAPABLE (1U << FAI_BROADCAST_CAPABLE)
#define FAM_BCAST FAM_BROADCAST_CAPABLE

/**
 *  Face attribute "directcontrol"
 *
 * If true, the face should not be sent interests unless there is no
 * response from any other faces.  This may be used by an application that
 * can update the FIB on demand.
 */
#define FAI_DIRECT_CONTROL 3
#define FAM_DIRECT_CONTROL (1U << FAI_DIRECT_CONTROL)
#define FAM_DC FAM_DIRECT_CONTROL

/**
 *  Stateless enumerator for face attribute names
 *
 * Call with NULL to get the first name.  Returns NULL after the last name.
 * The order is unspecified.  Generated names are provided for private attrs.
 */
const char *faceattr_next_name(struct ccnd_handle *h, const char *name);

/** For debugging */
void ccnd_msg(struct ccnd_handle *, const char *, ...);

/** A PRNG returning 31-bit pseudo-random numbers */
uint32_t ccnd_random(struct ccnd_handle *);

/** look up a strategy class */
const struct strategy_class *strategy_class_from_id(const char *id);

extern const struct strategy_class ccnd_strategy_classes[];

#endif
