/**
 * @file csrc/sync_track.c
 *
 * Part of CCNx Sync.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

/* The following line for MacOS is a simple custom build of this file
 
 gcc -c -I.. -I../.. -I../../include sync_track.c
 
 */

/* The following line for MacOS is custom to avoid conflicts with libccn and libsync.
 * It is derived from ccnx/csrc/lib/dir.mk
 
 gcc -g -o sync_track -I. -I.. -I../.. -I../../include sync_track.c IndexSorter.c SyncBase.c SyncHashCache.c SyncNode.c SyncRoot.c SyncTreeWorker.c SyncUtil.c sync_diff.o ../lib/{ccn_client,ccn_charbuf,ccn_indexbuf,ccn_coding,ccn_dtag_table,ccn_schedule,ccn_extend_dict,ccn_buf_decoder,ccn_uri,ccn_buf_encoder,ccn_bloom,ccn_name_util,ccn_face_mgmt,ccn_reg_mgmt,ccn_digest,ccn_interest,ccn_keystore,ccn_seqwriter,ccn_signing,ccn_sockcreate,ccn_traverse,ccn_match,hashtb,ccn_merkle_path_asn1,ccn_sockaddrutil,ccn_setup_sockaddr_un,ccn_bulkdata,ccn_versioning,ccn_header,ccn_fetch,ccn_btree,ccn_btree_content,ccn_btree_store}.o -lcrypto
 
 */

#include <errno.h>
#include <stdarg.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/types.h>
#include <sys/time.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/ccn_private.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

#include <sync/SyncMacros.h>
#include <sync/SyncBase.h>
#include <sync/SyncNode.h>
#include <sync/SyncPrivate.h>
#include <sync/SyncUtil.h>
#include <sync/sync_depends.h>
#include <sync/sync_diff.h>

// types

enum local_flags {
    local_flags_null,
    local_flags_advise,
    local_flags_node,
    local_flags_other
};

struct hash_list {
    struct hash_list *next;
    struct SyncHashCacheEntry *ce;
    int64_t lastSeen;
};

struct parms {
    struct ccn_charbuf *topo;
    struct ccn_charbuf *prefix;
    struct SyncHashCacheEntry *last_ce;
    struct SyncHashCacheEntry *next_ce;
    struct SyncNameAccum *excl;
    struct SyncNameAccum *namesToAdd;
    struct SyncHashInfoList *hashSeen;
    int debug;
    struct ccn *ccn;
    int skipToHash;
    struct ccn_scheduled_event *ev;
    struct sync_diff_fetch_data *fd;
    struct sync_diff_data *sdd;
    struct sync_update_data *ud;
    int scope;
    int fetchLifetime;
    int needUpdate;
    int64_t add_accum;
    int64_t startTime;
    int64_t timeLimit;
};

// forward declarations

static int
start_interest(struct sync_diff_data *sdd);


// utilities and stuff

// noteErr2 is used to deliver error messages when there is no
// active root or base

static int
noteErr2(const char *why, const char *msg) {
    fprintf(stderr, "** ERROR: %s, %s\n", why, msg);
    fflush(stderr);
    return -1;
}

static void
my_r_sync_msg(struct sync_depends_data *sd, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    va_end(ap);
    fprintf(stdout, "\n");
    fflush(stdout);
}

// extractNode parses and creates a sync tree node from an upcall info
// returns NULL if there was any kind of error
static struct SyncNodeComposite *
extractNode(struct SyncRootStruct *root, struct ccn_upcall_info *info) {
    // first, find the content
    char *here = "sync_track.extractNode";
    const unsigned char *cp = NULL;
    size_t cs = 0;
    size_t ccnb_size = info->pco->offset[CCN_PCO_E];
    const unsigned char *ccnb = info->content_ccnb;
    int res = ccn_content_get_value(ccnb, ccnb_size, info->pco,
                                    &cp, &cs);
    if (res < 0 || cs < DEFAULT_HASH_BYTES) {
        SyncNoteFailed(root, here, "ccn_content_get_value", __LINE__);
        return NULL;
    }
    
    // second, parse the object
    struct SyncNodeComposite *nc = SyncAllocComposite(root->base);
    struct ccn_buf_decoder ds;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&ds, cp, cs);
    res |= SyncParseComposite(nc, d);
    if (res < 0) {
        // failed, so back out of the allocations
        SyncNoteFailed(root, here, "bad parse", -res);
        SyncFreeComposite(nc);
        nc = NULL;
    }
    return nc;
}

