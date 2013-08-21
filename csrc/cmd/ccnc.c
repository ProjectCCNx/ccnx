/*
 * Copyright (C) 2012-2013 Palo Alto Research Center, Inc.
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

#include <sys/select.h>
#include <sys/time.h>
#include <fcntl.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/ccn_private.h> /* for ccn_process_scheduled_operations() */
#include <ccn/charbuf.h>
#include <ccn/lned.h>
#include <ccn/uri.h>

#define USAGE                                                                 \
    "[-hdi:nqr:vx:] ccnx:/chat/room - community text chat"               "\n" \
    " -h - help"                                                         "\n" \
    " -d - debug mode - no input editing"                                "\n" \
    " -i n - print n bytes of signer's public key digest in hex"         "\n" \
    " -n - no echo of own output"                                        "\n" \
    " -q - no automatic greeting or farewell"                            "\n" \
    " -r command - hook up to input and output of responder command"     "\n" \
    " -v - verbose trace of what is happening"                           "\n" \
    " -x sec - set freshness"

/** Entry in the application's pending interest table */
struct pit_entry {
    struct ccn_charbuf *pib;    /* Buffer for received Interest */
    int consumed;               /* Set when this interest is consumed */
    unsigned short expiry;      /* Wrapped time that this object expires */
};
/** Number of pending interests we will keep */
#define PIT_LIMIT 10

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
    struct ccn_charbuf *payload; /* Buffer for payload */
    struct ccn_charbuf *lineout; /* For building output line */
    struct ccn_charbuf *luser;   /* user's name */
    /* The following buffers contain ccnb-encoded data */
    struct ccn_charbuf *basename; /* The namespace we are serving */
    struct ccn_charbuf *name;   /* Buffer for constructed name */
    struct ccn_charbuf *cob;    /* Buffer for ContentObject */
    struct ccn_charbuf *incob;  /* Most recent incoming ContentObject */    
    int eof;                    /* true if we have encountered eof */
    int ready;                  /* true if payload is ready to go */
    int prefer_newest;          /* for saner startup */
    int echo;                   /* to see own output */
    int freshness;              /* to set FreshnessSeconds */
    int quiet;                  /* no automatic greeting or farewell */
    int robotname;              /* print n bytes of robot name */
    int verbose;                /* to turn on debugging output */
};

/* Prototypes */
static enum ccn_upcall_res incoming_interest(struct ccn_closure *,
                                             enum ccn_upcall_kind,
                                             struct ccn_upcall_info *);
static enum ccn_upcall_res  incoming_content(struct ccn_closure *,
                                             enum ccn_upcall_kind,
                                             struct ccn_upcall_info *);
static void fatal(int lineno, int val);
static void initialize(struct ccnxchat_state *st, struct ccn_charbuf *);
struct ccn_charbuf *adjust_regprefix(struct ccn_charbuf *);
static int namecompare(const void *, const void *);
static void stampnow(struct ccn_charbuf *);
static void usage(void);
unsigned short wrappednow(void);

static int wait_for_input_or_timeout(struct ccn *, int );
static void read_input(struct ccnxchat_state *);
static void generate_new_data(struct ccnxchat_state *);
static int  matchbox(struct ccnxchat_state *);
static int  send_matching_data(struct ccnxchat_state *);
static void toss_in_cs(struct ccnxchat_state *, const unsigned char *, size_t);
static void toss_in_pit(struct ccnxchat_state *,
                        const unsigned char *, struct ccn_parsed_interest *);
static void age_cs(struct ccnxchat_state *);
static void age_pit(struct ccnxchat_state *);
static void debug_logger(struct ccnxchat_state *, int, struct ccn_charbuf *);
static int append_interest_details(struct ccn_charbuf *c,
                                   const unsigned char *ccnb, size_t size);
static void generate_cob(struct ccnxchat_state *);
static void add_info_exclusion(struct ccnxchat_state *,
                               struct ccn_upcall_info *);
static void add_uri_exclusion(struct ccnxchat_state *, const char *);
static void add_ver_exclusion(struct ccnxchat_state *, struct ccn_charbuf **);
static void display_the_content(struct ccnxchat_state *,
                                struct ccn_upcall_info *);
static void express_interest(struct ccnxchat_state *);
static void init_ver_exclusion(struct ccnxchat_state *);
static void prune_oldest_exclusion(struct ccnxchat_state *);
static int append_full_user_name(struct ccn_charbuf *);

/* Very simple error handling */
#define FATAL(res) fatal(__LINE__, res)

