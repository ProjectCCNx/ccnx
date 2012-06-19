/*
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

#define USAGE "ccnx:/uri/of/chat/room"

/** Entry in the application's pending interest table */
struct pit_entry {
    struct ccn_charbuf *pib;    /* Buffer for received Interest */
    int consumed;               /* Set when this interest is consumed */
    unsigned short expiry;      /* Wrapped time that this object expires */
};
/** Number of pending interests we will keep */
#define PIT_LIMIT 4

/** Entry in the mini content store that holds our generated data */
struct cs_entry {
    struct ccn_charbuf *cob;    /* Buffer for ContentObject*/
    int sent;                   /* Number of times sent */
    int matched;                /* Non-zero if send needed */
};
/** Number of generated data items we will hold */
#define CS_LIMIT 3
/** Max number of received versions to track */
#define VER_LIMIT 5

/**
 * Application state
 *
 * A pointer to one of these is stored in the data field of the closure.
 */
struct ccnxchat_state {
    struct ccn *h;              /* Backlink to ccn handle */
    int n_pit;                  /* Number of live PIT entries */
    struct pit_entry pit[PIT_LIMIT];
    int n_cob;                  /* Number of live CS entries */
    struct cs_entry cs[CS_LIMIT];
    int n_ver;                  /* Number of recently received versions */
    struct ccn_charbuf *ver[VER_LIMIT];
    struct ccn_closure *cc;     /* Closure for incoming content */
    /* All these buffers contain ccnb-encoded data, except for payload */
    struct ccn_charbuf *basename; /* The namespace we are serving */
    struct ccn_charbuf *name;   /* Buffer for constructed name */
    struct ccn_charbuf *payload; /* Buffer for payload */
    struct ccn_charbuf *cob;    /* Buffer for ContentObject */
    struct ccn_charbuf *lineout; /* For building output line */
    int eof;                    /* true if we have encountered eof */
};

/* Prototypes */
static enum ccn_upcall_res incoming_interest(struct ccn_closure *,
                                             enum ccn_upcall_kind,
                                             struct ccn_upcall_info *);
static enum ccn_upcall_res  incoming_content(struct ccn_closure *,
                                             enum ccn_upcall_kind,
                                             struct ccn_upcall_info *);
static void fatal(int lineno, int val);
static void initialize(int argc, char **argv, struct ccn_charbuf *);
struct ccn_charbuf *adjust_regprefix(struct ccn_charbuf *);
static int namecompare(const void *, const void *);
static void stampnow(struct ccn_charbuf *);
static void seed_random(void);
static void usage(void);
unsigned short wrappednow(void);

static void generate_new_data(struct ccnxchat_state *);
static int  matchbox(struct ccnxchat_state *);
static void send_matching_data(struct ccnxchat_state *);
static void toss_in_cs(struct ccnxchat_state *, const unsigned char *, size_t);
static void toss_in_pit(struct ccnxchat_state *, const unsigned char *, size_t);
static void age_cs(struct ccnxchat_state *);
static void age_pit(struct ccnxchat_state *);
static void debug_logger(struct ccnxchat_state *, int lineno, struct ccn_charbuf *);
static int append_interest_details(struct ccn_charbuf *c,
                                   const unsigned char *ccnb, size_t size);
static void generate_cob(struct ccnxchat_state *);
static void add_info_exclusion(struct ccnxchat_state *st, struct ccn_upcall_info *info);
static void add_uri_exclusion(struct ccnxchat_state *st, const char *uri);
static void add_ver_exclusion(struct ccnxchat_state *st, struct ccn_charbuf **c);
static void display_the_content(struct ccnxchat_state *st, struct ccn_upcall_info *info);
static void express_interest(struct ccnxchat_state *st);
static void init_ver_exclusion(struct ccnxchat_state *st);
static void prune_oldest_exclusion(struct ccnxchat_state *st);

/* Very simple error handling */
#define FATAL(res) fatal(__LINE__, res)

/* Debug messages */
#define DB(st, ccnb) debug_logger(st, __LINE__, ccnb)

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

