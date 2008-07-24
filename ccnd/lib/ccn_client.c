#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/hashtb.h>

struct ccn {
    int sock;
    size_t outbufindex;
    struct ccn_charbuf *interestbuf;
    struct ccn_charbuf *inbuf;
    struct ccn_charbuf *outbuf;
    struct hashtb *interests_by_prefix;
    struct ccn_closure *default_content_action;
    struct hashtb *interest_filters;
    struct ccn_closure *default_interest_action;
    struct ccn_skeleton_decoder decoder;
    struct ccn_indexbuf *scratch_indexbuf;
    struct timeval now;
    int refresh_us;
    int err;
    int errline;
    int verbose_error;
    int tap;
};

struct expressed_interest;

struct interests_by_prefix {
    struct expressed_interest *list;
};

struct expressed_interest { /* keyed by components of name prefix */
    struct timeval lasttime;
    struct ccn_closure *action;
    int prefix_comps;
    unsigned char *morecomponents;
    unsigned morecomponents_size;
    unsigned char *template_stuff;
    unsigned template_stuff_size;
    int repeat;
    int target;
    int outstanding;
    struct expressed_interest *next;
};

struct interest_filter { /* keyed by components of name */
    struct ccn_closure *action;
};

#define NOTE_ERR(h, e) (h->err = (e), h->errline = __LINE__, ccn_note_err(h))
#define NOTE_ERRNO(h) NOTE_ERR(h, errno)

void
ccn_perror(struct ccn *h, const char * s)
{
    fprintf(stderr, "%s: error %d - ccn_client.c:%d[%d]\n",
	    s, h->err, h->errline, (int)getpid());
}

static int
ccn_note_err(struct ccn *h)
{
    if (h->verbose_error)
        fprintf(stderr, "ccn_client.c:%d[%d] - error %d\n",
                        h->errline, (int)getpid(), h->err);
    return(-1);
}

static struct ccn_indexbuf *
ccn_indexbuf_obtain(struct ccn *h)
{
    struct ccn_indexbuf *c = h->scratch_indexbuf;
    if (c == NULL)
        return(ccn_indexbuf_create());
    h->scratch_indexbuf = NULL;
    c->n = 0;
    return(c);
}

static void
ccn_indexbuf_release(struct ccn *h, struct ccn_indexbuf *c)
{
    c->n = 0;
    if (h->scratch_indexbuf == NULL)
        h->scratch_indexbuf = c;
    else
        ccn_indexbuf_destroy(&c);
}

static void
ccn_replace_handler(struct ccn *h, struct ccn_closure **dstp, struct ccn_closure *src)
{
    struct ccn_closure *old = *dstp;
    if (src == old)
        return;
    if (src != NULL)
        src->refcount++;
    *dstp = src;
    if (old != NULL && (--(old->refcount)) == 0) {
        (old->p)(old, CCN_UPCALL_FINAL, h, NULL, 0, NULL, 0, NULL, 0);
    }
}

struct ccn *
ccn_create(void)
{
    struct ccn *h;
    const char *s;

    h = calloc(1, sizeof(*h));
    if (h == NULL)
        return(h);
    h->sock = -1;
    h->interestbuf = ccn_charbuf_create();
    s = getenv("CCN_DEBUG");
    h->verbose_error = (s != NULL && s[0] != 0);
    s = getenv("CCN_TAP");
    if (s != NULL && s[0] != 0) {
	char tap_name[255];
	struct timeval tv;
	gettimeofday(&tv, NULL);
	if (snprintf(tap_name, 255, "%s-%d-%d-%d", s, (int)getpid(), (int)tv.tv_sec, (int)tv.tv_usec) >= 255) {
	    fprintf(stderr, "CCN_TAP path is too long: %s\n", s);
	} else {
	    h->tap = open(tap_name, O_WRONLY|O_APPEND|O_CREAT, S_IRWXU);
	    if (h->tap == -1) {
		perror("Unable to open CCN_TAP file");
	    } else {
		printf("CCN_TAP writing to %s\n", tap_name);
		fflush(stdout);
	    }
	}
    } else {
	h->tap = -1;
    }
    return(h);
}

