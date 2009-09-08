/**
 * @file ccncatchunks2.c
 * Reads stuff written by ccnsendchunks, writes to stdout.
 */
#include <sys/time.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/schedule.h>
#include <ccn/uri.h>

#define CHUNK_SIZE 1024
#define PIPELIMIT (1U << 7)
//#define GOT_HERE() fprintf(stderr, "LINE %d\n", __LINE__)
#define GOT_HERE() ((void)(__LINE__))

struct excludestuff;

struct ooodata {
    struct ccn_closure closure;     /* closure per slot */
    unsigned char *raw_data;        /* content that has arrived out-of-order */
    size_t raw_data_size;           /* its size (plus 1) in bytes */
};

struct mydata {
    struct ccn *h;
    int allow_stale;
    unsigned ooo_base;
    unsigned ooo_count;
    unsigned curwindow;
    unsigned maxwindow;
    unsigned sendtime;
    unsigned sendtime_slot;
    unsigned rtt;
    unsigned rtte;
    unsigned backoff;
    struct ccn_charbuf *name;
    struct ccn_charbuf *templ;
    struct excludestuff *excl;
    struct ccn_schedule *sched;
    struct ccn_scheduled_event *report;
    struct ccn_scheduled_event *holefiller;
    intmax_t interests_sent;
    intmax_t pkts_recvd;
    intmax_t delivered;
    intmax_t junk;
    intmax_t holes;
    intmax_t timeouts;
    intmax_t dups;
    intmax_t lastcheck;
    intmax_t unverified;
    struct ooodata ooo[PIPELIMIT];
};

struct excludestuff {
    struct excludestuff* next;
    unsigned char *data;
    size_t size;
};

static int fill_holes(struct ccn_schedule *sched, void *clienth, 
                      struct ccn_scheduled_event *ev, int flags);

static FILE* logstream = NULL;

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] [-p n] ccn:/a/b\n"
            "   Reads stuff written by ccnsendchunks under"
            " the given uri and writes to stdout\n"
            "   -a - allow stale data\n"
            "   -p n - use up to n pipeline slots\n",
            progname);
    exit(1);
}

static void
mygettime(const struct ccn_gettime *self, struct ccn_timeval *result)
{
    struct timeval now = {0};
    gettimeofday(&now, 0);
    result->s = now.tv_sec;
    result->micros = now.tv_usec;
}

static struct ccn_gettime myticker = {
    "timer",
    &mygettime,
    1000000,
    NULL
};

static void
update_rtt(struct mydata *md, int incoming, unsigned slot)
{
    struct timeval now = {0};
    unsigned t, delta, rtte;
    
    if (!incoming && md->sendtime_slot == ~0)
        md->sendtime_slot = slot;
    if (slot != md->sendtime_slot)
        return;
    gettimeofday(&now, 0);
    t = ((unsigned)(now.tv_sec) * 1000000) + (unsigned)(now.tv_usec);
    if (incoming) {
        delta = t - md->sendtime;
        md->rtt = delta;
        if (delta <= 30000000) {
            rtte = md->rtte;
            if (delta > rtte)
                rtte = rtte + (rtte >> 3);
            else
                rtte = rtte - (rtte >> 7);
            if (rtte < 127)
                rtte = delta;
            md->rtte = rtte;
        }
        if (md->holefiller == NULL)
            md->holefiller = ccn_schedule_event(md->sched, 10000, &fill_holes, NULL, 0);
        md->sendtime_slot = ~0;
        if (logstream)
            fprintf(logstream,
                    "%ld.%06u ccncatchunks2: "
                    "%jd isent, %jd recvd, %jd junk, %jd holes, %jd t/o, %jd unvrf, "
                    "%u curwin, %u rtt, %u rtte\n",
                    (long)now.tv_sec,
                    (unsigned)now.tv_usec,
                    md->interests_sent,
                    md->pkts_recvd,
                    md->junk,
                    md->holes,
                    md->timeouts,
                    md->unverified,
                    md->curwindow,
                    md->rtt,
                    md->rtte
                    );
    }
    else
        md->sendtime = t;
}