/** Main */
int
main(int argc, char **argv)
{
    struct ccn *h = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *cob = NULL;
    struct ccn_closure in_interest = {0};
    struct ccn_closure in_content = {0};
    struct ccnxchat_state state = {0};
    struct ccnxchat_state *st;
    int res;
    
    name = ccn_charbuf_create();
    initialize(argc, argv, name);
    cob = ccn_charbuf_create();
    /* Connect to ccnd */
    h = ccn_create();
    if (ccn_connect(h, NULL) == -1)
        FATAL(-1);
    /* Set up state for interest handler */
    in_interest.p = &incoming_interest;
    in_interest.data = st = &state;
    /* Set up state for content handler */
    in_content.p = &incoming_content;
    in_content.data = st;
    st->h = h;
    st->basename = name;
    st->name = ccn_charbuf_create();
    st->payload = ccn_charbuf_create();
    st->cob = ccn_charbuf_create();
    st->lineout = ccn_charbuf_create();
    st->cc = &in_content;
    init_ver_exclusion(st);
    /* Set up a handler for interests */
    res = ccn_set_interest_filter(h, st->basename, &in_interest);
    if (res < 0)
        FATAL(res);
    debug_logger(st, __LINE__, st->basename);
    express_interest(st);
    /* Run the event loop */
    for (;;) {
        res = ccn_run(h, 100);
        if (res != 0)
            FATAL(res);
        if (st->n_cob == 0 || (st->n_pit != 0 && st->n_cob < CS_LIMIT))
            generate_new_data(st);
        matchbox(st);
        send_matching_data(st);
        age_cs(st);
        age_pit(st);
    }
    /* Loop has no normal exit */
}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

/** Interest handler */
static enum ccn_upcall_res
incoming_interest(struct ccn_closure *selfp,
                  enum ccn_upcall_kind kind,
                  struct ccn_upcall_info *info)
{
    struct ccnxchat_state *st = selfp->data;
    
    switch (kind) {
        case CCN_UPCALL_FINAL:
            break;
        case CCN_UPCALL_INTEREST:
            toss_in_pit(st, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
            if (matchbox(st) != 0) {
                /* We have a new match, so don't wait to process it */
                ccn_set_run_timeout(info->h, 0);
                return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
            }
            break;
        default:
            break;
    }
    return(CCN_UPCALL_RESULT_OK);
}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

static enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                  enum ccn_upcall_kind kind,
                  struct ccn_upcall_info *info)
{
    struct ccnxchat_state *st = selfp->data;

    switch (kind) {
        case CCN_UPCALL_FINAL:
            return(CCN_UPCALL_RESULT_OK);
        case CCN_UPCALL_CONTENT_UNVERIFIED:
            add_info_exclusion(st, info);
            return(CCN_UPCALL_RESULT_VERIFY);
        case CCN_UPCALL_CONTENT:
            display_the_content(st, info);
            add_info_exclusion(st, info);
            express_interest(st);
            return(CCN_UPCALL_RESULT_OK);
        case CCN_UPCALL_INTEREST_TIMED_OUT:
            prune_oldest_exclusion(st);
            express_interest(st);
            return(CCN_UPCALL_RESULT_OK);
        default:
            /* something unexpected - make noise */
            DB(st, NULL);
            express_interest(st);
            return(CCN_UPCALL_RESULT_ERR);
    }
}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

