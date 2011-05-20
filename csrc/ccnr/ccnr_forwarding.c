#include "common.h"
static struct nameprefix_entry *
nameprefix_for_pe(struct ccnr_handle * h, struct propagating_entry * pe);

static void
replan_propagation(struct ccnr_handle * h, struct propagating_entry * pe);

PUBLIC void
finalize_nameprefix(struct hashtb_enumerator *e)
{
    struct ccnr_handle *h = hashtb_get_param(e->ht, NULL);
    struct nameprefix_entry *npe = e->data;
    struct propagating_entry *head = &npe->pe_head;
    if (head->next != NULL) {
        while (head->next != head)
            consume(h, head->next);
    }
    ccn_indexbuf_destroy(&npe->forward_to);
    ccn_indexbuf_destroy(&npe->tap);
    while (npe->forwarding != NULL) {
        struct ccn_forwarding *f = npe->forwarding;
        npe->forwarding = f->next;
        free(f);
    }
}

static void
link_propagating_interest_to_nameprefix(struct ccnr_handle *h,
    struct propagating_entry *pe, struct nameprefix_entry *npe)
{
    struct propagating_entry *head = &npe->pe_head;
    pe->next = head;
    pe->prev = head->prev;
    pe->prev->next = pe->next->prev = pe;
}

PUBLIC void
finalize_propagating(struct hashtb_enumerator *e)
{
    struct ccnr_handle *h = hashtb_get_param(e->ht, NULL);
    consume(h, e->data);
}

/**
 * If the pe interest is slated to be sent to the given filedesc,
 * promote the filedesc to the front of the list, preserving the order
 * of the others.
 * @returns -1 if not found, or pe->sent if successful.
 */
static int
promote_outbound(struct propagating_entry *pe, unsigned filedesc)
{
    struct ccn_indexbuf *ob = pe->outbound;
    int lb = pe->sent;
    int i;
    if (ob == NULL || ob->n <= lb || lb < 0)
        return(-1);
    for (i = ob->n - 1; i >= lb; i--)
        if (ob->buf[i] == filedesc)
            break;
    if (i < lb)
        return(-1);
    for (; i > lb; i--)
        ob->buf[i] = ob->buf[i-1];
    ob->buf[lb] = filedesc;
    return(lb);
}

PUBLIC void
adjust_npe_predicted_response(struct ccnr_handle *h,
                              struct nameprefix_entry *npe, int up)
{
    unsigned t = npe->usec;
    if (up)
        t = t + (t >> 3);
    else
        t = t - (t >> 7);
    if (t < 127)
        t = 127;
    else if (t > 1000000)
        t = 1000000;
    npe->usec = t;
}

static void
adjust_predicted_response(struct ccnr_handle *h,
                          struct propagating_entry *pe, int up)
{
    struct nameprefix_entry *npe;
        
    npe = nameprefix_for_pe(h, pe);
    if (npe == NULL)
        return;
    adjust_npe_predicted_response(h, npe, up);
    if (npe->parent != NULL)
        adjust_npe_predicted_response(h, npe->parent, up);
}

/**
 * Use the history to reorder the interest forwarding.
 *
 * @returns number of tap faces that are present.
 */
static int
reorder_outbound_using_history(struct ccnr_handle *h,
                               struct nameprefix_entry *npe,
                               struct propagating_entry *pe)
{
    int ntap = 0;
    int i;
    
    if (npe->osrc != CCN_NOFACEID)
        promote_outbound(pe, npe->osrc);
    /* Process npe->src last so it will be tried first */
    if (npe->src != CCN_NOFACEID)
        promote_outbound(pe, npe->src);
    /* Tap are really first. */
    if (npe->tap != NULL) {
        ntap = npe->tap->n;
        for (i = 0; i < ntap; i++)
            promote_outbound(pe, npe->tap->buf[i]);
    }
    return(ntap);
}

/**
 * Remove expired faces from npe->forward_to
 */
static void
check_forward_to(struct ccnr_handle *h, struct nameprefix_entry *npe)
{
    struct ccn_indexbuf *ft = npe->forward_to;
    int i;
    int j;
    if (ft == NULL)
        return;
    for (i = 0; i < ft->n; i++)
        if (fdholder_from_fd(h, ft->buf[i]) == NULL)
            break;
    for (j = i + 1; j < ft->n; j++)
        if (fdholder_from_fd(h, ft->buf[j]) != NULL)
            ft->buf[i++] = ft->buf[j];
    if (i == 0)
        ccn_indexbuf_destroy(&npe->forward_to);
    else if (i < ft->n)
        ft->n = i;
}

/**
 * Check for expired propagating interests.
 * @returns number that have gone away.
 */
static int
check_propagating(struct ccnr_handle *h)
{
    int count = 0;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct propagating_entry *pe;
    
    hashtb_start(h->propagating_tab, e);
    for (pe = e->data; pe != NULL; pe = e->data) {
        if (pe->interest_msg == NULL) {
            if (pe->size == 0) {
                count += 1;
                hashtb_delete(e);
                continue;
            }
            pe->size = (pe->size > 1); /* go around twice */
            /* XXX - could use a flag bit instead of hacking size */
        }
        hashtb_next(e);
    }
    hashtb_end(e);
    return(count);
}