/* Debug messages */
#define DB(st, ccnb) debug_logger(st, __LINE__, ccnb)

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

/** Main entry point for chat */
static int
chat_main(int argc, char **argv)
{
    struct ccn *h = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_closure in_interest = {0};
    struct ccn_closure in_content = {0};
    struct ccnxchat_state state = {0};
    struct ccnxchat_state *st;
    int res;
    int timeout_ms;
    
    st = &state;
    name = ccn_charbuf_create();
    initialize(st, name);
    /* Connect to ccnd */
    h = ccn_create();
    if (ccn_connect(h, NULL) == -1)
        FATAL(-1);
    /* Set up state for interest handler */
    in_interest.p = &incoming_interest;
    in_interest.data = st;
    /* Set up state for content handler */
    in_content.p = &incoming_content;
    in_content.data = st;
    st->h = h;
    st->cc = &in_content;
    st->basename = name;
    st->name = ccn_charbuf_create();
    st->payload = ccn_charbuf_create();
    st->cob = ccn_charbuf_create();
    st->incob = ccn_charbuf_create();
    st->lineout = ccn_charbuf_create();
    st->luser = ccn_charbuf_create();
    append_full_user_name(st->luser);
    init_ver_exclusion(st);
    /* Set up a handler for interests */
    res = ccn_set_interest_filter(h, st->basename, &in_interest);
    if (res < 0)
        FATAL(res);
    debug_logger(st, __LINE__, st->basename);
    st->prefer_newest = 1;
    express_interest(st);
    /* Run the event loop */
    for (;;) {
        res = -1;
        timeout_ms = 10000;
        if (st->ready == 0 && st->eof == 0 && st->n_pit != 0) {
            res = wait_for_input_or_timeout(h, 0);
            timeout_ms = 10;
        }
        if (res != 0)
            read_input(st);
        if (st->eof)
            timeout_ms = 100;
        else if (st->ready)
            timeout_ms = 10;
        res = ccn_run(h, timeout_ms);
        if (res != 0)
            FATAL(res);
        generate_new_data(st);
        matchbox(st);
        res = send_matching_data(st);
        if (st->eof && st->eof++ > 3)
            exit(0);
        if (res > 0 || st->cc->refcount == 0)
            express_interest(st);
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
            ccn_set_run_timeout(info->h, 0);
            toss_in_pit(st, info->interest_ccnb, info->pi);
            if (st->ready)
                generate_new_data(st);
            if (matchbox(st) != 0)
                return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
            break;
        default:
            break;
    }
    return(CCN_UPCALL_RESULT_OK);
}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

/**
 *  Handle an arriving content object or interest timeout
 *
 * In the case where an arriving object has been verified, we will
 * display it, add its version to the exclude list (so we don't see it again),
 * and cause ccn_run to return soon so that a new interest can be sent.
 *
 * When an interest times out, we trim the exclusion list before returning to
 * the main event loop.
 */
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
            DB(st, NULL);
            add_info_exclusion(st, info);
            return(CCN_UPCALL_RESULT_VERIFY);
        case CCN_UPCALL_CONTENT:
            display_the_content(st, info);
            add_info_exclusion(st, info);
            ccn_set_run_timeout(info->h, 0);
            return(CCN_UPCALL_RESULT_OK);
        case CCN_UPCALL_INTEREST_TIMED_OUT:
            prune_oldest_exclusion(st);
            if (st->eof == 0)
                ccn_set_run_timeout(info->h, 0);
            return(CCN_UPCALL_RESULT_OK);
        default:
            /* something unexpected - make noise */
            DB(st, NULL);
            return(CCN_UPCALL_RESULT_ERR);
    }
}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */


/** Display the content payload, optionally with abbreviated publisher id */
static void
display_the_content(struct ccnxchat_state *st, struct ccn_upcall_info *info)
{
    struct ccn_charbuf *cob = st->incob;
    struct ccn_charbuf *line = st->lineout;
    const unsigned char *keyhash = NULL;
    const unsigned char *data = NULL;
    size_t size;
    size_t ksize;
    ssize_t sres;
    int i;
    int res;
    
    /* We see our own data twice because of having 2 outstanding interests */
    size = info->pco->offset[CCN_PCO_E];
    if (size == cob->length && memcmp(cob->buf, info->content_ccnb, size) == 0)
        return;
    ccn_charbuf_reset(cob);
    ccn_charbuf_append(cob, info->content_ccnb, size);
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
     for (i = 0; i < st->robotname; i++)
         ccn_charbuf_putf(line, "%02x", keyhash[i]);
     if (i > 0)
         ccn_charbuf_putf(line, " ");
     ccn_charbuf_append(line, data, size);
     ccn_charbuf_putf(line, "\n");
     sres = write(1, line->buf, line->length);
     if (sres != line->length)
          exit(1);
}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