static struct sync_diff_fetch_data *
check_fetch_data(struct parms *p, struct sync_diff_fetch_data *fd) {
    struct sync_diff_fetch_data *each = p->fd;
    while (each != NULL) {
        struct sync_diff_fetch_data *next = each->next;
        if (each == fd) return fd;
        each = next;
    }
    return NULL;
}

static struct sync_diff_fetch_data *
find_fetch_data(struct parms *p, struct SyncHashCacheEntry *ce) {
    struct sync_diff_fetch_data *each = p->fd;
    while (each != NULL) {
        struct sync_diff_fetch_data *next = each->next;
        if (each->ce == ce) return each;
        each = next;
    }
    return NULL;
}

static int
delink_fetch_data(struct parms *p, struct sync_diff_fetch_data *fd) {
    if (fd != NULL) {
        struct sync_diff_fetch_data *each = p->fd;
        struct sync_diff_fetch_data *lag = NULL;
        while (each != NULL) {
            struct sync_diff_fetch_data *next = each->next;
            if (each == fd) {
                if (lag == NULL) p->fd = next;
                else lag->next = next;
                return 1;
            }
            lag = each;
            each = next;
        }
    }
    return 0;
}

static int
free_fetch_data(struct parms *p, struct sync_diff_fetch_data *fd) {
    if (delink_fetch_data(p, fd)) {
        struct ccn_closure *action = fd->action;
        if (action != NULL && action->data == fd)
            // break the link here
            action->data = NULL;
        fd->action = NULL;
        // only free the data if it is ours
        free(fd);
    }
}

static void
setCurrentHash(struct SyncRootStruct *root, struct SyncHashCacheEntry *ce) {
    struct ccn_charbuf *hash = root->currentHash;
    hash->length = 0;
    if (ce != NULL)
        ccn_charbuf_append_charbuf(hash, ce->hash);
}

static struct SyncHashCacheEntry *
chooseNextHash(struct parms *p) {
    struct SyncHashCacheEntry *nce = p->next_ce;
    if (nce != NULL && (nce->state & SyncHashState_covered) == 0
        && find_fetch_data(p, nce) == NULL)
        return nce;
    struct SyncHashInfoList *each = p->hashSeen;
    while (each != NULL) {
        struct SyncHashCacheEntry *ce = each->ce;
        if (ce != NULL && (ce->state & SyncHashState_covered) == 0
            && (nce == NULL || SyncCompareHash(ce->hash, nce->hash) > 0)
            && find_fetch_data(p, ce) == NULL)
            return ce;
        each = each->next;
    }
    return NULL;
}

// each_round starts a new comparison or update round,
// provided that the attached sync_diff is not busy
// we reuse the sync_diff_data, but reset the comparison hashes
// if we can't start one, we wait and try again
static int
each_round(struct ccn_schedule *sched,
           void *clienth,
           struct ccn_scheduled_event *ev,
           int flags) {
    if (ev == NULL)
        // not valid
        return -1;
    struct parms *p = ev->evdata;
    if (flags & CCN_SCHEDULE_CANCEL || p == NULL) {
        return -1;
    }
    if (p->needUpdate) {
        // do an update
        struct sync_update_data *ud = p->ud;
        switch (ud->state) {
            case sync_diff_state_init:
            case sync_update_state_error:
            case sync_update_state_done: {
                if (p->namesToAdd != NULL && p->namesToAdd->len > 0) {
                    start_sync_update(p->ud, p->namesToAdd);
                } else {
                    // update not very useful
                    p->needUpdate = 0;
                    return 1000;
                }
            }
            default:
                // we are busy right now
                break;
        }
    } else {
        // do a comparison
        struct sync_diff_data *sdd = p->sdd;
        switch (sdd->state) {
            case sync_diff_state_init:
            case sync_diff_state_error:
            case sync_diff_state_done: {
                // there is no comparison active
                struct SyncHashCacheEntry *ce = p->next_ce;
                if (ce != NULL
                    && ((ce->state & SyncHashState_covered) != 0))
                    ce = chooseNextHash(p);
                if (ce != NULL
                    && ((ce->state & SyncHashState_covered) == 0)
                    && ce != p->last_ce) {
                    // worth trying
                    p->next_ce = ce;
                    if (p->last_ce != NULL)
                        sdd->hashX = p->last_ce->hash;
                    if (p->next_ce != NULL)
                        sdd->hashY = p->next_ce->hash;
                    sync_diff_start(sdd);
                }
            }
            default:
                // we are busy right now
                break;
        }
    }
    return 500000; // 0.5 seconds
}