int
ccn_connect(struct ccn *h, const char *name)
{
    struct sockaddr_un addr = {0};
    int res;
    char name_buf[60];
    h->err = 0;
    if (h == NULL || h->sock != -1)
        return(NOTE_ERR(h, EINVAL));
    if (name == NULL || name[0] == 0) {
        name = getenv(CCN_LOCAL_PORT_ENVNAME);
        if (name == NULL || name[0] == 0 || strlen(name) > 10) {
            name = CCN_DEFAULT_LOCAL_SOCKNAME;
        }
        else {
            snprintf(name_buf, sizeof(name_buf), "%s.%s",
                     CCN_DEFAULT_LOCAL_SOCKNAME, name);
            name = name_buf;
        }
    }
    h->sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (h->sock == -1)
        return(NOTE_ERRNO(h));
    strncpy(addr.sun_path, name, sizeof(addr.sun_path));
    addr.sun_family = AF_UNIX;
    res = connect(h->sock, (struct sockaddr *)&addr, sizeof(addr));
    if (res == -1)
        return(NOTE_ERRNO(h));
    res = fcntl(h->sock, F_SETFL, O_NONBLOCK);
    if (res == -1)
        return(NOTE_ERRNO(h));
    return(h->sock);
}

int
ccn_get_connection_fd(struct ccn *h)
{
    return(h->sock);
}

int
ccn_disconnect(struct ccn *h)
{
    int res;
    ccn_charbuf_destroy(&h->inbuf);
    ccn_charbuf_destroy(&h->outbuf);
    res = close(h->sock);
    h->sock = -1;
    if (res == -1)
        return(NOTE_ERRNO(h));
    return(0);
}

static void
replace_template(struct expressed_interest *interest,
    struct ccn_charbuf *interest_template)
{
    size_t start;
    size_t size;
    int res;
    struct ccn_parsed_interest pi = {0};
    if (interest->template_stuff != NULL)
        free(interest->template_stuff);
    interest->template_stuff = NULL;
    interest->template_stuff_size = 0;
    if (interest_template != NULL) {
        res = ccn_parse_interest(interest_template->buf,
                                 interest_template->length, &pi, NULL);
        if (res >= 0) {
            start = pi.offset[CCN_PI_E_NameComponentCount];
            size = pi.offset[CCN_PI_E_Count] - start;
            interest->template_stuff = calloc(1, size);
            if (interest->template_stuff != NULL) {
                memcpy(interest->template_stuff, interest_template->buf + start, size);
                interest->template_stuff_size = size;
            }
        }
    }
}

static void
replace_morecomponents(struct expressed_interest *interest,
                       const unsigned char *morecomponents, size_t morecomponents_size)
{
    if (interest->morecomponents != NULL)
        free(interest->morecomponents);
    interest->morecomponents = NULL;
    interest->morecomponents = 0;
    if (morecomponents_size > 0) {
        interest->morecomponents = calloc(1, morecomponents_size);
        if (interest->morecomponents != NULL) {
            memcpy(interest->morecomponents, morecomponents, morecomponents_size);
            interest->morecomponents_size = morecomponents_size;
        }
    }
}

static struct expressed_interest *
ccn_destroy_interest(struct ccn *h, struct expressed_interest *i)
{
    struct expressed_interest *ans = i->next;
    ccn_replace_handler(h, &(i->action), NULL);
    replace_morecomponents(i, NULL, 0);
    replace_template(i, NULL);
    free(i);
    return(ans);
}

void
ccn_destroy(struct ccn **hp)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn *h = *hp;
    if (h == NULL)
        return;
    ccn_disconnect(h);
    ccn_replace_handler(h, &(h->default_interest_action), NULL);
    ccn_replace_handler(h, &(h->default_content_action), NULL);
    if (h->interests_by_prefix != NULL) {
        for (hashtb_start(h->interests_by_prefix, e); e->data != NULL; hashtb_next(e)) {
            struct interests_by_prefix *entry = e->data;
            while (entry->list != NULL)
                entry->list = ccn_destroy_interest(h, entry->list);
        }
        hashtb_end(e);
        hashtb_destroy(&(h->interests_by_prefix));
    }
    if (h->interest_filters != NULL) {
        for (hashtb_start(h->interest_filters, e); e->data != NULL; hashtb_next(e)) {
            struct interest_filter *i = e->data;
            ccn_replace_handler(h, &(i->action), NULL);
        }
        hashtb_end(e);
        hashtb_destroy(&(h->interest_filters));
    }
    ccn_charbuf_destroy(&h->interestbuf);
    ccn_indexbuf_destroy(&h->scratch_indexbuf);
    if (h->tap != -1) {
	close(h->tap);
    }
    free(h);
    *hp = NULL;
}