/** Insert an entry into our list of excluded versions */
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
    if (st->n_ver == VER_LIMIT || st->prefer_newest) {
        if (i == 0)
            return;
        ccn_charbuf_destroy(&st->ver[0]);
        for (j = 0; j + 1 < i; j++)
            st->ver[j] = st->ver[j + 1];
        st->ver[j] = *c;
        *c = NULL;
        st->prefer_newest = 0;
        return;
    }
    for (j = st->n_ver; j > i; j--)
        st->ver[j] = st->ver[j - 1];
    st->n_ver++;
    st->ver[j] = *c;
    *c = NULL;
}

/** Remove the oldest excluded version from our list, if appropriate */
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

/** Add an exclusion for the specific version of the new content object */
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

/** Add an exclusion for a version expressed in URI form */
static void
add_uri_exclusion(struct ccnxchat_state *st, const char *uri)
{
    struct ccn_charbuf *c = ccn_charbuf_create();
    
    ccn_name_from_uri(c, uri);
    add_ver_exclusion(st, &c);
    ccn_charbuf_destroy(&c);
}

/** Add an exclusion for the specific version of the given content object */
static void
add_cob_exclusion(struct ccnxchat_state *st, struct ccn_charbuf *cob)
{
    struct ccn_parsed_ContentObject co;
    struct ccn_parsed_ContentObject *pco = &co;
    struct ccn_indexbuf *comps;
    struct ccn_charbuf *c;
    const unsigned char *ver = NULL;
    size_t ver_size = 0;
    int i;
    int res;
    
    i = ccn_name_split(st->basename, NULL);
    c = ccn_charbuf_create();
    comps = ccn_indexbuf_create();
    res = ccn_parse_ContentObject(cob->buf, cob->length, pco, comps);
    if (res >= 0 && i + 1 < comps->n) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Component, cob->buf,
                                  comps->buf[i], comps->buf[i + 1],
                                  &ver, &ver_size);
        if (res >= 0) {
            ccn_name_init(c);
            ccn_name_append(c, ver, ver_size);
            add_ver_exclusion(st, &c);
        }
    }
    ccn_indexbuf_destroy(&comps);
    ccn_charbuf_destroy(&c);
}

/** Initialize the ver table with the bounds for legitimate versions */
static void
init_ver_exclusion(struct ccnxchat_state *st)
{    
    add_uri_exclusion(st, "/%FE%00%00%00%00%00%00");
    add_uri_exclusion(st, "/%FD%00%FF%FF%FF%FF%FF");
}

/** Express an interest excluding everything in our exclude list */
static void
express_interest(struct ccnxchat_state *st)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    struct ccn_charbuf *comp = NULL;
    int i;
    
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccn_charbuf_append(templ, st->basename->buf, st->basename->length);
    ccnb_tagged_putf(templ, CCN_DTAG_MinSuffixComponents, "%d", 3);
    ccnb_tagged_putf(templ, CCN_DTAG_MaxSuffixComponents, "%d", 3);
    ccnb_element_begin(templ, CCN_DTAG_Exclude);
    if (st->n_ver > 1)
        ccnb_tagged_putf(templ, CCN_DTAG_Any, "");
    for (i = 0; i < st->n_ver; i++) {
        comp = st->ver[i];
        if (comp->length < 4) abort();
        ccn_charbuf_append(templ, comp->buf + 1, comp->length - 2);
    }
    ccnb_tagged_putf(templ, CCN_DTAG_Any, "");
    ccnb_element_end(templ); /* </Exclude> */
    if (st->prefer_newest)
        ccnb_tagged_putf(templ, CCN_DTAG_ChildSelector, "%d", 1);
    ccnb_element_end(templ); /* </Interest> */
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
    sp.sp_flags |= CCN_SP_FINAL_BLOCK; /* always produce just one segment */
    if (st->freshness > 0)
        sp.freshness = st->freshness;
    /* Make a ContentObject using the constructed name and our payload */
    ccn_charbuf_reset(st->cob);
    res = ccn_sign_content(st->h, st->cob, st->name, &sp,
                           st->payload->buf, st->payload->length);
    if (res < 0)
        FATAL(res);
    DB(st, st->cob);
}