/**
 * Ages src info and retires unused nameprefix entries.
 * @returns number that have gone away.
 */
static int
check_nameprefix_entries(struct ccnr_handle *h)
{
    int count = 0;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct propagating_entry *head;
    struct nameprefix_entry *npe;    
    
    hashtb_start(h->nameprefix_tab, e);
    for (npe = e->data; npe != NULL; npe = e->data) {
        if (npe->forward_to != NULL)
            check_forward_to(h, npe);
        if (  npe->src == CCN_NOFACEID &&
              npe->children == 0 &&
              npe->forwarding == NULL) {
            head = &npe->pe_head;
            if (head == head->next) {
                count += 1;
                if (npe->parent != NULL) {
                    npe->parent->children--;
                    npe->parent = NULL;
                }
                hashtb_delete(e);
                continue;
            }
        }
        npe->osrc = npe->src;
        npe->src = CCN_NOFACEID;
        hashtb_next(e);
    }
    hashtb_end(e);
    return(count);
}

/**
 * Scheduled reap event for retiring expired structures.
 */
static int
reap(struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    struct ccnr_handle *h = clienth;
    (void)(sched);
    (void)(ev);
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        h->reaper = NULL;
        return(0);
    }
    check_propagating(h);
    check_nameprefix_entries(h);
    return(2 * CCN_INTEREST_LIFETIME_MICROSEC);
}

PUBLIC void
reap_needed(struct ccnr_handle *h, int init_delay_usec)
{
    if (h->reaper == NULL)
        h->reaper = ccn_schedule_event(h->sched, init_delay_usec, reap, NULL, 0);
}

/**
 * Age out the old forwarding table entries
 */
static int
age_forwarding(struct ccn_schedule *sched,
             void *clienth,
             struct ccn_scheduled_event *ev,
             int flags)
{
    struct ccnr_handle *h = clienth;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_forwarding *f;
    struct ccn_forwarding *next;
    struct ccn_forwarding **p;
    struct nameprefix_entry *npe;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        h->age_forwarding = NULL;
        return(0);
    }
    hashtb_start(h->nameprefix_tab, e);
    for (npe = e->data; npe != NULL; npe = e->data) {
        p = &npe->forwarding;
        for (f = npe->forwarding; f != NULL; f = next) {
            next = f->next;
            if ((f->flags & CCN_FORW_REFRESHED) == 0 ||
                  fdholder_from_fd(h, f->filedesc) == NULL) {
                if (h->debug & 2) {
                    struct fdholder *fdholder = fdholder_from_fd(h, f->filedesc);
                    if (fdholder != NULL) {
                        struct ccn_charbuf *prefix = ccn_charbuf_create();
                        ccn_name_init(prefix);
                        ccn_name_append_components(prefix, e->key, 0, e->keysize);
                        ccnr_debug_ccnb(h, __LINE__, "prefix_expiry", fdholder,
                                prefix->buf,
                                prefix->length);
                        ccn_charbuf_destroy(&prefix);
                    }
                }
                *p = next;
                free(f);
                f = NULL;
                continue;
            }
            f->expires -= CCN_FWU_SECS;
            if (f->expires <= 0)
                f->flags &= ~CCN_FORW_REFRESHED;
            p = &(f->next);
        }
        hashtb_next(e);
    }
    hashtb_end(e);
    h->forward_to_gen += 1;
    return(CCN_FWU_SECS*1000000);
}

PUBLIC void
age_forwarding_needed(struct ccnr_handle *h)
{
    if (h->age_forwarding == NULL)
        h->age_forwarding = ccn_schedule_event(h->sched,
                                               CCN_FWU_SECS*1000000,
                                               age_forwarding,
                                               NULL, 0);
}

static struct ccn_forwarding *
seek_forwarding(struct ccnr_handle *h,
                struct nameprefix_entry *npe, unsigned filedesc)
{
    struct ccn_forwarding *f;
    
    for (f = npe->forwarding; f != NULL; f = f->next)
        if (f->filedesc == filedesc)
            return(f);
    f = calloc(1, sizeof(*f));
    if (f != NULL) {
        f->filedesc = filedesc;
        f->flags = (CCN_FORW_CHILD_INHERIT | CCN_FORW_ACTIVE);
        f->expires = 0x7FFFFFFF;
        f->next = npe->forwarding;
        npe->forwarding = f;
    }
    return(f);
}

/**
 * Register or update a prefix in the forwarding table (FIB).
 *
 * @param h is the ccnr handle.
 * @param msg is a ccnb-encoded message containing the name prefix somewhere.
 * @param comps contains the delimiting offsets for the name components in msg.
 * @param ncomps is the number of relevant components.
 * @param filedesc indicates which fdholder to forward to.
 * @param flags are the forwarding entry flags (CCN_FORW_...), -1 for defaults.
 * @param expires tells the remaining lifetime, in seconds.
 * @returns -1 for error, or new flags upon success; the private flag
 *        CCN_FORW_REFRESHED indicates a previously existing entry.
 */