// start_round schedules a new comparison round,
// cancelling any previously scheduled round
static void
start_round(struct sync_diff_data *sdd, int micros) {
    struct SyncRootStruct *root = sdd->root;
    struct SyncBaseStruct *base = root->base;
    struct parms *p = sdd->client_data;
    struct ccn_scheduled_event *ev = p->ev;
    if (ev != NULL && ev->action != NULL && ev->evdata == sdd) {
        // this one may wait too long, kick it now!
        ccn_schedule_cancel(base->sd->sched, ev);
    }
    p->ev = ccn_schedule_event(base->sd->sched,
                               micros,
                               each_round,
                               p,
                               0);
    return;
}

// my_response is used to handle a reply
static enum ccn_upcall_res
my_response(struct ccn_closure *selfp,
            enum ccn_upcall_kind kind,
            struct ccn_upcall_info *info) {
    static char *here = "sync_track.my_response";
    enum ccn_upcall_res ret = CCN_UPCALL_RESULT_ERR;
    switch (kind) {
        case CCN_UPCALL_FINAL:
            free(selfp);
            ret = CCN_UPCALL_RESULT_OK;
            break;
        case CCN_UPCALL_CONTENT_UNVERIFIED:
            ret = CCN_UPCALL_RESULT_VERIFY;
            break;
        case CCN_UPCALL_CONTENT_KEYMISSING:
            ret = CCN_UPCALL_RESULT_FETCHKEY;
            break;
        case CCN_UPCALL_INTEREST_TIMED_OUT: {
            struct sync_diff_fetch_data *fd = selfp->data;
            enum local_flags flags = selfp->intdata;
            if (fd == NULL) break;
            struct sync_diff_data *sdd = fd->sdd;
            if (sdd == NULL) break;
            struct parms *p = sdd->client_data;
            free_fetch_data(p, fd);
            start_round(sdd, 10);
            ret = CCN_UPCALL_RESULT_OK;
            break;
        }
        case CCN_UPCALL_CONTENT_RAW:
        case CCN_UPCALL_CONTENT: {
            struct sync_diff_fetch_data *fd = selfp->data;
            enum local_flags flags = selfp->intdata;
            if (fd == NULL) break;
            struct sync_diff_data *sdd = fd->sdd;
            if (sdd == NULL) break;
            struct SyncRootStruct *root = sdd->root;
            if (root == NULL) break;
            struct parms *p = sdd->client_data;
            struct SyncNodeComposite *nc = extractNode(root, info);
            if (p->debug >= CCNL_FINE) {
                char fs[1024];
                int pos = 0;
                switch (flags) {
                    case local_flags_null: 
                        pos += snprintf(fs+pos, sizeof(fs)-pos, "null");
                        break;
                    case local_flags_advise:
                        pos += snprintf(fs+pos, sizeof(fs)-pos, "advise");
                        break;
                    case local_flags_node:
                        pos += snprintf(fs+pos, sizeof(fs)-pos, "node");
                        break;
                    default: 
                        pos += snprintf(fs+pos, sizeof(fs)-pos, "??%d", flags);
                        break;
                }
                if (nc != NULL)
                    pos += snprintf(fs+pos, sizeof(fs)-pos, ", nc OK");
                struct ccn_charbuf *nm = SyncNameForIndexbuf(info->content_ccnb,
                                                             info->content_comps);
                struct ccn_charbuf *uri = SyncUriForName(nm);
                pos += snprintf(fs+pos, sizeof(fs)-pos, ", %s", ccn_charbuf_as_string(uri));
                SyncNoteSimple(sdd->root, here, fs);
                ccn_charbuf_destroy(&nm);
                ccn_charbuf_destroy(&uri);
            }
            if (nc != NULL) {
                // the node exists, so store it
                // TBD: check the hash?
                struct parms *p = sdd->client_data;
                struct SyncHashCacheEntry *ce = SyncHashEnter(root->ch,
                                                              nc->hash->buf, nc->hash->length,
                                                              SyncHashState_remote);
                if (flags == local_flags_advise) {
                    p->hashSeen = SyncNoteHash(p->hashSeen, ce);
                    if (p->next_ce == NULL)
                        // have to have an initial place to start
                        p->next_ce = ce;
                }
                if (ce->ncR == NULL) {
                    // store the node
                    ce->ncR = nc;
                    SyncNodeIncRC(nc);
                } else {
                    // flush the node
                    SyncNodeDecRC(nc);
                    nc = NULL;
                }
                if (flags != local_flags_null) {
                    // from start_interest
                    start_round(sdd, 10);
                } else {
                    // from sync_diff
                    sync_diff_note_node(sdd, ce);
                }
                ret = CCN_UPCALL_RESULT_OK;
            }
            free_fetch_data(p, fd);
            break;
        default:
            // SHOULD NOT HAPPEN
            break;
        }
    }
    return ret;
}

