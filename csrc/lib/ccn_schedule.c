/**
 * @file ccn_schedule.c
 * @brief Support for scheduling events.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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
#include <stddef.h>
#include <stdlib.h>
#include <limits.h>
#include <string.h>
#include <ccn/schedule.h>

/**
 * We use a heap structure (as in heapsort) to
 * keep track of the scheduled events to get O(log n)
 * behavior.
 */
struct ccn_schedule_heap_item {
    intptr_t event_time;
    struct ccn_scheduled_event *ev;
};

struct ccn_schedule {
    void *clienth;
    const struct ccn_gettime *clock;
    struct ccn_schedule_heap_item *heap;
    int heap_n;
    int heap_limit;
    int heap_height; /* this is validated just before use */
    int now;         /* internal micros corresponding to lasttime  */
    struct ccn_timeval lasttime; /* actual time when we last checked  */
    int time_has_passed; /* to prevent too-frequent time syscalls */
};

/*
 * update_epoch: reset sched->now to avoid wrapping
 */
static void
update_epoch(struct ccn_schedule *sched)
{
    struct ccn_schedule_heap_item *heap;
    int n;
    int i;
    int t = sched->now;
    heap = sched->heap;
    n = sched->heap_n;
    for (i = 0; i < n; i++)
        heap[i].event_time -= t;
    sched->now = 0;
}

static void
update_time(struct ccn_schedule *sched)
{
    struct ccn_timeval now = { 0 };
    int elapsed;
    if (sched->time_has_passed < 0)
        return; /* For testing with clock stopped */
    sched->clock->gettime(sched->clock, &now);
    // gettimeofday(&now, 0);
    if ((unsigned)(now.s - sched->lasttime.s) >= INT_MAX/4000000) {
        /* We have taken a backward or large step - do a repair */
        sched->lasttime = now;
    }
    sched->time_has_passed = 1;
    elapsed = now.micros - sched->lasttime.micros +
        sched->clock->micros_per_base * (now.s - sched->lasttime.s);
    if (elapsed + sched->now < elapsed)
        update_epoch(sched);
    sched->now += elapsed;
    sched->lasttime = now;
}

struct ccn_schedule *
ccn_schedule_create(void *clienth, const struct ccn_gettime *ccnclock)
{
    struct ccn_schedule *sched;
    if (ccnclock == NULL)
        return(NULL);
    sched = calloc(1, sizeof(*sched));
    if (sched != NULL) {
        sched->clienth = clienth;
        sched->clock = ccnclock;
        update_time(sched);
    }
    return(sched);
}

void
ccn_schedule_destroy(struct ccn_schedule **schedp)
{
    struct ccn_schedule *sched;
    struct ccn_scheduled_event *ev;
    struct ccn_schedule_heap_item *heap;
    int n;
    int i;
    sched = *schedp;
    if (sched == NULL)
        return;
    *schedp = NULL;
    heap = sched->heap;
    if (heap != NULL) {
        n = sched->heap_n;
        sched->heap = NULL;
        for (i = 0; i < n; i++) {
            ev = heap[i].ev;
            (ev->action)(sched, sched->clienth, ev, CCN_SCHEDULE_CANCEL);
            free(ev);
        }
        free(heap);
    }
    free(sched);
}

const struct ccn_gettime *
ccn_schedule_get_gettime(struct ccn_schedule *schedp) {
    return(schedp->clock);
}

/*
 * heap_insert: insert a new item
 * n is the total heap size, counting the new item
 * h must satisfy (n >> h) == 1
 */
static void
heap_insert(struct ccn_schedule_heap_item *heap, int micros,
            struct ccn_scheduled_event *ev, int h, int n)
{
    int i;
    for (i = (n >> h); i < n; i = (n >> --h)) {
        if (micros <= heap[i-1].event_time) {
            intptr_t d = heap[i-1].event_time;
            struct ccn_scheduled_event *e = heap[i-1].ev;
            heap[i-1].ev = ev;
            heap[i-1].event_time = micros;
            micros = d;
            ev = e;
        }
    }
    heap[n-1].event_time = micros;
    heap[n-1].ev = ev;
}

/*
 * heap_sift: remove topmost element
 * n is the total heap size, before removal
 */
static void
heap_sift(struct ccn_schedule_heap_item *heap, int n)
{
    int i, j;
    int micros;
    if (n < 1)
        return;
    micros = heap[n-1].event_time;
    for (i = 1, j = 2; j < n; i = j, j = 2 * j) {
        if (j + 1 < n && heap[j-1].event_time > heap[j].event_time)
            j += 1;
        if (micros < heap[j-1].event_time)
            break;
        heap[i-1] = heap[j-1];
    }
    heap[i-1] = heap[n-1];
    heap[n-1].ev = NULL;
    heap[n-1].event_time = 0;
}

/*
 * reschedule_event: schedule an event
 * ev is already set up and initialized
 */
static struct ccn_scheduled_event *
reschedule_event(
    struct ccn_schedule *sched,
    int micros,
    struct ccn_scheduled_event *ev)
{
    int lim;
    int n;
    int h;
    struct ccn_schedule_heap_item *heap;
    if (micros + sched->now < micros)
        update_epoch(sched);
    micros += sched->now;
    heap = sched->heap;
    n = sched->heap_n + 1;
    if (n > sched->heap_limit) {
        lim = sched->heap_limit + n;
        heap = realloc(sched->heap, lim * sizeof(heap[0]));
        if (heap == NULL) return(NULL);
        memset(&(heap[sched->heap_limit]), 0, (lim - n) * sizeof(heap[0]));
        sched->heap_limit = lim;
        sched->heap = heap;
    }
    sched->heap_n = n;
    h = sched->heap_height;
    while ((n >> h) > 1)
        sched->heap_height = ++h;
    while ((n >> h) < 1)
        sched->heap_height = --h;
    heap_insert(heap, micros, ev, h, n);
    return(ev);
}