static int
ccnr_reg_prefix(struct ccnr_handle *h,
                const unsigned char *msg,
                struct ccn_indexbuf *comps,
                int ncomps,
                unsigned filedesc,
                int flags,
                int expires)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_forwarding *f = NULL;
    struct nameprefix_entry *npe = NULL;
    int res;
    struct fdholder *fdholder = NULL;
    
    if (flags >= 0 &&
        (flags & CCN_FORW_PUBMASK) != flags)
        return(-1);
    fdholder = fdholder_from_fd(h, filedesc);
    if (fdholder == NULL)
        return(-1);
    /* This is a bit hacky, but it gives us a way to set CCN_FACE_DC */
    if (flags >= 0 && (flags & CCN_FORW_LAST) != 0)
        fdholder->flags |= CCN_FACE_DC;
    hashtb_start(h->nameprefix_tab, e);
    res = nameprefix_seek(h, e, msg, comps, ncomps);
    if (res >= 0) {
        res = (res == HT_OLD_ENTRY) ? CCN_FORW_REFRESHED : 0;
        npe = e->data;
        f = seek_forwarding(h, npe, filedesc);
        if (f != NULL) {
            h->forward_to_gen += 1; // XXX - too conservative, should check changes
            f->expires = expires;
            if (flags < 0)
                flags = f->flags & CCN_FORW_PUBMASK;
            f->flags = (CCN_FORW_REFRESHED | flags);
            res |= flags;
            if (h->debug & (2 | 4)) {
                struct ccn_charbuf *prefix = ccn_charbuf_create();
                struct ccn_charbuf *debugtag = ccn_charbuf_create();
                ccn_charbuf_putf(debugtag, "prefix,ff=%s%x",
                                 flags > 9 ? "0x" : "", flags);
                if (f->expires < (1 << 30))
                    ccn_charbuf_putf(debugtag, ",sec=%d", expires);
                ccn_name_init(prefix);
                ccn_name_append_components(prefix, msg,
                                           comps->buf[0], comps->buf[ncomps]);
                ccnr_debug_ccnb(h, __LINE__,
                                ccn_charbuf_as_string(debugtag),
                                fdholder,
                                prefix->buf,
                                prefix->length);
                ccn_charbuf_destroy(&prefix);
                ccn_charbuf_destroy(&debugtag);
            }
        }
        else
            res = -1;
    }
    hashtb_end(e);
    return(res);
}

/**
 * Register a prefix, expressed in the form of a URI.
 * @returns negative value for error, or new fdholder flags for success.
 */
PUBLIC int
ccnr_reg_uri(struct ccnr_handle *h,
             const char *uri,
             unsigned filedesc,
             int flags,
             int expires)
{
    struct ccn_charbuf *name;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    struct ccn_indexbuf *comps;
    int res;
    
    name = ccn_charbuf_create();
    ccn_name_init(name);
    res = ccn_name_from_uri(name, uri);
    if (res < 0)
        goto Bail;
    comps = ccn_indexbuf_create();
    d = ccn_buf_decoder_start(&decoder, name->buf, name->length);
    res = ccn_parse_Name(d, comps);
    if (res < 0)
        goto Bail;
    res = ccnr_reg_prefix(h, name->buf, comps, comps->n - 1,
                          filedesc, flags, expires);
Bail:
    ccn_charbuf_destroy(&name);
    ccn_indexbuf_destroy(&comps);
    return(res);
}

/**
 * Register prefixes, expressed in the form of a list of URIs.
 * The URIs in the charbuf are each terminated by nul.
 */
PUBLIC void
ccnr_reg_uri_list(struct ccnr_handle *h,
             struct ccn_charbuf *uris,
             unsigned filedesc,
             int flags,
             int expires)
{
    size_t i;
    const char *s;
    s = ccn_charbuf_as_string(uris);
    for (i = 0; i + 1 < uris->length; i += strlen(s + i) + 1)
        ccnr_reg_uri(h, s + i, filedesc, flags, expires);
}

/**
 * Recompute the contents of npe->forward_to and npe->flags
 * from forwarding lists of npe and all of its ancestors.
 */