static enum ccn_upcall_res
advise_interest_arrived(struct ccn_closure *selfp,
                        enum ccn_upcall_kind kind,
                        struct ccn_upcall_info *info) {
    // the reason to have a listener is to be able to listen for changes
    // in the collection without relying on the replies to our root advise
    // interests, which may not receive timely replies (althoug they eventually
    // get replies)
    static char *here = "sync_track.advise_interest_arrived";
    enum ccn_upcall_res ret = CCN_UPCALL_RESULT_ERR;
    switch (kind) {
        case CCN_UPCALL_FINAL:
            free(selfp);
            ret = CCN_UPCALL_RESULT_OK;
            break;
        case CCN_UPCALL_INTEREST: {
            struct sync_diff_data *sdd = selfp->data;
            if (sdd == NULL) {
                // this got cancelled
                ret = CCN_UPCALL_RESULT_OK;
                break;
            }
            struct SyncRootStruct *root = sdd->root;
            struct SyncBaseStruct *base = root->base;
            struct parms *p = sdd->client_data;
            int skipToHash = SyncComponentCount(sdd->root->topoPrefix) + 2;
            // skipToHash gets to the new hash
            // topo + marker + sliceHash
            const unsigned char *hp = NULL;
            size_t hs = 0;
            size_t bytes = 0;
            int failed = 0;
            if (p->debug >= CCNL_FINE) {
                struct ccn_charbuf *name = SyncNameForIndexbuf(info->interest_ccnb,
                                                               info->interest_comps);
                SyncNoteUri(root, here, "entered", name);
                ccn_charbuf_destroy(&name);
            }
            int cres = ccn_name_comp_get(info->interest_ccnb,
                                         info->interest_comps,
                                         skipToHash, &hp, &hs);
            struct SyncHashCacheEntry *ce = SyncHashEnter(root->ch, hp, hs,
                                                          SyncHashState_remote);
            if (ce == NULL || ce->state & SyncHashState_covered) {
                // should not be added
                if (p->debug >= CCNL_FINE)
                    SyncNoteSimple(sdd->root, here, "skipped");
            } else {
                // remember the remote hash, maybe start something
                if (p->debug >= CCNL_FINE)
                    SyncNoteSimple(sdd->root, here, "noting");
                p->hashSeen = SyncNoteHash(p->hashSeen, ce);
                start_interest(sdd);
            }
            ret = CCN_UPCALL_RESULT_OK;
            break;
        }
        default:
            // SHOULD NOT HAPPEN
            break;
    }
    return ret;
}