static int
reporter(struct ccn_schedule *sched, void *clienth, 
         struct ccn_scheduled_event *ev, int flags)
{
    struct timeval now = {0};
    struct mydata *md = clienth;
    gettimeofday(&now, 0);
    fflush(stdout);
    fprintf(stderr,
            "%ld.%06u ccncatchunks2[%d]: "
            "%jd isent, %jd recvd, %jd junk, %jd holes, %jd t/o, %jd unvrf, "
            "%u curwin, %u rtt, %u rtte\n",
            (long)now.tv_sec,
            (unsigned)now.tv_usec,
            (int)getpid(),
            md->interests_sent,
            md->pkts_recvd,
            md->junk,
            md->holes,
            md->timeouts,
            md->unverified,
            md->curwindow,
            md->rtt,
            md->rtte
            );
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        md->report = NULL;
        return(0);
    }
    return(3000000);
}

int
count_excludestuff(struct excludestuff* p)
{
    int n;
    for (n = 0; p != NULL; p = p->next)
        n++;
    return(n);
}

void
fill_bloom(struct ccn_bloom *b, struct excludestuff* excl)
{
    struct excludestuff* p;
    for (p = excl; p != NULL; p = p->next)
        ccn_bloom_insert(b, p->data, p->size);
}

void
clear_excludes(struct mydata *md)
{
    struct excludestuff* e;
    while (md->excl != NULL) {
        e = md->excl;
        md->excl = e->next;
        free(e->data);
        free(e);
    }
}

void
note_new_exclusion(struct mydata *md, const unsigned char *ccnb,
                   size_t start, size_t stop)
{
    struct excludestuff* e;
    unsigned char *data;
    if (start < stop) {
        e = calloc(1, sizeof(*e));
        data = calloc(1, stop-start);
        memcpy(data, ccnb + start, stop - start);
        e->data = data;
        e->size = stop - start;
        e->next = md->excl;
        md->excl = e;
    }
}

struct ccn_charbuf *
make_template(struct mydata *md)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    int nexcl;
    struct ccn_bloom *b = NULL;
    int i;
    unsigned char seed[4];
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    // XXX - use pubid if possible
    ccn_charbuf_append_tt(templ, CCN_DTAG_AdditionalNameComponents, CCN_DTAG);
    ccnb_append_number(templ, 1);
    ccn_charbuf_append_closer(templ); /* </AdditionalNameComponents> */
    if (md->allow_stale) {
        ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
        ccnb_append_number(templ,
                                                CCN_AOK_DEFAULT | CCN_AOK_STALE);
        ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
    }
    nexcl = count_excludestuff(md->excl);
    if (nexcl != 0) {
        long r = lrand48();
        for (i = 0; i < 4; i++) {
            seed[i] = r;
            r <<= 8;
        }
        if (nexcl < 8) nexcl = 8;
        b = ccn_bloom_create(nexcl, seed);
        fill_bloom(b, md->excl);
        ccn_charbuf_append_tt(templ, CCN_DTAG_ExperimentalResponseFilter, CCN_DTAG);
        i = ccn_bloom_wiresize(b);
        ccn_charbuf_append_tt(templ, i, CCN_BLOB);
        ccn_bloom_store_wire(b, ccn_charbuf_reserve(templ, i), i);
        templ->length += i;
        ccn_charbuf_append_closer(templ);
    }
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
}

static struct ccn_charbuf *
sequenced_name(struct mydata *md, uintmax_t seq)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *temp = NULL;
    
    name = ccn_charbuf_create();
    temp = ccn_charbuf_create();
    ccn_charbuf_append(name, md->name->buf, md->name->length);
    ccn_charbuf_putf(temp, "%ju", seq);
    ccn_name_append(name, temp->buf, temp->length);
    ccn_charbuf_destroy(&temp);
    return(name);
}

static void
ask_more(struct mydata *md, uintmax_t seq)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    int res;
    unsigned slot;
    struct ccn_closure *cl = NULL;
    
    slot = seq % PIPELIMIT;
    cl = &md->ooo[slot].closure;
    if (cl->intdata == -1)
        cl->intdata = seq;
    assert(cl->intdata == seq);
    assert(md->ooo[slot].raw_data_size == 0);
    name = sequenced_name(md, seq);
    clear_excludes(md); // XXX Should not do this unconditionally
    templ = make_template(md);
    update_rtt(md, 0, slot);
    res = ccn_express_interest(md->h, name, -1, cl, templ);
    if (res < 0) abort();
    md->interests_sent++;
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    if (seq == md->delivered + md->ooo_count)
        md->ooo_count++;
    assert(seq >= md->delivered);
    assert(seq < md->delivered + md->ooo_count);
    assert(md->ooo_count < PIPELIMIT);
}

