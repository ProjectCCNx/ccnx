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

/** Ops for strategy callout */
enum ccn_strategy_op {
    CCNST_NOP,      /* no-operation */
    CCNST_INIT,     /* initialize strategy, allocate instance state */
    CCNST_FIRST,    /* newly created interest entry (pit entry) */
// CCNST_ADDDNSTRM additional downstream face (same interest arrived from another face)
// CCNST_ADDUPSTRM additional upstream face (e.g. new registration)
// refresh
// notification
// expiry of upstream
// expiry of downstream
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