static void
display_the_content(struct ccnxchat_state *st, struct ccn_upcall_info *info)
{
    struct ccn_charbuf *cob = st->cob;
    struct ccn_charbuf *line = st->lineout;
    const unsigned char *keyhash = NULL;
    const unsigned char *data = NULL;
    size_t size;
    size_t ksize;
    ssize_t sres;
    int res;
    
    ccn_charbuf_reset(cob);
    ccn_charbuf_append(cob, info->content_ccnb, info->pco->offset[CCN_PCO_E]);
    DB(st, cob);
    res = ccn_content_get_value(cob->buf, cob->length, info->pco,
                                &data, &size);
    if (res < 0) abort();
    res = ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest,
                              cob->buf,
                              info->pco->offset[CCN_PCO_B_PublisherPublicKeyDigest],
                              info->pco->offset[CCN_PCO_E_PublisherPublicKeyDigest],
                              &keyhash, &ksize);
     if (res < 0 || ksize < 32) abort();
     ccn_charbuf_reset(line);
     ccn_charbuf_putf(line, "%02x%02x%02x ", keyhash[0], keyhash[1], keyhash[2]);
     ccn_charbuf_append(line, data, size);
     ccn_charbuf_putf(line, "\n");
     sres = write(1, line->buf, line->length);
     if (sres != line->length) {
          exit(1);
     }
}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
static void
add_ver_exclusion(struct ccnxchat_state *st, struct ccn_charbuf **c)
{
    int i;
    int j;
    int t;
    
    for (i = 0; i < st->n_ver; i++) {
        t = namecompare(c, &(st->ver[i]));
        if (t == 0)
            return;
        if (t < 0)
            break;
    }
    if (st->n_ver == VER_LIMIT) {
        if (i == 0)
            return;
        ccn_charbuf_destroy(&st->ver[0]);
        for (j = 0; j + 1 < i; j++)
            st->ver[j] = st->ver[j + 1];
        st->ver[j] = *c;
        *c = NULL;
        return;
    }
    for (j = st->n_ver; j > i; j--)
        st->ver[j] = st->ver[j - 1];
    st->n_ver++;
    st->ver[j] = *c;
    *c = NULL;
}

static void
prune_oldest_exclusion(struct ccnxchat_state *st)
{
    int j;
    
    if (st->n_ver <= 2)
        return;
    ccn_charbuf_destroy(&st->ver[0]);
    for (j = 0; j + 1 < st->n_ver; j++)
        st->ver[j] = st->ver[j + 1];
    st->n_ver--;
}

static void
add_info_exclusion(struct ccnxchat_state *st, struct ccn_upcall_info *info)
{
    struct ccn_charbuf *c = ccn_charbuf_create();
    const unsigned char *ver = NULL;
    size_t ver_size = 0;
    int res;
    
    if (info->content_comps->n > info->matched_comps + 1) {
        ccn_name_init(c);
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Component, info->content_ccnb,
                                  info->content_comps->buf[info->matched_comps],
                                  info->content_comps->buf[info->matched_comps + 1],
                                  &ver,
                                  &ver_size);
        if (res < 0) abort();
        ccn_name_append(c, ver, ver_size);
        add_ver_exclusion(st, &c);
    }
    ccn_charbuf_destroy(&c);
}

static void
add_uri_exclusion(struct ccnxchat_state *st, const char *uri)
{
    struct ccn_charbuf *c = ccn_charbuf_create();
    
    ccn_name_from_uri(c, uri);
    add_ver_exclusion(st, &c);
    ccn_charbuf_destroy(&c);
}

static void
init_ver_exclusion(struct ccnxchat_state *st)
{    
    add_uri_exclusion(st, "/%FE%00%00%00%00%00%00");
    add_uri_exclusion(st, "/%FD%00%FF%FF%FF%FF%FF");
}

static void
express_interest(struct ccnxchat_state *st)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    struct ccn_charbuf *comp = NULL;
    int i;
    
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append(templ, st->basename->buf, st->basename->length); /* Name */
    ccnb_tagged_putf(templ, CCN_DTAG_MinSuffixComponents, "%d", 3);
    ccnb_tagged_putf(templ, CCN_DTAG_MaxSuffixComponents, "%d", 3);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Exclude, CCN_DTAG);
    if (st->n_ver > 1)
        ccnb_tagged_putf(templ, CCN_DTAG_Any, "");
    for (i = 0; i < st->n_ver; i++) {
        comp = st->ver[i];
        if (comp->length < 4) abort();
        ccn_charbuf_append(templ, comp->buf + 1, comp->length - 2);
    }
    ccnb_tagged_putf(templ, CCN_DTAG_Any, "");
    ccn_charbuf_append_closer(templ); /* </Exclude> */
    ccn_charbuf_append_closer(templ); /* </Interest> */
    ccn_express_interest(st->h, st->basename, st->cc, templ);
    ccn_charbuf_destroy(&templ);
}
/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

/**
 * Generate a content object containing the current payload
 *
 * The standard versioning and segmentation profiles are used.
 * It is assumed that the payload fits into one content object.
 */