/*
 * ccn_check_namebuf: check that name is valid
 * Returns the byte offset of the end of prefix portion,
 * as given by prefix_comps, or -1 for error.
 * prefix_comps = -1 means the whole name is the prefix.
 */
static int
ccn_check_namebuf(struct ccn *h, struct ccn_charbuf *namebuf, int prefix_comps)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    int i = 0;
    int ans = 0;
    d = ccn_buf_decoder_start(&decoder, namebuf->buf, namebuf->length);
    if (ccn_buf_match_dtag(d, CCN_DTAG_Name)) {
        ccn_buf_advance(d);
        ans = d->decoder.token_index;
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, NULL, NULL)) {
                ccn_buf_advance(d);
            }
            ccn_buf_check_close(d);
            i += 1;
            if (prefix_comps < 0 || i == prefix_comps)
                ans = d->decoder.token_index;
        }
        ccn_buf_check_close(d);
    }
    if (d->decoder.state < 0 || ans < prefix_comps)
        return(-1);
    return(ans);
}

int
ccn_express_interest(struct ccn *h,
                     struct ccn_charbuf *namebuf,
                     int prefix_comps,
                     struct ccn_closure *action,
                     struct ccn_charbuf *interest_template)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int res;
    int prefixend;
    struct expressed_interest *interest;
    struct interests_by_prefix *entry = NULL;
    if (h->interests_by_prefix == NULL) {
        h->interests_by_prefix = hashtb_create(sizeof(struct interests_by_prefix), NULL);
        if (h->interests_by_prefix == NULL)
            return(NOTE_ERRNO(h));
    }
    prefixend = ccn_check_namebuf(h, namebuf, prefix_comps);
    if (prefixend < 0)
        return(prefixend);
    /*
     * To make it easy to lookup prefixes of names, we keep only
     * the prefix name components as the key in the hash table.
     */
    hashtb_start(h->interests_by_prefix, e);
    res = hashtb_seek(e, namebuf->buf + 1, prefixend - 1, 0);
    entry = e->data;
    if (entry == NULL) {
        NOTE_ERRNO(h);
        return(res);
    }
    interest = calloc(1, sizeof(*interest));
    if (interest == NULL) {
        NOTE_ERRNO(h);
        return(-1);
    }
    interest->prefix_comps = prefix_comps;
    ccn_replace_handler(h, &(interest->action), action);
    replace_morecomponents(interest, namebuf->buf + prefixend, namebuf->length - 1 - prefixend);
    replace_template(interest, interest_template);
    interest->target = 1;
    interest->next = entry->list;
    entry->list = interest;
    hashtb_end(e);
    return(0);
}

int
ccn_set_interest_filter(struct ccn *h, struct ccn_charbuf *namebuf,
                        struct ccn_closure *action)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int res;
    struct interest_filter *entry;
    if (h->interest_filters == NULL) {
        h->interest_filters = hashtb_create(sizeof(struct interest_filter), NULL);
        if (h->interest_filters == NULL)
            return(NOTE_ERRNO(h));
    }
    res = ccn_check_namebuf(h, namebuf, -1);
    if (res < 0)
        return(res);
    hashtb_start(h->interest_filters, e);
    res = hashtb_seek(e, namebuf->buf + 1, namebuf->length - 2, 0);
    if (res >= 0) {
        entry = e->data;
        ccn_replace_handler(h, &(entry->action), action);
        if (action == NULL)
            hashtb_delete(e);
    }
    hashtb_end(e);
    return(res);
}