PUBLIC void
update_forward_to(struct ccnr_handle *h, struct nameprefix_entry *npe)
{
    struct ccn_indexbuf *x = NULL;
    struct ccn_indexbuf *tap = NULL;
    struct ccn_forwarding *f = NULL;
    struct nameprefix_entry *p = NULL;
    unsigned tflags;
    unsigned wantflags;
    unsigned moreflags;
    unsigned lastfaceid;
    unsigned namespace_flags;
    /* tap_or_last flag bit is used only in this procedure */
    unsigned tap_or_last = (1U << 31);

    x = npe->forward_to;
    if (x == NULL)
        npe->forward_to = x = ccn_indexbuf_create();
    else
        x->n = 0;
    wantflags = CCN_FORW_ACTIVE;
    lastfaceid = CCN_NOFACEID;
    namespace_flags = 0;
    for (p = npe; p != NULL; p = p->parent) {
        moreflags = CCN_FORW_CHILD_INHERIT;
        for (f = p->forwarding; f != NULL; f = f->next) {
            if (fdholder_from_fd(h, f->filedesc) == NULL)
                continue;
            tflags = f->flags;
            if (tflags & (CCN_FORW_TAP | CCN_FORW_LAST))
                tflags |= tap_or_last;
            if ((tflags & wantflags) == wantflags) {
                if (h->debug & 32)
                    ccnr_msg(h, "fwd.%d adding %u", __LINE__, f->filedesc);
                ccn_indexbuf_set_insert(x, f->filedesc);
                if ((f->flags & CCN_FORW_TAP) != 0) {
                    if (tap == NULL)
                        tap = ccn_indexbuf_create();
                    ccn_indexbuf_set_insert(tap, f->filedesc);
                }
                if ((f->flags & CCN_FORW_LAST) != 0)
                    lastfaceid = f->filedesc;
            }
            namespace_flags |= f->flags;
            if ((f->flags & CCN_FORW_CAPTURE) != 0)
                moreflags |= tap_or_last;
        }
        wantflags |= moreflags;
    }
    if (lastfaceid != CCN_NOFACEID)
        ccn_indexbuf_move_to_end(x, lastfaceid);
    npe->flags = namespace_flags;
    npe->fgen = h->forward_to_gen;
    if (x->n == 0)
        ccn_indexbuf_destroy(&npe->forward_to);
    ccn_indexbuf_destroy(&npe->tap);
    npe->tap = tap;
}

/**
 * This is where we consult the interest forwarding table.
 * @param h is the ccnr handle
 * @param from is the handle for the originating fdholder (may be NULL).
 * @param msg points to the ccnb-encoded interest message
 * @param pi must be the parse information for msg
 * @param npe should be the result of the prefix lookup
 * @result Newly allocated set of outgoing faceids (never NULL)
 */
static struct ccn_indexbuf *
get_outbound_faces(struct ccnr_handle *h,
    struct fdholder *from,
    unsigned char *msg,
    struct ccn_parsed_interest *pi,
    struct nameprefix_entry *npe)
{
    int checkmask = 0;
    int wantmask = 0;
    struct ccn_indexbuf *x;
    struct fdholder *fdholder;
    int i;
    int n;
    unsigned filedesc;
    
    while (npe->parent != NULL && npe->forwarding == NULL)
        npe = npe->parent;
    if (npe->fgen != h->forward_to_gen)
        update_forward_to(h, npe);
    x = ccn_indexbuf_create();
    if (pi->scope == 0 || npe->forward_to == NULL || npe->forward_to->n == 0)
        return(x);
    if ((npe->flags & CCN_FORW_LOCAL) != 0)
        checkmask = (from != NULL && (from->flags & CCN_FACE_GG) != 0) ? CCN_FACE_GG : (~0);
    else if (pi->scope == 1)
        checkmask = CCN_FACE_GG;
    else if (pi->scope == 2)
        checkmask = from ? (CCN_FACE_GG & ~(from->flags)) : ~0;
    wantmask = checkmask;
    if (wantmask == CCN_FACE_GG)
        checkmask |= CCN_FACE_DC;
    for (n = npe->forward_to->n, i = 0; i < n; i++) {
        filedesc = npe->forward_to->buf[i];
        fdholder = fdholder_from_fd(h, filedesc);
        if (fdholder != NULL && fdholder != from &&
            ((fdholder->flags & checkmask) == wantmask)) {
            if (h->debug & 32)
                ccnr_msg(h, "outbound.%d adding %u", __LINE__, fdholder->filedesc);
            ccn_indexbuf_append_element(x, fdholder->filedesc);
        }
    }
    return(x);
}

static int
pe_next_usec(struct ccnr_handle *h,
             struct propagating_entry *pe, int next_delay, int lineno)
{
    if (next_delay > pe->usec)
        next_delay = pe->usec;
    pe->usec -= next_delay;
    if (h->debug & 32) {
        struct ccn_charbuf *c = ccn_charbuf_create();
        ccn_charbuf_putf(c, "%p.%dof%d,usec=%d+%d",
                         (void *)pe,
                         pe->sent,
                         pe->outbound ? pe->outbound->n : -1,
                         next_delay, pe->usec);
        if (pe->interest_msg != NULL) {
            ccnr_debug_ccnb(h, lineno, ccn_charbuf_as_string(c),
                            fdholder_from_fd(h, pe->filedesc),
                            pe->interest_msg, pe->size);
        }
        ccn_charbuf_destroy(&c);
    }
    return(next_delay);
}