/**
 * Wait until input on fd is ready or ccn_run needs to be called
 *
 * @returns 1 if STDIN is ready to read, 0 if not, or -1 for error.
 */
static int
wait_for_input_or_timeout(struct ccn *h, int fd)
{
    fd_set readfds;
    struct timeval tv;
    int ccnfd;
    int maxfd = fd;
    int res = -1;
    
    ccnfd = ccn_get_connection_fd(h);
    if (ccnfd < 0)
        return(-1);
    if (maxfd < ccnfd)
        maxfd = ccnfd;
    FD_ZERO(&readfds);
    FD_SET(fd, &readfds);
    FD_SET(ccnfd, &readfds);
    res = ccn_process_scheduled_operations(h);
    if (res >= 0) {
        tv.tv_sec  = res / 1000000U;
        tv.tv_usec = res % 1000000U;
        res = select(maxfd + 1, &readfds, 0, 0, &tv);                
    }
    if (res >= 0)
        res = FD_ISSET(fd, &readfds);
    return(res);
}

/** Read a line of standard input into payload */
static void
read_input(struct ccnxchat_state *st)
{
    unsigned char *cp = NULL;
    ssize_t res;
    int fl;
    int fd = 0;       /* standard input */
    
    if (st->ready)
        return;
    if (st->eof) {
        if (st->payload->length > 0)
            st->ready = 1;
        return;
    }
    fl = fcntl(fd, F_GETFL);
    fcntl(fd, F_SETFL, O_NONBLOCK | fl);
    while (st->ready == 0) {
        cp = ccn_charbuf_reserve(st->payload, 1);
        res = read(fd, cp, 1);
        if (res == 1) {
            if (cp[0] == '\n')
                st->ready = 1;
            else
                st->payload->length++;
        }
        else if (res == 0) {
            if (st->eof == 0 && st->quiet == 0) {
                ccn_charbuf_putf(st->payload, "=== ");
                ccn_charbuf_append_charbuf(st->payload, st->luser);
                ccn_charbuf_putf(st->payload, " leaving chat");
                st->freshness = 1;
            }
            st->eof = 1;
            if (st->payload->length > 0)
                st->ready = 1;
            break;
        }
        else
            break; /* partial line */
    }
    fcntl(fd, F_SETFL, fl);
}