static int
start_interest(struct sync_diff_data *sdd) {
    static char *here = "sync_track.start_interest";
    struct SyncRootStruct *root = sdd->root;
    struct SyncBaseStruct *base = root->base;
    struct parms *p = sdd->client_data;
    struct SyncHashCacheEntry *ce = p->next_ce;
    enum local_flags flags = local_flags_advise;
    struct ccn_charbuf *prefix = SyncCopyName(sdd->root->topoPrefix);
    int res = 0;
    struct ccn *ccn = base->sd->ccn;
    if (ccn == NULL)
        return SyncNoteFailed(root, here, "bad ccn handle", __LINE__);
    res |= ccn_name_append_str(prefix, "\xC1.S.ra");
    res |= ccn_name_append(prefix, root->sliceHash->buf, root->sliceHash->length);
    p->skipToHash = SyncComponentCount(prefix);
    if (ce != NULL) {
        // append the best component seen
        res |= ccn_name_append(prefix, ce->hash->buf, ce->hash->length);
    } else {
        // append an empty component
        res |= ccn_name_append(prefix, "", 0);
    }
    struct SyncNameAccum *excl = SyncExclusionsFromHashList(root, NULL, p->hashSeen);
    struct ccn_charbuf *template = SyncGenInterest(NULL,
                                                   p->scope,
                                                   p->fetchLifetime,
                                                   -1, -1, excl);
    SyncFreeNameAccumAndNames(excl);
    struct ccn_closure *action = calloc(1, sizeof(*action));
    struct sync_diff_fetch_data *fd = calloc(1, sizeof(*fd));
    fd->sdd = sdd;
    fd->action = action;
    fd->startTime = SyncCurrentTime();
    // note: no ce available yet
    action->data = fd;
    action->intdata = local_flags_advise;
    action->p = &my_response;
    fd->next = p->fd;
    p->fd = fd;
    res |= ccn_express_interest(ccn, prefix, action, template);
    ccn_charbuf_destroy(&template);
    if (p->debug >= CCNL_FINE) {
        SyncNoteUri(sdd->root, here, "start_interest", prefix);
    }
    if (res < 0) {
        SyncNoteFailed(root, here, "ccn_express_interest failed", __LINE__);
        // return the resources, must free fd first!
        free_fetch_data(p, fd);
        free(action);
        return -1;
    }
    return 1;
}

static int
my_get(struct sync_diff_get_closure *fc,
       struct sync_diff_fetch_data *fd) {
    char *here = "sync_track.my_get";
    struct sync_diff_data *sdd = fc->sdd;
    struct parms *p = sdd->client_data;
    struct SyncRootStruct *root = sdd->root;
    struct SyncBaseStruct *base = root->base;
    struct SyncHashCacheEntry *ce = fd->ce;
    int res = 0;
    struct ccn *ccn = base->sd->ccn;
    if (ccn == NULL)
        return SyncNoteFailed(root, here, "bad ccn handle", __LINE__);
    if (ce == NULL)
        return SyncNoteFailed(root, here, "bad cache entry", __LINE__);
    // first, check for existing fetch of same hash
    struct ccn_charbuf *hash = ce->hash;
    struct ccn_charbuf *name = SyncCopyName(sdd->root->topoPrefix);
    ccn_name_append_str(name, "\xC1.S.nf");
    res |= ccn_name_append(name, root->sliceHash->buf, root->sliceHash->length);
    if (hash == NULL || hash->length == 0)
        res |= ccn_name_append(name, "", 0);
    else
        res |= ccn_name_append(name, ce->hash->buf, ce->hash->length);
    if (p->debug >= CCNL_FINE) {
        SyncNoteUri(sdd->root, here, "starting", name);
    }
    // note, this fd belongs to sync_diff, not us
    struct ccn_closure *action = calloc(1, sizeof(*action));
    action->data = fd;
    action->p = &my_response;
    fd->action = action;
    
    struct ccn_charbuf *template = SyncGenInterest(NULL,
                                                   root->priv->syncScope,
                                                   base->priv->fetchLifetime,
                                                   -1, 1, NULL);
    
    res = ccn_express_interest(ccn, name, action, template);
    ccn_charbuf_destroy(&template);
    if (res < 0) {
        SyncNoteFailed(root, here, "ccn_express_interest failed", __LINE__);
        free(action);
        return -1;
    }
    return 1;
}

