/**
 * @file sync/UpdateSketch.c
 *  
 * Part of CCNx Sync.
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

void
UpdateAddName(struct SyncUpdateData *ud, struct ccn_charbuf *name) {
    // add the name (use copy), build the node as soon as the rules apply
}

struct SyncNodeComposite *
CacheEntryFetch(struct SyncUpdateData *ud, struct SyncHashCacheEntry *ce) {
    // causes the node to be swapped in
    if (ce == NULL) return NULL;
    if (ce->ncL != NULL) return ce->ncL;
    if (ce->ncR != NULL) return ce->ncR;
    if (ce->state & SyncHashState_fetching) return NULL;
    if (ce->state & SyncHashState_local) {
        // preferentially do the local fetch
    }
    if (ce->state & SyncHashState_remote) {
        // initiate the transfer
        ce->state |= SyncHashState_fetching;
    }
    return NULL;
}

struct ccn_charbuf *
BestName(struct SyncUpdateData *ud) {
    // returns the best current name from the updates
    struct ccn_charbuf *best = ud->lagName;
    if (best == NULL) {
        if (ud->ixBase->len > 0) {
            struct SyncNameAccum *src = (struct SyncNameAccum *) ixBase->client;
            int j = IndexSorter_Best(ud->ixBase);
            best = src->ents[j].name;
            ud->lagName = best;
        }
    }
    return best;
}

int
AdvanceName(struct SyncUpdateData *ud) {
    // advances to the next best current name
    // skips duplicates
    struct SyncNameAccum *src = (struct SyncNameAccum *) ixBase->client;
    for (;;) {
        struct ccn_charbuf *best = BestName(ud);
        IndexSorter_Rem(ud->ixBase);
        ud->lagName = NULL;
        if (ud->ixBase->len <= 0) break;
        int j = IndexSorter_Best(ud->ixBase);
        struct ccn_charbuf *next = src->ents[j].name;
        if (next == NULL) break;
        ud->lagName = next;
        if (SyncCmpNames(best, next) != 0) return 1;
    }
    return 0;
}

int
AcceptNode(struct SyncUpdateData *ud) {
    // accepts the remainder of nodes/names in the current node
    // if the accums at lower levels are empty, then share this node
    // (likely to be tricky code)
}

int
BuildTree(struct SyncUpdateData *ud,
          struct SyncTreeWorkerHead *tw,
          struct SyncNameAccum *src) {
    int res = 0;
    while (res == 0) {
        struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(tw);
        if (ent == NULL) break;
        struct SyncHashCacheEntry *ce = ent->cacheEntry;
        if (ce == NULL)
            // no cache entry, so bad news
            return -__LINE__;
        struct SyncNodeComposite *nc = CacheEntryFetch(ud, ce);
        if (nc == NULL) {
            if (ce->state & SyncHashState_fetching)
                // can't process this node now, it's being fetched
                return 0;
            return -__LINE__;
        }
        struct ccn_charbuf *name = BestName(ud);
        enum SyncCompareResult scr = SyncNodeCompareMinMax(nc, name);
        switch (scr) {
            case SCR_before:
                // name < Min(L), so add name, advance names
                UpdateAddName(ud, name);
                AdvanceName(ud);
                break;
            case SCR_min:
                // name == Min(L), advance names
                AdvanceName(ud);
                break;
            case SCR_max:
                // R == Max(L), discard R, advance both
                ent->pos++;
                AdvanceName(ud);
                break;
            case SCR_after:
                // R > Max(L), advance L
                AcceptNode(ud);
                break;
            case SCR_inside:
                // Min(L) < R < Max(L), so dive into L
                if (SyncTreeWorkerPush(tw) == NULL)
                    return - __LINE__;
                break;
            default:
                // this is really broken
                return -__LINE__;
        }
    }
}