/** Collect some new data and when ready, place it in store */
static void
generate_new_data(struct ccnxchat_state *st)
{
    if (st->ready && st->n_pit > 0 && st->n_cob < CS_LIMIT) {    
        generate_cob(st);
        toss_in_cs(st, st->cob->buf, st->cob->length);
        if (st->echo == 0)
            add_cob_exclusion(st, st->cob);
        ccn_charbuf_reset(st->payload);
        st->ready = 0;
    }
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
toss_in_pit(struct ccnxchat_state *st, const unsigned char *p,
            struct ccn_parsed_interest *pi)
{
    intmax_t lifetime;
    unsigned short lifetime_ms = ((unsigned short)(~0)) >> 1;
    struct pit_entry *pie;
    size_t size;
    
    size = pi->offset[CCN_PI_E];
    lifetime = ccn_interest_lifetime(p, pi); /* units: s/4096 */
    lifetime = (lifetime * (1000 / 8) + (4096 / 8 - 1)) / (4096 / 8);
    if (lifetime_ms > lifetime)
        lifetime_ms = lifetime;
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
                if (cse->sent == 0)
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
static int
send_matching_data(struct ccnxchat_state *st)
{
    struct cs_entry *cse = NULL;
    int i;
    int res;
    int sent;
    
    sent = 0;
    for (i = 0; i < st->n_cob; i++) {
        cse = &(st->cs[i]);
        if (cse->matched) {
            res = ccn_put(st->h, cse->cob->buf, cse->cob->length);
            if (res < 0)
                FATAL(res);
            cse->sent++;
            cse->matched = 0;
            sent++;
        }
    }
    return(sent);
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
 * Comparison operator for sorting the exclusions.
 * For convenience, the items in the excl array are
 * charbufs containing ccnb-encoded Names of one component each.
 * (This is not the most efficient representation.)
 */
static int
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
/** Global option info */
static struct {
    int debug;
    int echo;
    int freshness;
    int robotname;
    int quiet;
    int verbose;
    const char *basename;
    const char *responder;
} option;

/** Parse ccnc command-line options */
static void
parseopts(int argc, char **argv)
{
    int opt;
    
    progname = argv[0];
    optind = 1;
    option.echo = 1;
    option.robotname = 3;
    option.verbose = 0;
    option.quiet = 0;
    option.freshness = 30 * 60;
    while ((opt = getopt(argc, argv, "hdi:nqr:vx:")) != -1) {
        switch (opt) {
            case 'd':
                option.debug = 1;
                break;
            case 'i':
                option.robotname = atoi(optarg);
                if (option.robotname < 0 || option.robotname > 32)
                    usage();
                break;
            case 'n':
                option.echo = 0;
                break;
            case 'q':
                option.quiet = 1;
                break;
            case 'r':
                option.responder = optarg;
                break;
            case 'v':
                option.verbose++;
                break;
            case 'x':
                option.freshness = atoi(optarg);
                break;
            case 'h':
            default:
                usage();
        }
    }
    option.basename = argv[optind];
    if (option.basename == NULL || argv[optind + 1] != NULL)
        usage();
}

/**
 * Initialization at startup
 *
 * If there is a command line argument, it is interpreted as a
 * URI relative to basename, and basename is updated accordingly.
 *
 * basename is a Name in ccnb encoding.
 */
static void
initialize(struct ccnxchat_state *st, struct ccn_charbuf *basename)
{
    int res;
    
    res = ccn_name_from_uri(basename, option.basename);
    if (res < 0)
        usage();
    st->echo = option.echo;
    st->freshness = option.freshness;
    st->verbose = option.verbose;
    st->robotname = option.robotname;
    st->quiet = option.quiet;
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
    fprintf(stderr, "%s " USAGE "\n", progname);
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
    
    if (st->verbose == 0)
        return;
    c = ccn_charbuf_create();
    stampnow(c);
    ccn_charbuf_putf(c, "debug.%d %5d", lineno, wrappednow());
    if (st != NULL)
        ccn_charbuf_putf(c, " pit=%d pot=%d cob=%d buf=%d",
                         st->n_pit, st->cc->refcount,
                         st->n_cob, (int)st->payload->length);
    if (ccnb != NULL) {
        ccn_charbuf_putf(c, " ");
        ccn_uri_append(c, ccnb->buf, ccnb->length, 1);
        append_interest_details(c, ccnb->buf, ccnb->length);
    }
    fprintf(stderr, "%s\n", ccn_charbuf_as_string(c));
    ccn_charbuf_destroy(&c);
}

/** Append the details of the interest, expecially the excludes */
static int
append_interest_details(struct ccn_charbuf *c,
                        const unsigned char *ccnb, size_t size)
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

/** Append the name of the user, if we have access to it */
static int
append_full_user_name(struct ccn_charbuf *c)
{
    int res = -1;
#ifndef C_NO_GECOS
    struct passwd *pwd;
    pwd = getpwuid(getuid());
    if (pwd != NULL) {
        res = 0;
        ccn_charbuf_putf(c, "%s", pwd->pw_gecos);
    }
#endif
    return(res);
}

/**
 * classic unix fork/exec to hook up an automatic responder
 */
static int
robo_chat(int argc, char **argv)
{
    pid_t p;
    int res;
    int io[2] = {-1, -1};
    int oi[2] = {-1, -1};
    
    res = socketpair(AF_UNIX, SOCK_STREAM, 0, io);
    if (res < 0) exit(1);
    res = socketpair(AF_UNIX, SOCK_STREAM, 0, oi);
    if (res < 0) exit(1);
    p = fork();
    if (p < 0) {
        perror("fork");
        exit(1);
    }
    if (p == 0) {
        /* child */
        dup2(io[1], 0);
        dup2(oi[1], 1);
        close(io[0]);
        close(io[1]);
        close(oi[0]);
        close(oi[1]);
        execlp("sh", "sh", "-c", option.responder, NULL);
        perror(option.responder); /* no return unless there was an error */
        exit(1);
    }
    /* parent */
    dup2(io[0], 1);
    dup2(oi[0], 0);
    close(io[0]);
    close(io[1]);
    close(oi[0]);
    close(oi[1]);
    /* Turn off echo and extra output for the robo-chat case */
    option.echo = 0;
    option.quiet = 1;
    return(chat_main(argc, argv));
}

/** 
 *  Main entry point for chat.
 *
 * This takes care of starting the line editor front end, if desired.
 */
int
main(int argc, char **argv)
{
    parseopts(argc, argv);
    if (option.responder != NULL)
        return(robo_chat(argc, argv));
    if (option.debug)
        return(chat_main(argc, argv));
    return(lned_run(argc, argv, "Chat.. ", &chat_main));
}