int
ccn_set_default_interest_handler(struct ccn *h,
                                struct ccn_closure *action)
{
    if (h == NULL)
        return(-1);
    ccn_replace_handler(h, &(h->default_interest_action), action);
    return(0);
}

int
ccn_set_default_content_handler(struct ccn *h,
                                struct ccn_closure *action)
{
    if (h == NULL)
        return(-1);
    ccn_replace_handler(h, &(h->default_content_action), action);
    return(0);
}

static int
ccn_pushout(struct ccn *h)
{
    ssize_t res;
    size_t size;
    if (h->outbuf != NULL && h->outbufindex < h->outbuf->length) {
        size = h->outbuf->length - h->outbufindex;
        res = write(h->sock, h->outbuf->buf + h->outbufindex, size);
        if (res == size) {
            h->outbuf->length = h->outbufindex = 0;
            return(0);
        }
        if (res == -1)
            return ((errno == EAGAIN) ? 1 : NOTE_ERRNO(h));
        h->outbufindex += res;
        return(1);
    }
    return(0);
}

int
ccn_put(struct ccn *h, const void *p, size_t length)
{
    struct ccn_skeleton_decoder dd = {0};
    ssize_t res;
    if (h == NULL || p == NULL || length == 0)
        return(NOTE_ERR(h, EINVAL));
    res = ccn_skeleton_decode(&dd, p, length);
    if (!(res == length && dd.state == 0))
        return(NOTE_ERR(h, EINVAL));
    if (h->outbuf != NULL && h->outbufindex < h->outbuf->length) {
        // XXX - should limit unbounded growth of h->outbuf
        ccn_charbuf_append(h->outbuf, p, length); // XXX - check res
        return (ccn_pushout(h));
    }
    if (h->tap != -1) {
	write(h->tap, p, length);
    }
    res = write(h->sock, p, length);
    if (res == length)
        return(0);
    if (res == -1) {
        if (errno != EAGAIN)
            return(NOTE_ERRNO(h));
        res = 0;
    }
    if (h->outbuf == NULL) {
        h->outbuf = ccn_charbuf_create();
        h->outbufindex = 0;
    }
    ccn_charbuf_append(h->outbuf, ((const unsigned char *)p)+res, length-res);
    return(1);
}

int
ccn_output_is_pending(struct ccn *h)
{
    return(h != NULL && h->outbuf != NULL && h->outbufindex < h->outbuf->length);
}