static void
generate_cob(struct ccnxchat_state *st)
{
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    int res;
    
    /* Make sure name buffer is up to date */
    ccn_charbuf_reset(st->name);
    ccn_charbuf_append(st->name, st->basename->buf, st->basename->length);
    ccn_create_version(st->h, st->name, CCN_V_NOW, 0, 0);
    ccn_name_append_numeric(st->name, CCN_MARKER_SEQNUM, 0);
    sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    /* Make a ContentObject using the constructed name and our payload */
    ccn_charbuf_reset(st->cob);
    res = ccn_sign_content(st->h, st->cob, st->name, &sp,
                           st->payload->buf, st->payload->length);
    if (res < 0)
        FATAL(res);
    DB(st, st->cob);
    printf("=== %s\n", ccn_charbuf_as_string(st->payload));
    fflush(stdout);
}

/** Collect some new data and when ready, place it in store */
static void
generate_new_data(struct ccnxchat_state *st)
{
    unsigned char *cp;
    ssize_t res;
    int fl;
    int ready = 0;    /* set when we have a whole line */
    int fd = 0;       /* standard input */

    if (st->eof != 0) {
        if (st->eof++ > 3)
            exit(0);
        if (st->payload->length == 0)
            return;
    } 
    fl = fcntl(fd, F_GETFL);
    fcntl(fd, F_SETFL, O_NONBLOCK | fl);
    while (!ready) {
        cp = ccn_charbuf_reserve(st->payload, 1);
        res = read(fd, cp, 1);
        if (res == 1) {
           if (cp[0] == '\n')
               ready = 1;
           else
               st->payload->length++;
        }
        else if (res == 0) {
            if (st->eof == 0)
                ccn_charbuf_putf(st->payload, "--- leaving");
            if (st->cob->length > 0)
                ready = 1;
            st->eof++;
            break;
        }
        else
            break;
    }
    if (ready) {    
        generate_cob(st);
        toss_in_cs(st, st->cob->buf, st->cob->length);
        ccn_charbuf_reset(st->payload);
    }
    fcntl(fd, F_SETFL, fl);
}

/**
 * Insert a ccnb-encoded ContentObject into our content store
 */
static void
toss_in_cs(struct ccnxchat_state *st, const unsigned char *p, size_t size)
{
    struct cs_entry *cse;        /* New slot in our store */

    if (st->n_cob >= CS_LIMIT)
        FATAL(st->n_cob);
    cse = &(st->cs[st->n_cob++]); /* Allocate cs slot */
    cse->cob = ccn_charbuf_create();
    ccn_charbuf_append(cse->cob, p, size);
    cse->sent = 0;
    cse->matched = 0;
}

/** Insert a ccnb-encoded Interest message into our pending interest table */
static void
toss_in_pit(struct ccnxchat_state *st, const unsigned char *p, size_t size)
{
    unsigned short lifetime_ms = CCN_INTEREST_LIFETIME_MICROSEC / 1000;
    struct pit_entry *pie;
    
    if (st->n_pit == PIT_LIMIT)
        age_pit(st);
    if (st->n_pit == PIT_LIMIT) {
        /* Forcibly free up a slot if we must */
        st->pit[0].consumed = 1;
        age_pit(st);
    }
    if (st->n_pit >= PIT_LIMIT)
        FATAL(st->n_pit);
    pie = &(st->pit[st->n_pit++]); /* Allocate pit slot */
    pie->pib = ccn_charbuf_create();
    ccn_charbuf_append(pie->pib, p, size);
    pie->consumed = 0;
    pie->expiry = wrappednow() + lifetime_ms;
    DB(st, pie->pib);
}

/**
 * Match PIT entries against the store
 *
 * This implementation relies on both tables being relatively
 * small, since it can look at all n x m combinations.
 *
 * @returns number of new matches found
 */