static int
do_propagate(struct ccn_schedule *sched,
             void *clienth,
             struct ccn_scheduled_event *ev,
             int flags)
{
    struct ccnr_handle *h = clienth;
    struct propagating_entry *pe = ev->evdata;
    (void)(sched);
    int next_delay = 1;
    int special_delay = 0;
    if (pe->interest_msg == NULL)
        return(0);
    if (flags & CCN_SCHEDULE_CANCEL) {
        consume(h, pe);
        return(0);
    }
    if ((pe->flags & CCN_PR_WAIT1) != 0) {
        pe->flags &= ~CCN_PR_WAIT1;
        adjust_predicted_response(h, pe, 1);
    }
    if (pe->usec <= 0) {
        if (h->debug & 2)
            ccnr_debug_ccnb(h, __LINE__, "interest_expiry",
                            fdholder_from_fd(h, pe->filedesc),
                            pe->interest_msg, pe->size);
        consume(h, pe);
        reap_needed(h, 0);
        return(0);        
    }
    if ((pe->flags & CCN_PR_STUFFED1) != 0) {
        pe->flags &= ~CCN_PR_STUFFED1;
        pe->flags |= CCN_PR_WAIT1;
        next_delay = special_delay = ev->evint;
    }
    else if (pe->outbound != NULL && pe->sent < pe->outbound->n) {
        unsigned filedesc = pe->outbound->buf[pe->sent];
        struct fdholder *fdholder = fdholder_from_fd(h, filedesc);
        if (fdholder != NULL && (fdholder->flags & CCN_FACE_NOSEND) == 0) {
            if (h->debug & 2)
                ccnr_debug_ccnb(h, __LINE__, "interest_to", fdholder,
                                pe->interest_msg, pe->size);
            pe->sent++;
            h->interests_sent += 1;
            h->interest_faceid = pe->filedesc;
            next_delay = nrand48(h->seed) % 8192 + 500;
            if (((pe->flags & CCN_PR_TAP) != 0) &&
                  ccn_indexbuf_member(nameprefix_for_pe(h, pe)->tap, pe->filedesc)) {
                next_delay = special_delay = 1;
            }
            else if ((pe->flags & CCN_PR_UNSENT) != 0) {
                pe->flags &= ~CCN_PR_UNSENT;
                pe->flags |= CCN_PR_WAIT1;
                next_delay = special_delay = ev->evint;
            }
            stuff_and_send(h, fdholder, pe->interest_msg, pe->size, NULL, 0);
            ccnr_meter_bump(h, fdholder->meter[FM_INTO], 1);
        }
        else
            ccn_indexbuf_remove_first_match(pe->outbound, filedesc);
    }
    /* The internal client may have already consumed the interest */
    if (pe->outbound == NULL)
        next_delay = CCN_INTEREST_LIFETIME_MICROSEC;
    else if (pe->sent == pe->outbound->n) {
        if (pe->usec <= CCN_INTEREST_LIFETIME_MICROSEC / 4)
            next_delay = CCN_INTEREST_LIFETIME_MICROSEC;
        else if (special_delay == 0)
            next_delay = CCN_INTEREST_LIFETIME_MICROSEC / 16;
        if (pe->fgen != h->forward_to_gen)
            replan_propagation(h, pe);
    }
    else {
        unsigned filedesc = pe->outbound->buf[pe->sent];
        struct fdholder *fdholder = fdholder_from_fd(h, filedesc);
        /* Wait longer before sending interest to ccnrc */
        if (fdholder != NULL && (fdholder->flags & CCN_FACE_DC) != 0)
            next_delay += 60000;
    }
    next_delay = pe_next_usec(h, pe, next_delay, __LINE__);
    return(next_delay);
}

/**
 * Adjust the outbound fdholder list for a new Interest, based upon
 * existing similar interests.
 * @result besides possibly updating the outbound set, returns
 *         an extra delay time before propagation.  A negative return value
 *         indicates the interest should be dropped.
 */
// XXX - rearrange to allow dummied-up "sent" entries.
// XXX - subtle point - when similar interests are present in the PIT, and a new dest appears due to prefix registration, only one of the set should get sent to the new dest.
static int
adjust_outbound_for_existing_interests(struct ccnr_handle *h, struct fdholder *fdholder,
                                       unsigned char *msg,
                                       struct ccn_parsed_interest *pi,
                                       struct nameprefix_entry *npe,
                                       struct ccn_indexbuf *outbound)
{
    struct propagating_entry *head = &npe->pe_head;
    struct propagating_entry *p;
    size_t presize = pi->offset[CCN_PI_B_Nonce];
    size_t postsize = pi->offset[CCN_PI_E] - pi->offset[CCN_PI_E_Nonce];
    size_t minsize = presize + postsize;
    unsigned char *post = msg + pi->offset[CCN_PI_E_Nonce];
    int k = 0;
    int max_redundant = 3; /* Allow this many dups from same fdholder */
    int i;
    int n;
    struct fdholder *otherface;
    int extra_delay = 0;