/*
 * ccn_schedule_event: schedule a new event
 */
struct ccn_scheduled_event *
ccn_schedule_event(
    struct ccn_schedule *sched,
    int micros,
    ccn_scheduled_action action,
    void *evdata,
    intptr_t evint)
{
    struct ccn_scheduled_event *ev;
    ev = calloc(1, sizeof(*ev));
    if (ev == NULL) return(NULL);
    ev->action = action;
    ev->evdata = evdata;
    ev->evint = evint;
    update_time(sched);
    return(reschedule_event(sched, micros, ev));
}

/* Use a dummy action in cancelled events */ 
static int
ccn_schedule_cancelled_event(struct ccn_schedule *sched, void *clienth,
                             struct ccn_scheduled_event *ev, int flags)
{
    return(0);
}

/**
 * Cancel a scheduled event.
 *
 * Cancels the event (calling action with CCN_SCHEDULE_CANCEL set)
 * @returns 0 if OK, or -1 if this is not possible.
 */
int
ccn_schedule_cancel(struct ccn_schedule *sched, struct ccn_scheduled_event *ev)
{
    int res;
    if (ev == NULL)
        return(-1);
    res = (ev->action)(sched, sched->clienth, ev, CCN_SCHEDULE_CANCEL);
    if (res > 0)
        abort(); /* Bug in ev->action - bad return value */
    ev->action = &ccn_schedule_cancelled_event;
    ev->evdata = NULL;
    ev->evint = 0;
    return(0);
}

static void
ccn_schedule_run_next(struct ccn_schedule *sched)
{
    struct ccn_scheduled_event *ev;
    int micros;
    int res;
    if (sched->heap_n == 0) return;
    ev = sched->heap[0].ev;
    sched->heap[0].ev = NULL;
    micros = sched->heap[0].event_time - sched->now;
    heap_sift(sched->heap, sched->heap_n--);
    res = (ev->action)(sched, sched->clienth, ev, 0);
    if (res <= 0) {
        free(ev);
        return;
    }
    /*
     * Try to reschedule based on the time the
     * event was originally scheduled, but if we have gotten
     * way behind, just use the current time.
     */
    if (micros < -(int)(sched->clock->micros_per_base))
        micros = 0;
    reschedule_event(sched, micros + res, ev);
}

/*
 * ccn_schedule_run: do any scheduled events
 * This executes any scheduled actions whose time has come.
 * The return value is the number of micros until the next
 * scheduled event, or -1 if there are none.
 */
int
ccn_schedule_run(struct ccn_schedule *sched)
{
    update_time(sched);
    while (sched->heap_n > 0 && sched->heap[0].event_time <= sched->now) {
        sched->time_has_passed = 0;
        ccn_schedule_run_next(sched);
        if (sched->time_has_passed)
            update_time(sched);
    }
    if (sched->heap_n == 0)
        return(-1);
    return(sched->heap[0].event_time - sched->now);
}

#ifdef TESTSCHEDULE
// cc -g -o testschedule -DTESTSCHEDULE=main -I../include ccn_schedule.c
#include <stdio.h>
#include <sys/time.h>

static void
my_gettime(const struct ccn_gettime *self, struct ccn_timeval *result)
{
    struct timeval now = {0};
    gettimeofday(&now, 0);
    result->s = now.tv_sec;
    result->micros = now.tv_usec;
}

static struct ccn_gettime gt = {"getTOD", &my_gettime, 1000000, NULL};

static void
testtick(struct ccn_schedule *sched)
{
    sched->now = sched->heap[0].event_time + 1;
    printf("%ld: ", (long)sched->heap[0].event_time);
    ccn_schedule_run_next(sched);
    printf("\n");
}
static char dd[] = "ABDEFGHI";
#define SARGS struct ccn_schedule *sched, void *clienth, struct ccn_scheduled_event *ev, int flags
static int A(SARGS) { if (flags & CCN_SCHEDULE_CANCEL) return(0);
                      printf("A"); return 70000000; }
static int B(SARGS) { printf("B"); return 0; }
static int C(SARGS) { printf("C"); return 0; }
static int D(SARGS) { if (flags & CCN_SCHEDULE_CANCEL) return(0);
                      printf("D");  return 30000000; }
static struct ccn_schedule_heap_item tst[7];
int TESTSCHEDULE()
{
    struct ccn_schedule *s = ccn_schedule_create(dd+5, &gt);
    int i;
    struct ccn_scheduled_event *victim = NULL;
    // s->heap = tst; s->heap_limit = 7; // uncomment for easy debugger display
    s->time_has_passed = -1; /* don't really ask for time */
    ccn_schedule_event(s, 11111, A, dd+4, 11111);
    ccn_schedule_event(s, 1, A, dd, 1);
    ccn_schedule_event(s, 111, C, dd+2, 111);
    victim = ccn_schedule_event(s, 1111111, A, dd+6, 1111111);
    ccn_schedule_event(s, 11, B, dd+1, 11);
    testtick(s);
    ccn_schedule_event(s, 1111, D, dd+3, 1111);
    ccn_schedule_event(s, 111111, B, dd+5, 111111);
    for (i = 0; i < 100; i++) {
        if (i == 50) { ccn_schedule_cancel(s, victim); victim = NULL; }
        testtick(s);
    }
    ccn_schedule_destroy(&s);
    return(0);
}
#endif