// my_add is called when sync_diff discovers a new name
// right now all we do is log it
static int
my_add(struct sync_diff_add_closure *ac, struct ccn_charbuf *name) {
    char *here = "sync_track.my_add";
    struct sync_diff_data *sdd = ac->sdd;
    struct parms *p = sdd->client_data;
    if (p->debug >= CCNL_INFO) {
        if (name != NULL)
            SyncNoteUri(sdd->root, here, "adding", name);
        else {
            char temp[1024];
            int pos = 0;
            p->add_accum += sdd->namesAdded;
            pos += snprintf(temp+pos, sizeof(temp)-pos, "added %jd, accum %jd",
                            (intmax_t) sdd->namesAdded, (intmax_t) p->add_accum);
            SyncNoteSimple(sdd->root, here, temp);
        }
    }
    if (name == NULL) {
        // end of comparison, so fire off another round
        struct SyncRootStruct *root = sdd->root;
        struct ccn_charbuf *hash = p->next_ce->hash;
        struct SyncHashCacheEntry *ce = p->next_ce;
        int delay = 1000000;
        if (sdd->state == sync_diff_state_done) {
            // successful difference, so next_ce is covered
            ce->state |= SyncHashState_covered;
            delay = 10000;
            if (p->last_ce == NULL) {
                // first time through, just accept the new entry
                p->last_ce = ce;
                setCurrentHash(root, ce);
                p->ud->ceStart = ce;
            } else if (p->namesToAdd != NULL && p->namesToAdd->len > 0) {
                // need to update the entry
                p->needUpdate = 1;
                p->last_ce = ce;
                p->ud->ceStart = ce;
                delay = 1000;
            } else {
                // the last guess was not so good for the max, so revert
                ce = p->last_ce;
                p->next_ce = ce;
            }
        }
        start_round(sdd, delay);
    } else {
        // accumulate the names
        struct SyncNameAccum *acc = p->namesToAdd;
        if (acc == NULL) {
            acc = SyncAllocNameAccum(4);
            p->namesToAdd = acc;
        }
        SyncNameAccumAppend(acc, SyncCopyName(name), 0);
    }
    return 0;
}

static int
note_update_done(struct sync_done_closure *dc) {
    struct parms *p = dc->data;
    struct sync_update_data *ud = dc->ud;
    if (p != NULL && p->ud == ud && ud != NULL && ud->dc == dc) {
        // passes sanity check
        static char *here = "sync_track.note_update_done";
        if (ud->ceStop != ud->ceStart && ud->ceStop != NULL) {
            // we have a new hash that is better
            setCurrentHash(ud->root, ud->ceStop);
            ud->ceStart = ud->ceStop;
            if (p->debug >= CCNL_FINE)
                SyncNoteSimple(ud->root, here, "new hash set");
        } else {
            if (p->debug >= CCNL_FINE)
                SyncNoteSimple(ud->root, here, "no new hash");
        }
        p->needUpdate = 0;
        return 1;
    }
    return -1;
}

// the only client routine we might need is the logger
// there is no Repo in this application
struct sync_depends_client_methods client_methods = {
    my_r_sync_msg, NULL, NULL, NULL, NULL, NULL
};

static void
gettime(const struct ccn_gettime *self, struct ccn_timeval *result) {
    struct timeval now = {0};
    gettimeofday(&now, 0);
    result->s = now.tv_sec;
    result->micros = now.tv_usec;
}

// doTest initializes the base and root and other data,
// starts up 