    if ((fdholder->flags & (CCN_FACE_MCAST | CCN_FACE_LINK)) != 0)
        max_redundant = 0;
    if (outbound != NULL) {
        for (p = head->next; p != head && outbound->n > 0; p = p->next) {
            if (p->size > minsize &&
                p->interest_msg != NULL &&
                p->usec > 0 &&
                0 == memcmp(msg, p->interest_msg, presize) &&
                0 == memcmp(post, p->interest_msg + p->size - postsize, postsize)) {
                /* Matches everything but the Nonce */
                otherface = fdholder_from_fd(h, p->filedesc);
                if (otherface == NULL)
                    continue;
                /*
                 * If scope is 2, we can't treat these as similar if
                 * they did not originate on the same host
                 */
                if (pi->scope == 2 &&
                    ((otherface->flags ^ fdholder->flags) & CCN_FACE_GG) != 0)
                    continue;
                if (h->debug & 32)
                    ccnr_debug_ccnb(h, __LINE__, "similar_interest",
                                    fdholder_from_fd(h, p->filedesc),
                                    p->interest_msg, p->size);
                if (fdholder->filedesc == p->filedesc) {
                    /*
                     * This is one we've already seen before from the same fdholder,
                     * but dropping it unconditionally would lose resiliency
                     * against dropped packets. Thus allow a few of them.
                     * Add some delay, though.
                     * XXX c.f. bug #13 // XXX - old bugid
                     */
                    extra_delay += npe->usec + 20000;
                    if ((++k) < max_redundant)
                        continue;
                    outbound->n = 0;
                    return(-1);
                }
                /*
                 * The existing interest from another fdholder will serve for us,
                 * but we still need to send this interest there or we
                 * could miss an answer from that direction. Note that
                 * interests from two other faces could conspire to cover
                 * this one completely as far as propagation is concerned,
                 * but it is still necessary to keep it around for the sake
                 * of returning content.
                 * This assumes a unicast link.  If there are multiple
                 * parties on this fdholder (broadcast or multicast), we
                 * do not want to send right away, because it is highly likely
                 * that we've seen an interest that one of the other parties
                 * is going to answer, and we'll see the answer, too.
                 */
                n = outbound->n;
                outbound->n = 0;
                for (i = 0; i < n; i++) {
                    if (p->filedesc == outbound->buf[i]) {
                        outbound->buf[0] = p->filedesc;
                        outbound->n = 1;
                        if ((otherface->flags & (CCN_FACE_MCAST | CCN_FACE_LINK)) != 0)
                            extra_delay += npe->usec + 10000;
                        break;
                    }
                }
                p->flags |= CCN_PR_EQV; /* Don't add new faces */
                // XXX - How robust is setting of CCN_PR_EQV?
                /*
                 * XXX - We would like to avoid having to keep this
                 * interest around if we get here with (outbound->n == 0).
                 * However, we still need to remember to send the content
                 * back to this fdholder, and the data structures are not
                 * there right now to represent this.  c.f. #100321.
                 */
            }
        }
    }
    return(extra_delay);
}

PUBLIC void
ccnr_append_debug_nonce(struct ccnr_handle *h, struct fdholder *fdholder, struct ccn_charbuf *cb) {
        unsigned char s[12];
        int i;
        
        for (i = 0; i < 3; i++)
            s[i] = h->ccnr_id[i];
        s[i++] = h->logpid >> 8;
        s[i++] = h->logpid;
        s[i++] = fdholder->filedesc >> 8;
        s[i++] = fdholder->filedesc;
        s[i++] = h->sec;
        s[i++] = h->usec * 256 / 1000000;
        for (; i < sizeof(s); i++)
            s[i] = nrand48(h->seed);
        ccnb_append_tagged_blob(cb, CCN_DTAG_Nonce, s, i);
}

PUBLIC void
ccnr_append_plain_nonce(struct ccnr_handle *h, struct fdholder *fdholder, struct ccn_charbuf *cb) {
        int noncebytes = 6;
        unsigned char *s = NULL;
        int i;
        
        ccn_charbuf_append_tt(cb, CCN_DTAG_Nonce, CCN_DTAG);
        ccn_charbuf_append_tt(cb, noncebytes, CCN_BLOB);
        s = ccn_charbuf_reserve(cb, noncebytes);
        for (i = 0; i < noncebytes; i++)
            s[i] = nrand48(h->seed) >> i;
        cb->length += noncebytes;
        ccn_charbuf_append_closer(cb);
}

/**
 * Schedules the propagation of an Interest message.
 */