static int
matchbox(struct ccnxchat_state *st)
{
    int new_matches;
    struct cs_entry *cse;
    struct pit_entry *pie;
    int i, j;
    
    new_matches = 0;
    for (i = 0; i < st->n_pit; i++) {
        pie = &(st->pit[i]);
        if (pie->consumed)
            continue;
        for (j = 0; j < st->n_cob; j++) {
            cse = &(st->cs[j]);
            if (ccn_content_matches_interest(
                  cse->cob->buf, cse->cob->length, 1, NULL,
                  pie->pib->buf, pie->pib->length, NULL)) {
                if (cse->matched == 0)
                    new_matches++;
                cse->matched = 1;
                pie->consumed = 1;
                DB(st, pie->pib);
            }
        }
    }
    return(new_matches);
}

/** Send data that has been matched */
static void
send_matching_data(struct ccnxchat_state *st)
{
    struct cs_entry *cse;
    int i;
    int res;
    
    for (i = 0; i < st->n_cob; i++) {
        cse = &(st->cs[i]);
        if (cse->matched) {
            res = ccn_put(st->h, cse->cob->buf, cse->cob->length);
            if (res < 0)
                FATAL(res);
            cse->sent++;
            cse->matched = 0;
        }
    }
}

/** Remove already-sent entries from the content store */
static void
age_cs(struct ccnxchat_state *st)
{
    const struct cs_entry empty = {0};
    int i;
    int j;
    
    for (i = 0, j = 0; i < st->n_cob; i++) {
        if (st->cs[i].sent != 0) {
            /* could log here */
            DB(st, st->cs[i].cob);
            ccn_charbuf_destroy(&(st->cs[i].cob));
        }
        else
            st->cs[j++] = st->cs[i];
    }
    st->n_cob = j;
    /* Clear garbage in now-unused entries */
    while (i > j)
        st->cs[--i] = empty;
}

/** Get rid of PIT entries that have timed out or been consumed */
static void
age_pit(struct ccnxchat_state *st)
{
    const struct pit_entry empty = {0};
    struct pit_entry *pie;
    unsigned short now;
    unsigned short delta;
    unsigned short deltawrap;
    int i;
    int j;
    
    deltawrap = ~0;
    deltawrap >>= 1; /* 32767 on most platforms */
    now = wrappednow();
    for (i = 0, j = 0; i < st->n_pit; i++) {
        pie = &(st->pit[i]);
        delta = now - pie->expiry;
        if (delta <= deltawrap) {
            DB(st, pie->pib);
            pie->consumed = 1;
        }
        if (pie->consumed)
            ccn_charbuf_destroy(&(pie->pib));
        else
            st->pit[j++] = st->pit[i];
    }
    st->n_pit = j;
    /* Clear out now-unused entries */
    while (i > j)
        st->pit[--i] = empty;
}

/**
 * Comparison operator for sorting the excl list with qsort.
 * For convenience, the items in the excl array are
 * charbufs containing ccnb-encoded Names of one component each.
 * (This is not the most efficient representation.)
 */
static int /* for qsort */
namecompare(const void *a, const void *b)
{
    const struct ccn_charbuf *aa = *(const struct ccn_charbuf **)a;
    const struct ccn_charbuf *bb = *(const struct ccn_charbuf **)b;
    int ans = ccn_compare_names(aa->buf, aa->length, bb->buf, bb->length);
    return (ans);
}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

/** Global progam name for messages */
static const char *progname;

/**
 * Initialization at startup
 *
 * If there is a command line argument, it is interpreted as a
 * URI relative to basename, and basename is updated accordingly.
 *
 * basename is a Name in ccnb encoding.
 */
static void
initialize(int argc, char **argv, struct ccn_charbuf *basename) {
    int res;
    
    progname = argv[0];
    if (argc > 2)
        usage();
    if (argc > 1) {
        if (argv[1][0] == '-')
            usage();
        res = ccn_name_from_uri(basename, argv[1]);
        if (res < 0)
            usage();
    }
    seed_random();
}

/** Return a newly-allocated Name buffer with one Component chopped off */
struct ccn_charbuf *
adjust_regprefix(struct ccn_charbuf *name)
{
    struct ccn_charbuf *c;
    
    c = ccn_charbuf_create();
    ccn_charbuf_append(c, name->buf, name->length);
    ccn_name_chop(c, NULL, -1);
    DB(NULL, c);
    return(c);
}

