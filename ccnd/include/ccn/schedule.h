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

#include <stdint.h>

struct ccn_schedule;
struct ccn_scheduled_event;
/*
 * The scheduled action may return a non-positive value
 * if the event should not be scheduled to occur again,
 * or a positive number of microseconds. If (flags & CCN_SCHEDULE_CANCEL),
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

struct ccn_schedule *ccn_schedule_create(void *clienth);
void ccn_schedule_destroy(struct ccn_schedule **schedp);

/*
 * ccn_schedule_event: schedule a new event
 */
struct ccn_scheduled_event *ccn_schedule_event(
    struct ccn_schedule *sched,
    int microsec,
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
 * The return value is the number of microseconds until the next
 * scheduled event, or -1 if there are none.
 */
int ccn_schedule_run(struct ccn_schedule *);

#endif