static enum ccn_upcall_res
hole_filled(struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    if (kind == CCN_UPCALL_FINAL)
        free(selfp);
    return(CCN_UPCALL_RESULT_OK);
}

static int
fill_holes(struct ccn_schedule *sched, void *clienth, 
         struct ccn_scheduled_event *ev, int flags)
{
    struct mydata *md = clienth;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_closure *cl = NULL;
    unsigned backoff;
    int delay;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        md->holefiller = NULL;
        return(0);
    }
    backoff = md->backoff;
    if (md->delivered == md->lastcheck && md->ooo_count > 0) {
        if (backoff == 0) {
            md->holes++;
            fprintf(stderr, "*** Hole at %jd\n", md->delivered);
            reporter(sched, md, NULL, 0);
            md->curwindow = 1;
            cl = calloc(1, sizeof(*cl));
            cl->p = &hole_filled;
            name = sequenced_name(md, md->delivered);
            templ = make_template(md);
            ccn_express_interest(md->h, name, -1, cl, templ);
            md->interests_sent++;
            ccn_charbuf_destroy(&templ);
            ccn_charbuf_destroy(&name);
        }
        if ((6000000 >> backoff) > md->rtte)
            backoff++;
    }
    else {
        md->lastcheck = md->delivered;
        backoff = 0;
    }
    md->backoff = backoff;
    delay = (md->rtte << backoff);
    if (delay < 10000)
        delay = 10000;
    return(delay);
}

enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *data = NULL;
    size_t data_size = 0;
    size_t written;
    int res;
    struct mydata *md = selfp->data;
    unsigned slot;
    
    if (kind == CCN_UPCALL_FINAL) {
        selfp->intdata = -1;
        return(CCN_UPCALL_RESULT_OK);
    }
GOT_HERE();
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT) {
        md->timeouts++;
        if (selfp->refcount > 1 || selfp->intdata == -1)
            return(CCN_UPCALL_RESULT_OK);
        md->interests_sent++;
        md->curwindow = 1;
        // XXX - may need to reseed bloom filter
        return(CCN_UPCALL_RESULT_REEXPRESS);
    }
GOT_HERE();
    if (kind != CCN_UPCALL_CONTENT && kind != CCN_UPCALL_CONTENT_UNVERIFIED)
        return(CCN_UPCALL_RESULT_ERR);
    assert(md != NULL);
    md->pkts_recvd++;
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED) md->unverified++;
    if (selfp->intdata == -1) {
        /* Outside the window we care about. Toss it. */
        md->dups++;
        return(CCN_UPCALL_RESULT_OK);
    }
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();
    if (data_size > CHUNK_SIZE) {
        /* For us this is spam. Need to try again, excluding this one. */
        const unsigned char *ib = NULL; /* info->interest_ccnb */
        struct ccn_indexbuf *ic = NULL;
        struct ccn_charbuf *name = NULL;
        struct ccn_charbuf *templ = NULL;
        
        ib = info->interest_ccnb;
        ic = info->interest_comps;
        
        md->junk++;
        fprintf(stderr, "*** skip spam at block %d\n", (int)selfp->intdata);
        name = ccn_charbuf_create();
        ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 1]);
        note_new_exclusion(md, ccnb,
                           info->pco->offset[CCN_PCO_B_Signature],
                           info->pco->offset[CCN_PCO_E_Signature]);
        templ = make_template(md);
        res = ccn_express_interest(info->h, name, -1, selfp, templ);
        md->interests_sent++;
        update_rtt(md, 0, ((uintptr_t)selfp->intdata) % PIPELIMIT);
        if (res < 0)
            abort();
        ccn_charbuf_destroy(&templ);
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_OK);
    }
