/**
 * @file sync/SyncBase.c
 *  
 * Part of CCNx Sync.
 */
/*
 * Copyright (C) 2011-2013 Palo Alto Research Center, Inc.
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

#include "SyncMacros.h"
#include "SyncBase.h"
#include "SyncPrivate.h"
#include "SyncUtil.h"
#include <stdarg.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <ccn/uri.h>

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

// used to forward debug messages to the client (if present)
extern void
sync_msg(struct SyncBaseStruct *base, const char *fmt, ...) {
    if (base != NULL) {
        struct sync_plumbing *sd = base->sd;
        if (sd != NULL && sd->sync_data == base
            && sd->client_methods != NULL && sd->client_methods->r_sync_msg != NULL) {
            va_list ap;
            char temp[5000];
            va_start(ap, fmt);
            vsnprintf(temp, sizeof(temp), fmt, ap);
            sd->client_methods->r_sync_msg(sd, "%s", temp);
            va_end(ap);
        }
    }
}

// Basic object support

static int
getEnvLimited(char *key, int lo, int hi, int def) {
    char *s = getenv(key);
    if (s != NULL && s[0] != 0) {
        int x = strtol(s, NULL, 10);
        if (x >= lo && x <= hi) return x;
    }
    return def;
}

/**
 * the default behavior for sync_start is to read the options, but not start anything
 */
static int
sync_start_default(struct sync_plumbing *sd,
                   struct ccn_charbuf *state_buf) {
    
    if (sd == NULL) return -1;
    struct SyncBaseStruct *base = (struct SyncBaseStruct *) sd->sync_data;
    if (base == NULL || base->sd != sd) return -1;
    
    char *here = "Sync.sync_start";
    
    // called when there is a Repo that is ready for Sync activity
    struct SyncPrivate *priv = base->priv;
    
    int enable = getEnvLimited("CCNS_ENABLE", 0, 1, 1);
    
    if (enable <= 0) return -1;
    
    char *debugStr = getenv("CCNS_DEBUG");
    int debug = 0;
    // TBD: use a centralized definition that is NOT in Repo
    if (debugStr == NULL)
        debug = CCNL_NONE;
    else if (strcasecmp(debugStr, "NONE") == 0)
        debug = CCNL_NONE;
    else if (strcasecmp(debugStr, "SEVERE") == 0)
        debug = CCNL_SEVERE;
    else if (strcasecmp(debugStr, "ERROR") == 0)
        debug = CCNL_ERROR;
    else if (strcasecmp(debugStr, "WARNING") == 0)
        debug = CCNL_WARNING;
    else if (strcasecmp(debugStr, "INFO") == 0)
        debug = CCNL_INFO;
    else if (strcasecmp(debugStr, "FINE") == 0)
        debug = CCNL_FINE;
    else if (strcasecmp(debugStr, "FINER") == 0)
        debug = CCNL_FINER;
    else if (strcasecmp(debugStr, "FINEST") == 0)
        debug = CCNL_FINEST;
    base->debug = debug;
    
    // enable/disable storing of sync tree nodes
    // default is to store
    priv->useRepoStore = getEnvLimited("CCNS_REPO_STORE", 0, 1, 1);
    
    // enable/disable stable recovery point
    // default is to disable recovery, but to calculate stable points
    priv->stableEnabled = getEnvLimited("CCNS_STABLE_ENABLED", 0, 1, 1);
    
    // get faux error percent
    priv->fauxErrorTrigger = getEnvLimited("CCNS_FAUX_ERROR",
                                           0, 99, 0);
    
    // get private flags for SyncActions
    priv->syncActionsPrivate = getEnvLimited("CCNS_ACTIONS_PRIVATE",
                                             0, 255, 3);
    
    // heartbeat rate
    priv->heartbeatMicros = getEnvLimited("CCNS_HEARTBEAT_MICROS",
                                          10000, 10*1000000, 200000);
    
    // root advise lifetime
    priv->rootAdviseFresh = getEnvLimited("CCNS_ROOT_ADVISE_FRESH",
                                          1, 30, 4);
    
    // root advise lifetime
    priv->rootAdviseLifetime = getEnvLimited("CCNS_ROOT_ADVISE_LIFETIME",
                                             1, 30, 20);
    
    // root advise lifetime
    priv->fetchLifetime = getEnvLimited("CCNS_NODE_FETCH_LIFETIME",
                                        1, 30, 4);
    
    // max node or content fetches busy per root
    priv->maxFetchBusy = getEnvLimited("CCNS_MAX_FETCH_BUSY",
                                       1, 100, 6);
    
    // max number of compares busy
    priv->maxComparesBusy = getEnvLimited("CCNS_MAX_COMPARES_BUSY",
                                          1, 100, 4);
    
    // # of bytes permitted for RootAdvise delta mode
    priv->deltasLimit = getEnvLimited("CCNS_DELTAS_LIMIT",
                                      0, 8000, 0);
    
    // scope for generated interests
    priv->syncScope = getEnvLimited("CCNS_SYNC_SCOPE",
                                    0, 3, 2);
    
    
    if (base->debug >= CCNL_INFO) {
        char temp[1024];
        int pos = 0;
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        "CCNS_ENABLE=%d",
                        enable);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_DEBUG=%s",
                        debugStr);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_REPO_STORE=%d",
                        priv->useRepoStore);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_STABLE_ENABLED=%d",
                        priv->stableEnabled);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_FAUX_ERROR=%d",
                        priv->fauxErrorTrigger);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_ACTIONS_PRIVATE=%d",
                        priv->syncActionsPrivate);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_HEARTBEAT_MICROS=%d",
                        priv->heartbeatMicros);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_ROOT_ADVISE_FRESH=%d",
                        priv->rootAdviseFresh);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_ROOT_ADVISE_LIFETIME=%d",
                        priv->rootAdviseLifetime);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_NODE_FETCH_LIFETIME=%d",
                        priv->fetchLifetime);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_MAX_FETCH_BUSY=%d",
                        priv->maxFetchBusy);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_MAX_COMPARES_BUSY=%d",
                        priv->maxComparesBusy);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_DELTAS_LIMIT=%d",
                        priv->deltasLimit);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",CCNS_SYNC_SCOPE=%d",
                        priv->syncScope);
        pos += snprintf(temp+pos, sizeof(temp)-pos,
                        ",defer_verification=%d",
                        ccn_defer_verification(sd->ccn, -1));
        sync_msg(base, "%s, %s", here, temp);
    }
    
    return 1;
}

