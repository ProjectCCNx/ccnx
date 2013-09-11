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
 * This is intended to cause a compilation error if T becomes too big.
 */
#define CCN_STATESIZECHECK(XX, T) \
    struct XX {char x[(int)(sizeof(struct nameprefix_state)-sizeof(T))];}

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
/* On the CCNST_FIRST call for an Interest to a given prefix,
 * there will be one face marked downstream, and the eligible faces
 * are those which are neither DCFACE nor DNSTREAM.
 * Calling send_interest() manages the flag settings.
 *
 * Once the CCNST_ADDDNSTRM (additional downstream) operation is added, 
 * on that call there will be multiple downstream faces in the
 * PFI list and there will be a non-downstream face entry for any face(s)
 * that were previously downstream.
 * 
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
#define CCND_PFI_DCFACE  0x10000    /**< This upstream is a Direct Control face (gets data if unanswered for a long time) */


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
 *                  a givien prefix.  If the strategy uses parameters, this
 *                  call is the appropriate time to parse them and save the
 *                  resulting values in the private instance state for
 *                  rapid access in the more time-critical calls.
   XXX - we need a way to indicate a bad parameter string.
 *
 * CCNST_FIRST      indicates the creation of a new PIT entry due to an
 *                  arriving interest.  Since there was no existing state
 *                  for similar interests, there will be exactly one
 *                  downstream in the pfi list.  The upstreams in the list
 *                  are those that the FIB has indicated are eligible to
 *                  receive the interests.  The strategy callout should
 *                  examine each upstream that has the CCND_PFI_SENDUPST
    XXX - premarking is NYI
 *                  bit set, and either clear the bit to avoid sending
 *                  the interest on that face, or set the expiry time
 *                  according to when the interest should be sent.
 *                  The expiry times are initially set to the current time,
 *                  so if the strategy callout does nothing, the interest
 *                  will be sent to the marked upstreams as soon as the
 *                  callout returns.
 *                  At the time of the CCNST_FIRST call, some upstreams may have
 *                  already been fed (such as those with the TAP forwarding
 *                  flag).  These will have CCND_PFI_SENDUPST clear and
 *                  CCND_PFI_UPENDING set.  The strategy callout should
 *                  generally ignore such entries.
 *                  The faceid indicates the initial downstream face.
 *
 * CCNST_NEWUP      indicates a new upstream has been added.  This may be
 *                  because of a new prefix registration, or because
 *                  a second downstream has made the initial upstream
 *                  eligible.  The new upstream will have CCND_PFI_SENDUPST
 *                  set and an expriry in the near future, similar to the
 *                  situation for CCNST_FIRST.  The strategy should adjust
 *                  these as appropriate.
 *                  The faceid indicates the new upstream face.
 *
 * CCNST_NEWDN      a new downstream has been added, due to the arrival of
 *                  similar interest on a new face.
 *                  The faceid indicates the new downstream face.
 *
 * CCNST_EXPUP      indicates an upstream is eligible to receive a new
 *                  copy of the interest, because any previously sent
 *                  interest has expired and an unexpired downsream is
 *                  available.  The processing of CCND_PFI_SENDUPST and
 *                  the expiry are similar to the CCNST_NEWUP case.
 *                  The faceid indicates the affected upstream face.
 *
 * CCNST_EXPDN      indicates the downstream is expiring.
 *                  The faceid indicates the expiring downstream face.
 *
 * CCNST_REFRESH    indicates a new, similar, interest message has arrived on
 *                  a previously existing downstream face.  Its expiry
 *                  will have been updated accordingly.  The strategy
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
    CCNST_NEWUP,    /* additional upstream face
                       (new registration, etc.) */
    CCNST_NEWDN,    /* additional downstream face
                       (similar interest arrived from a new face) */
    CCNST_EXPUP,    /* upstream is expiring */
    CCNST_EXPDN,    /* downstream is expiring */
    CCNST_REFRESH,  /* downstream refreshed */
// notification
// removal of upstream
// removal of downstream
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
void
strategy_settimer(struct ccnd_handle *h, struct interest_entry *ie,
                  int usec, enum ccn_strategy_op op);

/** A PRNG returning 31-bit pseudo-random numbers */
uint32_t ccnd_random(struct ccnd_handle *);

extern const struct strategy_class ccnd_strategy_classes[];

void strategy1_callout(struct ccnd_handle *h,
                       struct strategy_instance *instance,
                       struct ccn_strategy *s,
                       enum ccn_strategy_op op,
                       unsigned faceid);

void strategy2_callout(struct ccnd_handle *h,
                       struct strategy_instance *instance,
                       struct ccn_strategy *s,
                       enum ccn_strategy_op op,
                       unsigned faceid);

#endif