GOT_HERE();
    /* OK, we will accept this block. */
    slot = ((uintptr_t)selfp->intdata) % PIPELIMIT;
    assert(selfp == &md->ooo[slot].closure);
    if (slot != md->ooo_base || md->ooo_count == 0) {
        /* out-of-order data, save for later */
        struct ooodata *ooo = &md->ooo[slot];
        if (ooo->raw_data_size == 0) {
GOT_HERE();
            update_rtt(md, 1, slot);
            ooo->raw_data = malloc(data_size);
            memcpy(ooo->raw_data, data, data_size);
            ooo->raw_data_size = data_size + 1;
        }
        else
            md->dups++;
        if (md->curwindow > 1)
            md->curwindow--;
    }
    else {
        assert(md->ooo[slot].raw_data_size == 0);
        update_rtt(md, 1, slot);
        md->ooo[slot].closure.intdata = -1;
        md->delivered++;
GOT_HERE();
        written = fwrite(data, data_size, 1, stdout);
        if (written != 1)
            exit(1);
        /* A short block signals EOF for us. */
        if (data_size < CHUNK_SIZE) {
            ccn_schedule_destroy(&md->sched);
            exit(0);
        }
        md->ooo_count--;
        slot = (slot + 1) % PIPELIMIT;
        if (md->curwindow < md->maxwindow)
            md->curwindow++;
        while (md->ooo_count > 0 && md->ooo[slot].raw_data_size != 0) {
            struct ooodata *ooo = &md->ooo[slot];
            md->delivered++;
            written = fwrite(ooo->raw_data, ooo->raw_data_size - 1, 1, stdout);
            if (written != 1)
                exit(1);
            /* A short block signals EOF for us. */
            if (ooo->raw_data_size - 1 < CHUNK_SIZE) {
                ccn_schedule_destroy(&md->sched);
                exit(0);
            }
            free(ooo->raw_data);
            ooo->raw_data = NULL;
            ooo->raw_data_size = 0;
            slot = (slot + 1) % PIPELIMIT;
            md->ooo_count--;
        }
        md->ooo_base = slot;
    }
    
    /* Ask for the next one or two */
    if (md->ooo_count < md->curwindow)
        ask_more(md, md->delivered + md->ooo_count);
    if (md->ooo_count < md->curwindow)
        ask_more(md, md->delivered + md->ooo_count);
    
    return(CCN_UPCALL_RESULT_OK);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_closure *incoming = NULL;
    const char *arg = NULL;
    int res;
    int micros;
    char ch;
    struct mydata *mydata;
    int allow_stale = 0;
    int i;
    unsigned maxwindow = PIPELIMIT-1;
    
    if (maxwindow > 31)
        maxwindow = 31;
    
    while ((ch = getopt(argc, argv, "hap:")) != -1) {
        switch (ch) {
            case 'a':
                allow_stale = 1;
                break;
            case 'p':
                res = atoi(optarg);
                if (1 <= res && res < PIPELIMIT)
                    maxwindow = res;
                else
                    usage(argv[0]);
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    arg = argv[optind];
    if (arg == NULL)
        usage(argv[0]);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, arg);
    if (res < 0) {
        fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], arg);
        exit(1);
    }
    if (argv[optind + 1] != NULL)
        fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    
    mydata = calloc(1, sizeof(*mydata));
    mydata->h = ccn;
    mydata->name = name;
    mydata->allow_stale = allow_stale;
    mydata->excl = NULL;
    mydata->sched = ccn_schedule_create(mydata, &myticker);
    mydata->report = ccn_schedule_event(mydata->sched, 0, &reporter, NULL, 0);
    mydata->holefiller = NULL;
    mydata->maxwindow = maxwindow;
    for (i = 0; i < PIPELIMIT; i++) {
        incoming = &mydata->ooo[i].closure;
        incoming->p = &incoming_content;
        incoming->data = mydata;
        incoming->intdata = -1;
    }
    mydata->ooo_base = 0;
    mydata->ooo_count = 0;
    mydata->curwindow = 1;
    logstream = NULL;
    // logstream = fopen("xxxxxxxxxxxxxxlogstream" + (unsigned)getpid()%10, "wb"); 
    ask_more(mydata, 0);
    /* Run a little while to see if there is anything there */
    res = ccn_run(ccn, 500);
    if (mydata->delivered == 0) {
        fprintf(stderr, "%s: not found: %s\n", argv[0], arg);
        exit(1);
    }
    /* We got something, run until end of data or somebody kills us */
    while (res >= 0) {
        micros = ccn_schedule_run(mydata->sched);
        if (micros < 0)
            micros = 10000000;
        res = ccn_run(ccn, micros / 1000);
    }
    ccn_schedule_destroy(&mydata->sched);
    ccn_destroy(&ccn);
    exit(res < 0);
}
