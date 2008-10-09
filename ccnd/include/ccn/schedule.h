/*
 * ccn/schedule.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 * Event scheduling
 *
 * $Id$
 */

#ifndef CCN_SCHEDULE_DEFINED
#define CCN_SCHEDULE_DEFINED

struct ccn_schedule;
struct ccn_scheduled_event;

/*
 * This is a two-part absolute time value, which might be seconds and 
 * microseconds but does not have to be.  The interpretation depends
 * on the client-provided gettime object.  The distance into the future
 * for which events may be scheduled will be limited by the number of
 * micros that will fit in an int.
 */
struct ccn_timeval {
    long s;
    unsigned micros;
};

struct ccn_gettime;
typedef void (*ccn_gettime_action)(const struct ccn_gettime *, struct ccn_timeval *);
struct ccn_gettime {
    char descr[8];
    ccn_gettime_action gettime;
    unsigned micros_per_base;  /* e.g., 1000000 for seconds, microseconds */
    void *data;                /* for private use by gettime */
};

/*
 * The scheduled action may return a non-positive value
 * if the event should not be scheduled to occur again,
 * or a positive number of micros. If (flags & CCN_SCHEDULE_CANCEL),
 * the action should clean up and not reschedule itself.
 * The clienth is the one passed to ccn_schedule_create; event-specific
 * client data may be stored in ev->evdata and ev->evint.
 */
#define CCN_SCHEDULE_CANCEL 0x10
typedef int (*ccn_scheduled_action)(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags);

struct ccn_scheduled_event {
    ccn_scheduled_action action;
    void *evdata;
    intptr_t evint;
};

/*
 * Create and destroy
 */
struct ccn_schedule *ccn_schedule_create(void *clienth,
                                         const struct ccn_gettime *clock);
void ccn_schedule_destroy(struct ccn_schedule **schedp);

/*
 * Accessor for the clock passed into create
 */
const struct ccn_gettime *ccn_schedule_get_gettime(struct ccn_schedule *);

/*
 * ccn_schedule_event: schedule a new event
 */
struct ccn_scheduled_event *ccn_schedule_event(
    struct ccn_schedule *sched,
    int micros,
    ccn_scheduled_action action,
    void *evdata,
    intptr_t evint);

/*
 * ccn_schedule_cancel: cancel a scheduled event
 * Cancels the event (calling action with CCN_SCHEDULE_CANCEL set)
 * Returns -1 if this is not possible.
 */
int ccn_schedule_cancel(struct ccn_schedule *, struct ccn_scheduled_event *);

/*
 * ccn_schedule_run: do any scheduled events
 * This executes any scheduled actions whose time has come.
 * The return value is the number of micros until the next
 * scheduled event, or -1 if there are none.
 */
int ccn_schedule_run(struct ccn_schedule *);

#endif
