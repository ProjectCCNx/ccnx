#include <stddef.h>
#include <stdlib.h>
#include <limits.h>
#include <string.h>
#include <sys/time.h>
#include <ccn/schedule.h>

/*
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
    struct ccn_schedule_heap_item *heap;
    int heap_n;
    int heap_limit;
    int heap_height;
    int now;      /* internal microsec corresponding to lasttime  */
    struct timeval lasttime; /* actual time when we last checked  */
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
    struct timeval now = { 0 };
    int elapsed;
    if (sched->time_has_passed < 0)
        return; /* For testing with clock stopped */
    gettimeofday(&now, 0);
    if ((unsigned)(now.tv_sec - sched->lasttime.tv_sec) >= INT_MAX/4000000) {
        /* We have taken a large step forward or backward - do a repair */
        sched->lasttime = now;
    }
    sched->time_has_passed = 1;
    elapsed = now.tv_usec - sched->lasttime.tv_usec +
        1000000 * (now.tv_sec - sched->lasttime.tv_sec);
    if (elapsed + sched->now < elapsed)
        update_epoch(sched);
    sched->now += elapsed;
    sched->lasttime = now;
}

struct ccn_schedule *
ccn_schedule_create(void *clienth)
{
    struct ccn_schedule *sched;
    sched = calloc(1, sizeof(*sched));
    if (sched != NULL) {
        sched->clienth = clienth;
        update_time(sched);
    }
    return(sched);
}

void
ccn_schedule_destroy(struct ccn_schedule **schedp)
{
    if (*schedp != NULL) {
        // XXX - there is other stuff to free
        free(*schedp);
        *schedp = NULL;
    }
}

/*
 * heap_insert: insert a new item
 * n is the total heap size, counting the new item
 * h must satisfy (n >> h) == 1
 */
static void
heap_insert(struct ccn_schedule_heap_item *heap, int microsec,
            struct ccn_scheduled_event *ev, int h, int n)
{
    int i;
    for (i = (n >> h); i < n; i = (n >> --h)) {
        if (microsec <= heap[i-1].event_time) {
            intptr_t d = heap[i-1].event_time;
            struct ccn_scheduled_event *e = heap[i-1].ev;
            heap[i-1].ev = ev;
            heap[i-1].event_time = microsec;
            microsec = d;
            ev = e;
        }
    }
    heap[n-1].event_time = microsec;
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
    int microsec;
    if (n < 1)
        return;
    microsec = heap[n-1].event_time;
    for (i = 1, j = 2; j < n; i = j, j = 2 * j) {
        if (j + 1 < n && heap[j-1].event_time > heap[j].event_time)
            j += 1;
        if (microsec < heap[j-1].event_time)
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
    int microsec,
    struct ccn_scheduled_event *ev)
{
    int lim;
    int n;
    int h;
    struct ccn_schedule_heap_item *heap;
    if (microsec + sched->now < microsec)
        update_epoch(sched);
    microsec += sched->now;
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
    heap_insert(heap, microsec, ev, h, n);
    return(ev);
}

/*
 * ccn_schedule_event: schedule a new event
 */
struct ccn_scheduled_event *
ccn_schedule_event(
    struct ccn_schedule *sched,
    int microsec,
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
    return(reschedule_event(sched, microsec, ev));
}

/*
 * ccn_schedule_cancel: cancel a scheduled event
 * Cancels the event (calling action with CCN_SCHEDULE_CANCEL set)
 * Returns -1 if this is not possible.
 */
int
ccn_schedule_cancel(struct ccn_schedule *sched, struct ccn_scheduled_event *ev)
{
    return(-1);
}

static void
ccn_schedule_run_next(struct ccn_schedule *sched)
{
    struct ccn_scheduled_event *ev;
    int microsec;
    int res;
    if (sched->heap_n == 0) return;
    ev = sched->heap[0].ev;
    sched->heap[0].ev = NULL;
    microsec = sched->heap[0].event_time - sched->now;
    heap_sift(sched->heap, sched->heap_n--);
    while ((sched->heap_n >> sched->heap_height) < 1)
        sched->heap_height--;
    res = (ev->action)(sched, sched->clienth, ev, 0);
    if (res <= 0) {
        free(ev); // XXX should quarantine this
        return;
    }
    /*
     * Try to reschedule based on the time the
     * event was originally scheduled, but if we have gotten
     * way behind, just use the current time.
     */
    if (microsec < -10000000)
        microsec = 0;
    reschedule_event(sched, microsec + res, ev);
}

/*
 * ccn_schedule_run: do any scheduled events
 * This executes any scheduled actions whose time has come.
 * The return value is the number of microseconds until the next
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
static void
testtick(struct ccn_schedule *sched)
{
    sched->now = sched->heap[0].event_time + 1;
    printf("%d: ", sched->heap[0].event_time);
    ccn_schedule_run_next(sched);
    printf("\n");
}
static char dd[] = "ABDEFGHI";
static int A(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags) { printf("A"); return 70000000; }
static int B(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags) { printf("B"); return 0; }
static int C(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags) { printf("C"); return 0; }
static int D(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags) { printf("D");  return 30000000; }
static struct ccn_schedule_heap_item tst[7];
int TESTSCHEDULE() {
    struct ccn_schedule *s = ccn_schedule_create(dd+5);
    int i;
    s->heap = tst; // for easy debugger display
    s->heap_limit = 7;
    s->time_has_passed = -1; /* don't really ask for time */
    ccn_schedule_event(s, 11111, A, dd+4, 11111);
    ccn_schedule_event(s, 1, A, dd, 1);
    ccn_schedule_event(s, 111, C, dd+2, 111);
    ccn_schedule_event(s, 1111111, A, dd+6, 1111111);
    ccn_schedule_event(s, 11, B, dd+1, 11);
    testtick(s);
    ccn_schedule_event(s, 1111, D, dd+3, 1111);
    ccn_schedule_event(s, 111111, B, dd+5, 111111);
    for (i = 0; i < 100; i++) {
        testtick(s);
    }
    return(0);
}
#endif