static void
ccn_refresh_interest(struct ccn *h, struct expressed_interest *interest,
                     const unsigned char *components, size_t components_size)
{
    struct ccn_charbuf *c = h->interestbuf;
    int res;
    char buf[20];
    c->length = 0;
    ccn_charbuf_append_tt(c, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(c, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append(c, components, components_size);
    if (interest->prefix_comps >= 0 && interest->morecomponents_size != 0) {
        ccn_charbuf_append(c, interest->morecomponents, interest->morecomponents_size);
        ccn_charbuf_append_closer(c);
        ccn_charbuf_append_tt(c, CCN_DTAG_NameComponentCount, CCN_DTAG);
        res = snprintf(buf, sizeof(buf), "%d", interest->prefix_comps);
        ccn_charbuf_append_tt(c, res, CCN_UDATA);
        ccn_charbuf_append(c, buf, res);
        ccn_charbuf_append_closer(c);
    }
    else
        ccn_charbuf_append_closer(c);
    if (interest->template_stuff != NULL)
        ccn_charbuf_append(c, interest->template_stuff,
                           interest->template_stuff_size);
    ccn_charbuf_append_closer(c);
    if (interest->outstanding < interest->target) {
        res = ccn_put(h, c->buf, c->length);
        if (res >= 0) {
            interest->outstanding += 1;
            interest->lasttime = h->now;
        }
    }
}

static void
ccn_dispatch_message(struct ccn *h, unsigned char *msg, size_t size)
{
    struct ccn_parsed_interest pi = {0};
    struct ccn_indexbuf *comps = ccn_indexbuf_obtain(h);
    int i;
    int res;
    res = ccn_parse_interest(msg, size, &pi, comps);
    if (res >= 0) {
        /* This message is an Interest */
        enum ccn_upcall_kind upcall_kind = CCN_UPCALL_INTEREST;
        if (h->interest_filters != NULL && comps->n > 0) {
            size_t keystart = comps->buf[0];
            unsigned char *key = msg + keystart;
            struct interest_filter *entry;
            for (i = comps->n - 1; i >= 0; i--) {
                entry = hashtb_lookup(h->interest_filters, key, comps->buf[i] - keystart);
                if (entry != NULL) {
                    res = (entry->action->p)(
                        entry->action,
                        upcall_kind,
                        h, msg, size, comps, i, NULL, 0);
                    if (res != -1)
                        upcall_kind = CCN_UPCALL_CONSUMED_INTEREST;
                }
            }
        }
        if (h->default_interest_action != NULL) {
            (h->default_interest_action->p)(
                h->default_interest_action,
                upcall_kind,
                h, msg, size, comps, 0, NULL, 0);
        }
    }
    else {
        /* This message should be a ContentObject. */
        struct ccn_parsed_ContentObject obj = {0};
        res = ccn_parse_ContentObject(msg, size, &obj, comps);
        if (res >= 0) {
            if (h->interests_by_prefix != NULL) {
                size_t keystart = comps->buf[0];
                unsigned char *key = msg + keystart;
                struct expressed_interest *interest = NULL;
                struct interests_by_prefix *entry = NULL;
                for (i = comps->n - 1; i >= 0; i--) {
                    entry = hashtb_lookup(h->interests_by_prefix, key, comps->buf[i] - keystart);
                    if (entry != NULL) {
                        for (interest = entry->list; interest != NULL; interest = interest->next) {
                            if (interest->target > 0 && interest->outstanding > 0) {
                                // XXX - At this point we need to check whether the content matches the rest of the qualifiers on the interest before doing the upcall.
                                interest->outstanding -= 1;
                                res = (interest->action->p)(interest->action,
                                                            CCN_UPCALL_CONTENT,
                                                            h, msg, size, comps, i, NULL, 0); // XXX pass matched_ccnb
                                if (res == CCN_UPCALL_RESULT_REEXPRESS)
                                    ccn_refresh_interest(h, interest, key, comps->buf[i] - keystart);
                                else {
                                    interest->target = 0;
                                    ccn_replace_handler(h, &(interest->action), NULL);
                                    replace_morecomponents(interest, NULL, 0);
                                    replace_template(interest, NULL);
                                }
                            }
                        }
                    }
                }
            }
            if (h->default_content_action != NULL) {
                (h->default_content_action->p)(
                    h->default_content_action,
                    CCN_UPCALL_CONTENT,
                    h, msg, size, comps, 0, NULL, 0);
            }
        }
    }
    ccn_indexbuf_release(h, comps);
}

static int
ccn_process_input(struct ccn *h)
{
    ssize_t res;
    ssize_t msgstart;
    unsigned char *buf;
    struct ccn_skeleton_decoder *d = &h->decoder;
    struct ccn_charbuf *inbuf = h->inbuf;
    if (inbuf == NULL)
        h->inbuf = inbuf = ccn_charbuf_create();
    if (inbuf->length == 0)
        memset(d, 0, sizeof(*d));
    buf = ccn_charbuf_reserve(inbuf, 8800);
    res = read(h->sock, buf, inbuf->limit - inbuf->length);
    if (res == 0) {
        ccn_disconnect(h);
        return(-1);
    }
    if (res == -1) {
        if (errno == EAGAIN)
            res = 0;
        else
            return(NOTE_ERRNO(h));
    }
    inbuf->length += res;
    msgstart = 0;
    ccn_skeleton_decode(d, buf, res);
    while (d->state == 0) {
        ccn_dispatch_message(h, inbuf->buf + msgstart, 
                              d->index - msgstart);
        msgstart = d->index;
        if (msgstart == inbuf->length) {
            inbuf->length = 0;
            return(0);
        }
        ccn_skeleton_decode(d, inbuf->buf + d->index,
                            inbuf->length - d->index);
    }
    if (msgstart < inbuf->length && msgstart > 0) {
        /* move partial message to start of buffer */
        memmove(inbuf->buf, inbuf->buf + msgstart,
                inbuf->length - msgstart);
        inbuf->length -= msgstart;
        d->index -= msgstart;
    }
    return(0);
}

static void
ccn_age_interest(struct ccn *h,
                 struct expressed_interest *interest,
                 const unsigned char *key, size_t keysize)
{
    int delta;
    int res;
    if (interest->lasttime.tv_sec + 30 < h->now.tv_sec) {
        interest->outstanding = 0;
        ccn_refresh_interest(h, interest, key, keysize);
        if (CCN_INTEREST_HALFLIFE_MICROSEC < h->refresh_us)
            h->refresh_us = CCN_INTEREST_HALFLIFE_MICROSEC;
        return;
    }
    delta = (h->now.tv_sec  - interest->lasttime.tv_sec)*1000000 +
    (h->now.tv_usec - interest->lasttime.tv_usec);
    while (delta >= CCN_INTEREST_HALFLIFE_MICROSEC) {
        interest->outstanding /= 2;
        delta -= CCN_INTEREST_HALFLIFE_MICROSEC;
    }
    if (delta < 0)
        delta = 0;
    if (CCN_INTEREST_HALFLIFE_MICROSEC - delta < h->refresh_us)
        h->refresh_us = CCN_INTEREST_HALFLIFE_MICROSEC - delta;
    interest->lasttime = h->now;
    while (delta > interest->lasttime.tv_usec) {
        delta -= 1000000;
        interest->lasttime.tv_sec -= 1;
    }
    interest->lasttime.tv_usec -= delta;
    if (interest->target > 0 && interest->outstanding == 0) {
        res = (interest->action->p)(
                                    interest->action,
                                    CCN_UPCALL_INTEREST_TIMED_OUT,
                                    h, NULL, 0, NULL, 0, NULL, 0); // XXX pass matched_ccnb and other stuff
        if (res == CCN_UPCALL_RESULT_REEXPRESS)
            ccn_refresh_interest(h, interest, key, keysize);
    }
}

int
ccn_run(struct ccn *h, int timeout)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct timeval start;
    struct interests_by_prefix *entry;
    struct expressed_interest **ip;
    struct pollfd fds[1];
    int millisec;
    int res;
    memset(fds, 0, sizeof(fds));
    memset(&start, 0, sizeof(start));
    while (h->sock != -1) {
        h->refresh_us = 5 * CCN_INTEREST_HALFLIFE_MICROSEC;
        gettimeofday(&h->now, NULL);
        if (h->interests_by_prefix != NULL && !ccn_output_is_pending(h)) {
             for (hashtb_start(h->interests_by_prefix, e); e->data != NULL; hashtb_next(e)) {
                entry = e->data;
                for (ip = &(entry->list); (*ip) != NULL;) {
                    if ((*ip)->target != 0)
                        ccn_age_interest(h, (*ip), e->key, e->keysize);
                    if ((*ip)->target == 0)
                        (*ip) = ccn_destroy_interest(h, (*ip));
                    else
                        ip = &((*ip)->next);
                }
             }
             hashtb_end(e);
        }
        if (start.tv_sec == 0)
            start = h->now;
        else if (timeout >= 0) {
            millisec = (h->now.tv_sec  - start.tv_sec) *1000 +
                       (h->now.tv_usec - start.tv_usec)/1000;
            if (millisec > timeout)
                return(0);
        }
        fds[0].fd = h->sock;
        fds[0].events = POLLIN;
        if (ccn_output_is_pending(h))
            fds[0].events |= POLLOUT;
        millisec = (h->refresh_us) / 1000;
        if (timeout >= 0 && timeout < millisec)
            millisec = timeout;
        res = poll(fds, 1, millisec);
        if (res < 0 && errno != EINTR)
            return (NOTE_ERRNO(h));
        if (res > 0) {
            if ((fds[0].revents | POLLOUT) != 0)
                ccn_pushout(h);
            if ((fds[0].revents | POLLIN) != 0) {
                ccn_process_input(h);
            }
        }
        if (h->err == ENOTCONN)
            ccn_disconnect(h);
    }
    return(-1);
}