PUBLIC int
propagate_interest(struct ccnr_handle *h,
                   struct fdholder *fdholder,
                   unsigned char *msg,
                   struct ccn_parsed_interest *pi,
                   struct nameprefix_entry *npe)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    unsigned char *nonce;
    size_t noncesize;
    struct ccn_charbuf *cb = NULL;
    int res;
    struct propagating_entry *pe = NULL;
    unsigned char *msg_out = msg;
    size_t msg_out_size = pi->offset[CCN_PI_E];
    int usec;
    int ntap;
    int delaymask;
    int extra_delay = 0;
    struct ccn_indexbuf *outbound = NULL;
    intmax_t lifetime;
    
    lifetime = ccn_interest_lifetime(msg, pi);
    outbound = get_outbound_faces(h, fdholder, msg, pi, npe);
    if (outbound->n != 0) {
        extra_delay = adjust_outbound_for_existing_interests(h, fdholder, msg, pi, npe, outbound);
        if (extra_delay < 0) {
            /*
             * Completely subsumed by other interests.
             * We do not have to worry about generating a nonce if it
             * does not have one yet.
             */
            if (h->debug & 16)
                ccnr_debug_ccnb(h, __LINE__, "interest_subsumed", fdholder,
                                msg_out, msg_out_size);
            h->interests_dropped += 1;
            ccn_indexbuf_destroy(&outbound);
            return(0);
        }
    }
    if (pi->offset[CCN_PI_B_Nonce] == pi->offset[CCN_PI_E_Nonce]) {
        /* This interest has no nonce; add one before going on */
        size_t nonce_start = 0;
        cb = charbuf_obtain(h);
        ccn_charbuf_append(cb, msg, pi->offset[CCN_PI_B_Nonce]);
        nonce_start = cb->length;
        (h->appnonce)(h, fdholder, cb);
        noncesize = cb->length - nonce_start;
        ccn_charbuf_append(cb, msg + pi->offset[CCN_PI_B_OTHER],
                               pi->offset[CCN_PI_E] - pi->offset[CCN_PI_B_OTHER]);
        nonce = cb->buf + nonce_start;
        msg_out = cb->buf;
        msg_out_size = cb->length;
    }
    else {
        nonce = msg + pi->offset[CCN_PI_B_Nonce];
        noncesize = pi->offset[CCN_PI_E_Nonce] - pi->offset[CCN_PI_B_Nonce];
    }
    hashtb_start(h->propagating_tab, e);
    res = hashtb_seek(e, nonce, noncesize, 0);
    pe = e->data;
    if (pe != NULL && pe->interest_msg == NULL) {
        unsigned char *m;
        m = calloc(1, msg_out_size);
        if (m == NULL) {
            res = -1;
            pe = NULL;
            hashtb_delete(e);
        }
        else {
            memcpy(m, msg_out, msg_out_size);
            pe->interest_msg = m;
            pe->size = msg_out_size;
            pe->filedesc = fdholder->filedesc;
            fdholder->pending_interests += 1;
            if (lifetime < INT_MAX / (1000000 >> 6) * (4096 >> 6))
                pe->usec = lifetime * (1000000 >> 6) / (4096 >> 6);
            else
                pe->usec = INT_MAX;
            delaymask = 0xFFF;
            pe->sent = 0;            
            pe->outbound = outbound;
            pe->flags = 0;
            if (pi->scope == 0)
                pe->flags |= CCN_PR_SCOPE0;
            else if (pi->scope == 1)
                pe->flags |= CCN_PR_SCOPE1;
            else if (pi->scope == 2)
                pe->flags |= CCN_PR_SCOPE2;
            pe->fgen = h->forward_to_gen;
            link_propagating_interest_to_nameprefix(h, pe, npe);
            ntap = reorder_outbound_using_history(h, npe, pe);
            if (outbound->n > ntap &&
                  outbound->buf[ntap] == npe->src &&
                  extra_delay == 0) {
                pe->flags = CCN_PR_UNSENT;
                delaymask = 0xFF;
            }
            outbound = NULL;
            res = 0;
            if (ntap > 0)
                (usec = 1, pe->flags |= CCN_PR_TAP);
            else
                usec = (nrand48(h->seed) & delaymask) + 1 + extra_delay;
            usec = pe_next_usec(h, pe, usec, __LINE__);
            ccn_schedule_event(h->sched, usec, do_propagate, pe, npe->usec);
        }
    }
    else {
        ccnr_msg(h, "Interesting - this shouldn't happen much - ccnr.c:%d", __LINE__);
        /* We must have duplicated an existing nonce, or ENOMEM. */
        res = -1;
    }
    hashtb_end(e);
    if (cb != NULL)
        charbuf_release(h, cb);
    ccn_indexbuf_destroy(&outbound);
    return(res);
}

static struct nameprefix_entry *
nameprefix_for_pe(struct ccnr_handle *h, struct propagating_entry *pe)
{
    struct nameprefix_entry *npe;
    struct propagating_entry *p;
    
    /* If any significant time is spent here, a direct link is possible, but costs space. */
    for (p = pe->next; p->filedesc != CCN_NOFACEID; p = p->next)
        continue;
    npe = (void *)(((char *)p) - offsetof(struct nameprefix_entry, pe_head));
    return(npe);
}