int
doTest(struct parms *p) {
    char *here = "sync_track.doTest";
    int res = 0;
    p->startTime = SyncCurrentTime();
    if (ccn_connect(p->ccn, NULL) == -1) {
        return noteErr2(here, "could not connect to ccnd");
    }
    
    struct sync_depends_data *sd = calloc(1, sizeof(*sd));
    sd->client_methods = &client_methods;
    sd->ccn = p->ccn;
    sd->sched = ccn_get_schedule(p->ccn);
    if (sd->sched == NULL) {
        // TBD: I'm not happy about this, the handle should export a scheduler
        struct ccn_schedule *schedule = ccn_get_schedule(p->ccn);
        if (schedule == NULL) {
            struct ccn_gettime *timer = calloc(1, sizeof(*timer));
            timer->descr[0]='S';
            timer->micros_per_base = 1000000;
            timer->gettime = &gettime;
            timer->data = p->ccn;
            schedule = ccn_schedule_create(p->ccn, timer);
            ccn_set_schedule(p->ccn, schedule);
            sd->sched = schedule;
        }
    }
    
    // make base and root for collection
    struct SyncBaseStruct *base = SyncNewBase(sd);
    struct SyncRootStruct *root = SyncAddRoot(base, -1,
                                              p->topo, p->prefix,
                                              NULL);
    
    // gen the closures
    struct sync_diff_data *sdd = calloc(1, sizeof(*sdd));
    struct sync_diff_get_closure get_s;
    struct sync_diff_add_closure add_s;
    sdd->add = &add_s;
    sdd->add->sdd = sdd;
    sdd->add->add = my_add;
    sdd->add->data = p;
    sdd->get = &get_s;
    sdd->get->sdd = sdd;
    sdd->get->get = my_get;
    sdd->get->data = p;
    
    sdd->root = root;
    sdd->hashX = NULL;
    sdd->hashY = NULL;
    sdd->client_data = p;
    p->sdd = sdd;
    
    struct sync_done_closure done_s;
    struct sync_update_data *ud = calloc(1, sizeof(*ud));
    ud->root = root;
    ud->dc = &done_s;
    ud->dc->done = note_update_done;
    ud->dc->ud = ud;
    ud->dc->data = p;
    ud->client_data = p;
    p->ud = ud;
    
    if (root->base->debug > p->debug)
        p->debug = root->base->debug;
    else root->base->debug = p->debug;
    
    // register the root advise interest listener
    struct ccn_charbuf *prefix = SyncCopyName(sdd->root->topoPrefix);
    ccn_name_append_str(prefix, "\xC1.S.ra");
    res |= ccn_name_append(prefix, root->sliceHash->buf, root->sliceHash->length);
    struct ccn_closure *action = NEW_STRUCT(1, ccn_closure);
    action->data = sdd;
    action->p = &advise_interest_arrived;
    res |= ccn_set_interest_filter(p->ccn, prefix, action);
    if (res < 0) {
        res = noteErr2(here, "registration failed");
    } else {
        // start the very first round
        start_round(sdd, 10);
        
        // loop until error or time done
        for (;;) {
            ccn_run(p->ccn, 100);
            int64_t now = SyncCurrentTime();
            int64_t dt = SyncDeltaTime(p->startTime, now);
            if (dt > p->timeLimit) break;
            if (sdd->nodeFetchFailed > 0) break;
            if (sdd->state == sync_diff_state_error) break;
        }
        
    }
    if (sync_diff_stop(sdd) < 0) {
        res = -1;
        SyncNoteFailed(root, here, "sync_diff_stop failed", __LINE__);
    }
    
    free(sdd);
    
    return res;
}

int
main(int argc, char **argv) {
    int i = 1;
    int res = 0;
    int seen = 0;
    struct parms ps;
    struct parms *p = &ps;
    
    memset(p, 0, sizeof(*p));
    
    p->topo = ccn_charbuf_create();
    p->prefix = ccn_charbuf_create();
    p->debug = 0;
    p->timeLimit = 60*1000000; // default is one minute (kinda arbitrary)
    p->scope = -1;
    p->fetchLifetime = 4;
    
    while (i < argc && res >= 0) {
        char * sw = argv[i];
        i++;
        char *arg1 = NULL;
        char *arg2 = NULL;
        if (i < argc) arg1 = argv[i];
        if (i+1 < argc) arg2 = argv[i+1];
        if (strcasecmp(sw, "-debug") == 0 || strcasecmp(sw, "-d") == 0) {
            i++;
            p->debug = atoi(arg1);
        } else if (strcasecmp(sw, "-topo") == 0) {
            if (arg1 != NULL) {
                p->topo->length = 0;
                ccn_name_from_uri(p->topo, arg1);
                i++;
                seen++;
            }
        } else if (strcasecmp(sw, "-prefix") == 0) {
            if (arg1 != NULL) {
                p->prefix->length = 0;
                ccn_name_from_uri(p->prefix, arg1);
                i++;
                seen++;
            }
        } else if (strcasecmp(sw, "-secs") == 0) {
            if (arg1 != NULL) {
                int64_t secs = atoi(arg1);
                p->timeLimit = secs * 1000000;
                i++;
            }
        } else {
            noteErr2("invalid switch: ", sw);
            seen = 0;
            break;
        }
    }
    
    if (seen) {
        p->ccn = ccn_create();
        doTest(p);
        if (p->ccn != NULL) {
            ccn_disconnect(p->ccn);
            ccn_destroy(&p->ccn);
        }
    }
}