static void
SyncFreeBase(struct SyncBaseStruct **bp) {
    if (bp != NULL) {
        struct SyncBaseStruct *base = *bp;
        *bp = NULL;
        if (base != NULL) {
            struct SyncPrivate *priv = base->priv;
            struct SyncNameAccumList *nal = NULL;
            struct SyncNameAccumList *nalNext = NULL;
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
            // free the name accums in the filter list
            if (priv->filters != NULL) {
                for (nal = priv->filters; nal != NULL; nal = nalNext) {
                    nalNext = nal->next;
                    SyncFreeNameAccumAndNames(nal->accum);
                    free(nal);
                }
            }
            if (priv->saveMethods != NULL) {
                free(priv->saveMethods);
            }
            ccn_charbuf_destroy(&priv->sliceCmdPrefix);
            ccn_charbuf_destroy(&priv->localHostPrefix);
            free(priv);
            free(base);
        }
    }
}

static int
sync_notify_default(struct sync_plumbing *sd,
                    struct ccn_charbuf *name,
                    int enum_index,
                    uint64_t seq_num) {
    struct SyncBaseStruct *base = (struct SyncBaseStruct *) sd->sync_data;
    if (base == NULL || base->sd != sd) return -1;
    // the default is to append the name to the namesToFetch for each root
    SyncAddName(base, name, seq_num);
    return 0;
}

extern void
sync_stop_default(struct sync_plumbing *sd,
                  struct ccn_charbuf *state_buf) {
    char *here = "Sync.sync_stop";
    if (sd == NULL) return;
    struct SyncBaseStruct *base = (struct SyncBaseStruct *) sd->sync_data;
    if (base == NULL || base->sd != sd) return;
    int debug = base->debug;
    if (debug >= CCNL_INFO)
        sync_msg(base, "%s", here);
    sd->sync_data = NULL;
    base->sd = NULL;
    SyncFreeBase(&base);
}

struct sync_plumbing_sync_methods defaultMethods = {
    &sync_start_default,
    &sync_notify_default,
    &sync_stop_default
};

extern struct SyncBaseStruct *
SyncNewBase(struct sync_plumbing *sd) {
    int64_t now = SyncCurrentTime();
    struct SyncBaseStruct *base = NEW_STRUCT(1, SyncBaseStruct);
    base->sd = sd;
    sd->sync_data = base;
    sd->sync_methods = &defaultMethods;
    struct SyncPrivate *priv = NEW_STRUCT(1, SyncPrivate);
    base->priv = priv;
    priv->topoAccum = SyncAllocNameAccum(4);
    priv->prefixAccum = SyncAllocNameAccum(4);
    priv->sliceCmdPrefix = ccn_charbuf_create();
    priv->localHostPrefix = ccn_charbuf_create();
    priv->comps = ccn_indexbuf_create();
    priv->lastCacheClean = now;
    ccn_name_from_uri(priv->localHostPrefix, "/%C1.M.S.localhost");
    ccn_name_from_uri(priv->sliceCmdPrefix, "/%C1.M.S.localhost/%C1.S.cs");
    return base;
}


