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
    /* All these buffers contain ccnb-encoded data, except for payload */
    struct ccn_charbuf *basename; /* The namespace we are serving */
    struct ccn_charbuf *name;   /* Buffer for constructed name */
    struct ccn_charbuf *payload; /* Buffer for payload */
    struct ccn_charbuf *cob;    /* Buffer for ContentObject */
};

/* Prototypes */
static enum ccn_upcall_res incoming_interest(struct ccn_closure *,
                                             enum ccn_upcall_kind,
                                             struct ccn_upcall_info *);
static void fatal(int lineno, int val);
static void initialize(int argc, char **argv, struct ccn_charbuf *);
struct ccn_charbuf *adjust_regprefix(struct ccn_charbuf *);
static void stampnow(struct ccn_charbuf *);
static void seed_random(void);
static void usage(void);
unsigned short wrappednow(void);

static void generate_new_data(struct ccnxchat_state *);
static int  matchbox(struct ccnxchat_state *);
static void send_matching_data(struct ccnxchat_state *);
static void toss_in_cs(struct ccnxchat_state *, const unsigned char *p, size_t);
static void toss_in_pit(struct ccnxchat_state *, const unsigned char *, size_t);
static void age_cs(struct ccnxchat_state *);
static void age_pit(struct ccnxchat_state *);        
void debug_logger(struct ccnxchat_state *, int lineno, struct ccn_charbuf *);
        
static void generate_cob(struct ccnxchat_state *);

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
    struct ccn_charbuf *regprefix = NULL;
    struct ccn_closure in_interest = {0};
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
    st->h = h;
    st->basename = name;
    st->name = ccn_charbuf_create();
    st->payload = ccn_charbuf_create();
    st->cob = ccn_charbuf_create();
    /* Set up a handler for interests */
    res = ccn_set_interest_filter(h, st->basename, &in_interest);
    if (res < 0)
        FATAL(res);
    debug_logger(st, __LINE__, st->basename);
    ccn_charbuf_destroy(&regprefix);
    /* Run the event loop */
    for (;;) {
        res = ccn_run(h, 100);
        if (res != 0)
            FATAL(res);
        if (st->n_pit != 0 && st->n_cob < CS_LIMIT)
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
    res = ccn_sign_content(st->h, st->cob, st->name, NULL,
                           st->payload->buf, st->payload->length);
    if (res < 0)
        FATAL(res);
    DB(st, st->cob);
    printf("=== %s\n", ccn_charbuf_as_string(st->payload));
    fflush(stdout);
}

/** Generate some new data and place in store */
static void
generate_new_data(struct ccnxchat_state *st)
{
    generate_cob(st);
    toss_in_cs(st, st->cob->buf, st->cob->length);
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
        if (pie->consumed || (now - pie->expiry) <= deltawrap) {
            /* could log here */
            DB(st, pie->pib);
            ccn_charbuf_destroy(&(pie->pib));
        }
        else
            st->pit[j++] = st->pit[i];
    }
    st->n_pit = j;
    /* Clear out now-unused entries */
    while (i > j)
        st->pit[--i] = empty;
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
void
debug_logger(struct ccnxchat_state *st, int lineno, struct ccn_charbuf *ccnb)
{
    struct ccn_charbuf *c;
    
    c = ccn_charbuf_create();
    stampnow(c);
    ccn_charbuf_putf(c, "debug.%d %5d", lineno, wrappednow());
    if (st != NULL)
        ccn_charbuf_putf(c, " %d %d", st->n_pit, st->n_cob);
    if (ccnb != NULL) {
        ccn_charbuf_putf(c, " ");
        ccn_uri_append(c, ccnb->buf, ccnb->length, 1);
    }
    fprintf(stderr, "%s\n", ccn_charbuf_as_string(c));
    ccn_charbuf_destroy(&c);
}