static void
replan_propagation(struct ccnr_handle *h, struct propagating_entry *pe)
{
    struct nameprefix_entry *npe = NULL;
    struct ccn_indexbuf *x = pe->outbound;
    struct fdholder *fdholder = NULL;
    struct fdholder *from = NULL;
    int i;
    int k;
    int n;
    unsigned filedesc;
    unsigned checkmask = 0;
    unsigned wantmask = 0;
    
    pe->fgen = h->forward_to_gen;
    if ((pe->flags & (CCN_PR_SCOPE0 | CCN_PR_EQV)) != 0)
        return;
    from = fdholder_from_fd(h, pe->filedesc);
    if (from == NULL)
        return;
    npe = nameprefix_for_pe(h, pe);
    while (npe->parent != NULL && npe->forwarding == NULL)
        npe = npe->parent;
    if (npe->fgen != h->forward_to_gen)
        update_forward_to(h, npe);
    if (npe->forward_to == NULL || npe->forward_to->n == 0)
        return;
    if ((pe->flags & CCN_PR_SCOPE1) != 0)
        checkmask = CCN_FACE_GG;
    if ((pe->flags & CCN_PR_SCOPE2) != 0)
        checkmask = CCN_FACE_GG & ~(from->flags);
    if ((npe->flags & CCN_FORW_LOCAL) != 0)
        checkmask = ((from->flags & CCN_FACE_GG) != 0) ? CCN_FACE_GG : (~0);
    wantmask = checkmask;
    if (wantmask == CCN_FACE_GG)
        checkmask |= CCN_FACE_DC;
    for (n = npe->forward_to->n, i = 0; i < n; i++) {
        filedesc = npe->forward_to->buf[i];
        fdholder = fdholder_from_fd(h, filedesc);
        if (fdholder != NULL && filedesc != pe->filedesc &&
            ((fdholder->flags & checkmask) == wantmask)) {
            k = x->n;
            ccn_indexbuf_set_insert(x, filedesc);
            if (x->n > k && (h->debug & 32) != 0)
                ccnr_msg(h, "at %d adding %u", __LINE__, filedesc);
        }
    }
    // XXX - should account for similar interests, history, etc.
}

/**
 * Checks whether this Interest message has been seen before by using
 * its Nonce, recording it in the process.  Also, if it has been
 * seen and the original is still propagating, remove the fdholder that
 * the duplicate arrived on from the outbound set of the original.
 */
PUBLIC int
is_duplicate_flooded(struct ccnr_handle *h, unsigned char *msg,
                     struct ccn_parsed_interest *pi, unsigned filedesc)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct propagating_entry *pe = NULL;
    int res;
    size_t nonce_start = pi->offset[CCN_PI_B_Nonce];
    size_t nonce_size = pi->offset[CCN_PI_E_Nonce] - nonce_start;
    if (nonce_size == 0)
        return(0);
    hashtb_start(h->propagating_tab, e);
    res = hashtb_seek(e, msg + nonce_start, nonce_size, 0);
    if (res == HT_OLD_ENTRY) {
        pe = e->data;
        if (promote_outbound(pe, filedesc) != -1)
            pe->sent++;
    }
    hashtb_end(e);
    return(res == HT_OLD_ENTRY);
}

/**
 * Finds the longest matching nameprefix, returns the component count or -1 for error.
 */
/* UNUSED */
PUBLIC int
nameprefix_longest_match(struct ccnr_handle *h, const unsigned char *msg,
                         struct ccn_indexbuf *comps, int ncomps)
{
    int i;
    int base;
    int answer = 0;
    struct nameprefix_entry *npe = NULL;

    if (ncomps + 1 > comps->n)
        return(-1);
    base = comps->buf[0];
    for (i = 0; i <= ncomps; i++) {
        npe = hashtb_lookup(h->nameprefix_tab, msg + base, comps->buf[i] - base);
        if (npe == NULL)
            break;
        answer = i;
        if (npe->children == 0)
            break;
    }
    ccnr_msg(h, "nameprefix_longest_match returning %d", answer);
    return(answer);
}

/**
 * Creates a nameprefix entry if it does not already exist, together
 * with all of its parents.
 */
PUBLIC int
nameprefix_seek(struct ccnr_handle *h, struct hashtb_enumerator *e,
                const unsigned char *msg, struct ccn_indexbuf *comps, int ncomps)
{
    int i;
    int base;
    int res = -1;
    struct nameprefix_entry *parent = NULL;
    struct nameprefix_entry *npe = NULL;
    struct propagating_entry *head = NULL;

    if (ncomps + 1 > comps->n)
        return(-1);
    base = comps->buf[0];
    for (i = 0; i <= ncomps; i++) {
        res = hashtb_seek(e, msg + base, comps->buf[i] - base, 0);
        if (res < 0)
            break;
        npe = e->data;
        if (res == HT_NEW_ENTRY) {
            head = &npe->pe_head;
            head->next = head;
            head->prev = head;
            head->filedesc = CCN_NOFACEID;
            npe->parent = parent;
            npe->forwarding = NULL;
            npe->fgen = h->forward_to_gen - 1;
            npe->forward_to = NULL;
            if (parent != NULL) {
                parent->children++;
                npe->flags = parent->flags;
                npe->src = parent->src;
                npe->osrc = parent->osrc;
                npe->usec = parent->usec;
            }
            else {
                npe->src = npe->osrc = CCN_NOFACEID;
                npe->usec = (nrand48(h->seed) % 4096U) + 8192;
            }
        }
        parent = npe;
    }
    return(res);
}
