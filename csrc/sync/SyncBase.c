/**
 * @file sync/SyncBase.c
 * 
 * Part of CCNx Sync.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

#include "SyncActions.h"
#include "SyncBase.h"
#include "SyncPrivate.h"
#include "SyncUtil.h"
#include <stdlib.h>
#include <string.h>
#include <ccn/uri.h>
#include <ccnr/ccnr_msg.h>
#include <ccnr/ccnr_private.h>
#include <ccnr/ccnr_sync.h>

// Error support

extern void
SyncSetErrInner(struct SyncBaseStruct *base,
                enum SyncErrCode code,
                char * file, int line) {
    struct SyncErrStruct *err = NEW_STRUCT(1, SyncErrStruct);
    err->code = code;
    err->file = file;
    err->line = line;
    struct SyncErrStruct *lag = base->errList;
    while (lag != NULL) {
        struct SyncErrStruct *next = lag->next;
        if (next == NULL) break;
        lag = next;
    }
    if (lag != NULL) lag->next = err;
    else base->errList = err;
}

extern void
SyncClearErr(struct SyncBaseStruct *base) {
    for (;;) {
        struct SyncErrStruct *err = base->errList;
        if (err == NULL) break;
        base->errList = err->next;
        free(err);
    }
}



// Basic object support

extern struct SyncBaseStruct *
SyncNewBase(struct ccnr_handle *ccnr,
            struct ccn *ccn,
            struct ccn_schedule *sched) {
    sync_time now = SyncCurrentTime();
    struct SyncBaseStruct *base = NEW_STRUCT(1, SyncBaseStruct);
    base->ccnr = ccnr;
    base->ccn = ccn;
    base->sched = sched;
    struct SyncPrivate *priv = NEW_STRUCT(1, SyncPrivate);
    base->priv = priv;
    priv->topoAccum = SyncAllocNameAccum(4);
    priv->prefixAccum = SyncAllocNameAccum(4);
    priv->sliceCmdPrefix = ccn_charbuf_create();
    priv->localHostPrefix = ccn_charbuf_create();
    priv->comps = ccn_indexbuf_create();
    priv->stableTarget = CCNR_NULL_HWM;
    priv->stableStored = CCNR_NULL_HWM;
    priv->lastStable = now;
    priv->lastCacheClean = now;
    ccn_name_from_uri(priv->localHostPrefix, "/%C1.M.S.localhost");
    ccn_name_from_uri(priv->sliceCmdPrefix, "/%C1.M.S.localhost/%C1.S.cs");
    return base;
}

static int
getEnvLimited(char *key, int lo, int hi, int def) {
    char *s = getenv(key);
    if (s != NULL && s[0] != 0) {
        int x = strtol(s, NULL, 10);
        if (x >= lo && x <= hi) return x;
    }
    return def;
}

extern void
SyncInit(struct SyncBaseStruct *bp) {
    if (bp != NULL) {
        struct ccnr_handle *ccnr = bp->ccnr;
        char *here = "Sync.SyncInit";
        
        if (ccnr != NULL) {
            // called when there is a Repo that is ready for Sync activity
            // TBD: read sync state and restart at the saved commit point
            struct SyncPrivate *priv = bp->priv;
            bp->debug = ccnr->syncdebug;
            
            int enable = 1;
            char *s = getenv("SYNC_ENABLE");
            if (s != NULL && s[0] != 0)
                enable = strtol(s, NULL, 10);
            
            if (enable <= 0) return;
            
            char *debugStr = getenv("SYNC_DEBUG");
            
            // enable/disable storing of sync tree nodes
            // default is to store
            priv->useRepoStore = getEnvLimited("SYNC_REPO_STORE", 0, 1, 1);
            
            // enable/disable stable recovery point
            // default is to disable recovery, but to calculate stable points
            priv->stableEnabled = getEnvLimited("SYNC_STABLE_ENABLED", 0, 1, 1);
            
            // get faux error percent
            priv->fauxErrorTrigger = getEnvLimited("SYNC_FAUX_ERROR",
                                                   0, 99, 0);
            
            // get private flags for SyncActions
            priv->syncActionsPrivate = getEnvLimited("SYNC_ACTIONS_PRIVATE",
                                                     0, 255, 3);
            
            // heartbeat rate
            priv->heartbeatMicros = getEnvLimited("SYNC_HEARTBEAT_MICROS",
                                                  10000, 10*1000000, 200000);
            
            // root advise lifetime
            priv->rootAdviseFresh = getEnvLimited("SYNC_ROOT_ADVISE_FRESH",
                                                  1, 30, 4);
            
            // root advise lifetime
            priv->rootAdviseLifetime = getEnvLimited("SYNC_ROOT_ADVISE_LIFETIME",
                                                     1, 30, 20);
            
            // root advise lifetime
            priv->fetchLifetime = getEnvLimited("SYNC_NODE_FETCH_LIFETIME",
                                                1, 30, 4);

            // max node or content fetches busy per root
            priv->maxFetchBusy = getEnvLimited("SYNC_MAX_FETCH_BUSY",
                                               1, 100, 6);

            // max number of compares busy
            priv->maxComparesBusy = getEnvLimited("SYNC_MAX_COMPARES_BUSY",
                                               1, 100, 4);
            
            
            if (bp->debug >= CCNL_INFO) {
                char temp[1024];
                int pos = 0;
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                "SYNC_ENABLE=%d",
                                enable);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_DEBUG=%s",
                                debugStr);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_REPO_STORE=%d",
                                priv->useRepoStore);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_STABLE_ENABLED=%d",
                                priv->stableEnabled);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_FAUX_ERROR=%d",
                                priv->fauxErrorTrigger);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_ACTIONS_PRIVATE=%d",
                                priv->syncActionsPrivate);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_HEARTBEAT_MICROS=%d",
                                priv->heartbeatMicros);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_ROOT_ADVISE_FRESH=%d",
                                priv->rootAdviseFresh);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_ROOT_ADVISE_LIFETIME=%d",
                                priv->rootAdviseLifetime);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_NODE_FETCH_LIFETIME=%d",
                                priv->fetchLifetime);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_MAX_FETCH_BUSY=%d",
                                priv->maxFetchBusy);
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",SYNC_MAX_COMPARES_BUSY=%d",
                                priv->maxComparesBusy);
#if (CCN_API_VERSION >= 4004)
                pos += snprintf(temp+pos, sizeof(temp)-pos,
                                ",defer_verification=%d",
                                ccn_defer_verification(bp->ccn, -1));
#endif
                ccnr_msg(ccnr, "%s, %s", here, temp);
            }
            
            SyncStartHeartbeat(bp);
        }
    }
}

extern void
SyncFreeBase(struct SyncBaseStruct **bp) {
    if (bp != NULL) {
        struct SyncBaseStruct *base = *bp;
        *bp = NULL;
        if (base != NULL) {
            struct SyncPrivate *priv = base->priv;
            // free the errList
            SyncClearErr(base);
            // free the roots
            while (priv->rootHead != NULL) {
                if (SyncRemRoot(priv->rootHead) != NULL) break;
            }
            // free the name accums
            if (priv->topoAccum != NULL)
                SyncFreeNameAccumAndNames(priv->topoAccum);
            if (priv->prefixAccum != NULL)
                SyncFreeNameAccumAndNames(priv->prefixAccum);
            if (priv->comps != NULL)
                ccn_indexbuf_destroy(&priv->comps);
            ccn_charbuf_destroy(&priv->sliceCmdPrefix);
            free(priv);
            free(base);
        }
    }
}

// Enumeration support
extern int
SyncNotifyContent(struct SyncBaseStruct *base,
                  int enumeration,
                  ccnr_accession item,
                  struct ccn_charbuf *name) {
    // here for any updates, whether from time-based enumeration
    // or from prefix-based enumeration
    char *here = "Sync.SyncNotifyContent";
    
    if (base != NULL && base->ccnr != NULL) {
        struct SyncPrivate *priv = base->priv;
        int debug = base->debug;
        
        if (name == NULL) {
            // end of an enumeration
            if (enumeration == 0) {
                if (debug >= CCNL_WARNING)
                    ccnr_msg(base->ccnr, "%s, end of time-based enum?", here);
            } else if (enumeration == priv->sliceEnum) {
                priv->sliceEnum = 0;
                if (debug >= CCNL_INFO)
                    ccnr_msg(base->ccnr, "%s, all slice names seen", here);
            } else if (enumeration == priv->sliceBusy) {
                priv->sliceBusy = 0;
                struct SyncRootStruct *root = priv->rootHead;
                while (root != NULL) {
                    struct SyncRootPrivate *rp = root->priv;
                    if (enumeration == rp->sliceBusy) {
                        rp->sliceBusy = 0;
                        if (debug >= CCNL_INFO)
                            SyncNoteSimple(root, here, "slice enum done");
                        break;
                    }
                    root = root->next;
                }
                // may need a new enumeration started
                root = priv->rootHead;
                while (root != NULL) {
                    struct SyncRootPrivate *rp = root->priv;
                    if (rp->sliceBusy < 0) {
                        SyncStartSliceEnum(root);
                        break;
                    }
                    root = root->next;
                }  
            } else {
                if (debug >= CCNL_WARNING)
                    ccnr_msg(base->ccnr, "%s, end of what enum?", here);
            }
            return -1;
        }
        
        if (debug >= CCNL_FINE) {
            struct ccn_charbuf *uri = SyncUriForName(name);
            ccnr_msg(base->ccnr,
                     "%s, enum %d, %s!",
                     here, enumeration, ccn_charbuf_as_string(uri));
            ccn_charbuf_destroy(&uri);
        }
        
        struct ccn_indexbuf *comps = priv->comps;
        int splitRes = ccn_name_split(name, comps);
        if (splitRes < 0) {
            // really hould not happen!  but it does not hurt to log and ignore it
            if (debug >= CCNL_SEVERE)
                ccnr_msg(base->ccnr, "%s, invalid name!", here);
            return 0;
        }
        
        unsigned char *comp0 = NULL;
        size_t size0 = 0;
        unsigned char *comp1 = NULL;
        size_t size1 = 0;
        ccn_name_comp_get(name->buf, comps, 0, 
                          (const unsigned char **) &comp0, &size0);
        ccn_name_comp_get(name->buf, comps, 1,
                          (const unsigned char **) &comp1, &size1);
        ccnr_accession mark = item;
        if (SyncPrefixMatch(priv->localHostPrefix, name, 0)) {
            // to the local host, don't update the stable target
            mark = CCNR_NULL_ACCESSION;
            if (SyncPrefixMatch(priv->sliceCmdPrefix, name, 0))
                // this is a new slice
                SyncHandleSlice(base, name);
        }
        if (mark != CCNR_NULL_ACCESSION)
            priv->stableTarget = ccnr_hwm_update(base->ccnr, priv->stableTarget, mark);
        
        // add the name to any applicable roots
        SyncAddName(base, name, item);
        return 0;
    }
    return -1;
}

extern void
SyncShutdown(struct SyncBaseStruct *bp) {
    char *here = "Sync.SyncShutdown";
    int debug = bp->debug;
    if (debug >= CCNL_INFO)
        ccnr_msg(bp->ccnr, "%s", here);
    // TBD: shutdown the hearbeat
    // TBD: unregister the prefixes
}