/** Print cryptic message and exit */
static void
fatal(int lineno, int val)
{
    fprintf(stderr, "Error near %s:%d (%d)\n", progname, lineno, val);
    exit(1);
}

/** Usage */
static void
usage(void)
{
    fprintf(stderr, "%s: " USAGE "\n", progname);
    exit(1);
}

/** Append a numeric timestamp to a charbuf */
static void
stampnow(struct ccn_charbuf *c)
{
    struct timeval tv;
    unsigned long sec;
    unsigned usec;
    
    gettimeofday(&tv, NULL);
    sec = tv.tv_sec;
    usec = tv.tv_usec;
    ccn_charbuf_putf(c, "%lu.%06u ", sec, usec);
}

/** Wrapped time - (normally) 16-bit unsigned; millisecond units */
unsigned short
wrappednow(void)
{
    unsigned short now;
    struct timeval tv;
    
    gettimeofday(&tv, NULL);
    now = tv.tv_sec * 1000 + tv.tv_usec / 1000;
    return(now);
}

/** Seed the pseudo-random numbers */
static void
seed_random(void)
{
    struct timeval tv;
    
    gettimeofday(&tv, NULL);
    srandom(getpid() * 31415 + tv.tv_sec + tv.tv_usec);
}

/**
 * Debugging aid
 *
 * Prints some internal state to stderr.
 * If non-NULL, ccnb should be a ccnb-encoded Name, Interest, or ContentObject.
 */
static void
debug_logger(struct ccnxchat_state *st, int lineno, struct ccn_charbuf *ccnb)
{
    struct ccn_charbuf *c;
    
    c = ccn_charbuf_create();
    stampnow(c);
    ccn_charbuf_putf(c, "debug.%d %5d", lineno, wrappednow());
    if (st != NULL)
        ccn_charbuf_putf(c, " pit=%d cob=%d buf=%d",
                         st->n_pit, st->n_cob, (int)st->payload->length);
    if (ccnb != NULL) {
        ccn_charbuf_putf(c, " ");
        ccn_uri_append(c, ccnb->buf, ccnb->length, 1);
        append_interest_details(c, ccnb->buf, ccnb->length);
    }
    fprintf(stderr, "%s\n", ccn_charbuf_as_string(c));
    ccn_charbuf_destroy(&c);
}

static int
append_interest_details(struct ccn_charbuf *c, const unsigned char *ccnb, size_t size)
{
    struct ccn_parsed_interest interest;
    struct ccn_parsed_interest *pi = &interest;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    const unsigned char *bloom;
    size_t bloom_size = 0;
    const unsigned char *comp;    
    size_t comp_size = 0;
    int i;
    int l;
    int res;

    res = ccn_parse_interest(ccnb, size, pi, NULL);
    if (res < 0)
        return(res);
    i = pi->offset[CCN_PI_B_Exclude];
    l = pi->offset[CCN_PI_E_Exclude] - i;
    if (l > 0) {
        d = ccn_buf_decoder_start(&decoder, ccnb + i, l);
        ccn_charbuf_append_string(c, " excl: ");
        ccn_buf_advance(d);
        
        if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
            ccn_buf_advance(d);
            ccn_charbuf_append_string(c, "* ");
            ccn_buf_check_close(d);
        }
        else if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, &bloom, &bloom_size))
                ccn_buf_advance(d);
            ccn_charbuf_append_string(c, "? ");
            ccn_buf_check_close(d);
        }
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            ccn_buf_advance(d);
            comp_size = 0;
            if (ccn_buf_match_blob(d, &comp, &comp_size))
                ccn_buf_advance(d);
            ccn_uri_append_percentescaped(c, comp, comp_size);
            ccn_charbuf_append_string(c, " ");
            ccn_buf_check_close(d);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
                ccn_buf_advance(d);
                ccn_charbuf_append_string(c, "* ");
                ccn_buf_check_close(d);
            }
            else if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
                ccn_buf_advance(d);
                if (ccn_buf_match_blob(d, &bloom, &bloom_size))
                    ccn_buf_advance(d);
                ccn_charbuf_append_string(c, "? ");
                ccn_buf_check_close(d);
            }
        }
    }
    return(0);
}

