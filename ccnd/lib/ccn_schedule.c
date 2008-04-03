#include <stddef.h>
#include <stdlib.h>
#include <limits.h>
#include <sys/time.h>
#include <ccn/schedule.h>


/*
 * We use a heap structure (as in heapsort) to
 * keep track of the scheduled events to get O(log n)
 * behavior.  Times are stored as deltas to the parent node
 * so that we don't need to worry about awkward boundary
 * cases when times wrap.
 */
struct ccn_schedule_heap_item {
    intptr_t delta_to_parent;
    struct ccn_scheduled_event *ev;
};

struct ccn_schedule {
    void *clienth;
    int heap_n;
    int heap_limit;
    int heap_height;
    struct ccn_schedule_heap_item *heap;
};

struct ccn_schedule *
ccn_schedule_create(void *clienth)
{
    struct ccn_schedule *sched;
    sched = calloc(1, sizeof(*sched));
    if (sched != NULL) {
        sched->clienth = clienth;
    }
    return(sched);
}

void
ccn_schedule_destroy(struct ccn_schedule **schedp)
{
    if (*schedp != NULL) {
        free(*schedp);
        *schedp = NULL;
    }
}

static void
heap_insert(struct ccn_schedule_heap_item *heap, int microsec,
            struct ccn_scheduled_event *ev, int h, int n)
{
    int i;
    struct ccn_schedule_heap_item *this;
    for (i = (n >> h); i < n; i = (n >> --h)) {
        this = &(heap[i-1]);
        if (microsec <= this->delta_to_parent) {
            int d = this->delta_to_parent - microsec;
            struct ccn_scheduled_event *e = this->ev;
            /* update deltas for both children */
            if (2 * i <= n)
                heap[2 * i - 1].delta_to_parent += d;
            if (2 * i + 1 <= n)
                heap[2 * i].delta_to_parent += d;
            this->delta_to_parent = microsec;
            this->ev = ev;
            microsec = d;
            ev = e;
        }
        else
            microsec -= this->delta_to_parent;
    }
    this = &(heap[n-1]);
    this->delta_to_parent = microsec;
    this->ev = ev;
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
    int lim;
    int n;
    int h;
    struct ccn_schedule_heap_item *heap;
    heap = sched->heap;
    n = sched->heap_n + 1;
    if (n > sched->heap_limit) {
        lim = sched->heap_limit + n;
        heap = realloc(sched->heap, lim * sizeof(heap[0]));
        if (heap == NULL) return(NULL);
        sched->heap_limit = lim;
        sched->heap = heap;
    }
    sched->heap_n = n;
    heap[n-1].ev = NULL;
    heap[n-1].delta_to_parent = INT_MAX/2;
    h = sched->heap_height;
    while ((n >> h) > 1)
        sched->heap_height = ++h;
    ev = calloc(1, sizeof(*ev)); // XXX - should keep our own cache
    if (ev == NULL) return(NULL);
    ev->action = action;
    ev->evdata = evdata;
    ev->evint = evint;
    heap_insert(heap, microsec, ev, h, n);
    return(ev);
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

#if 0
static void
ccn_schedule_run_next(struct ccn_schedule *sched)
{
    struct ccn_scheduled_event *ev;
    int microsec;
    int h = sched->heap_height;
    int n = sched->heap_n;
    if (sched->heap_n == 0) return;
    ev = sched->heap[0].ev;
    microsec = sched->heap[0].delta_to_parent;
    if (n >= 3) {
        if (sched->heap[1].delta_to_parent < sched->heap[2].delta_to_parent) {
            advance = sched->heap[1].delta_to_parent;
        }
        else
            advance = sched->heap[2].delta_to_parent;
        
}
#endif

/*
 * ccn_schedule_run: do any scheduled events
 * This executes any scheduled actions whose time has come.
 * The return value is the number of microseconds until the next
 * scheduled event, or -1 if there are none.
 */
int
ccn_schedule_run(struct ccn_schedule *sched)
{
    return(-1);
}

#ifdef TESTSCHEDULE
// cc -g -o xx -DTESTSCHEDULE=main -I../include ccn_schedule.c
static char dd[] = "ABDEFGHI";
static int A(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags) { return 'A'; }
static int B(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags) { return 'B'; }
static int C(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags) { return 'C'; }
static int D(
    struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags) { return 'D'; }
static struct ccn_schedule_heap_item tst[7];
int TESTSCHEDULE() {
    struct ccn_schedule *s = ccn_schedule_create(dd+5);
    s->heap = tst; // for easy debugger display
    s->heap_limit = 7;
    ccn_schedule_event(s, 11111, A, dd+4, 11111);
    ccn_schedule_event(s, 1, A, dd, 1);
    ccn_schedule_event(s, 111, C, dd+2, 111);
    ccn_schedule_event(s, 1111111, A, dd+6, 1111111);
    ccn_schedule_event(s, 11, B, dd+1, 11);
    ccn_schedule_event(s, 1111, D, dd+3, 1111);
    ccn_schedule_event(s, 111111, A, dd+5, 111111);
    return(0);
}
#endif
