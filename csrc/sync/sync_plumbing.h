/*
 * @file sync/sync_plumbing.h
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

/*
 * Comments and Questions about this draft.
 *
 * 1. This draft only includes the definition of the methods supplied by Sync
 *    and required by Sync.
 * 2. There is no creation method defined in this interface, details TBD.
 * 3. We assume that the charbuf definition is the common CCN one.
 * 4. The methods that can be NULL are there to provide access to a local Repo
 *    for faster and more efficient access.  If they are NULL then any content
 *    access must go through the normal CCN interfaces.
 * 5. It might be a good idea to provide some way to access configuration or
 *    tuning parameters for Sync.  Right now those come through env variables.
 */

#ifndef CCN_SyncDepends
#define CCN_SyncDepends

#include <sys/types.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>

struct sync_plumbing;           // forward def
struct sync_plumbing_sync_methods;   // forward def
struct sync_plumbing_client_methods; // forward def

struct sync_plumbing {
    struct ccn *ccn;                // ccn handle to share
    struct ccn_schedule *sched;     // scheduler to share
    
    // sync data and methods
    void *sync_data;
    struct sync_plumbing_sync_methods *sync_methods;
    
    // client data and methods
    void *client_data;
    struct sync_plumbing_client_methods *client_methods;
    
};

struct sync_plumbing_sync_methods {
    // call to start sync processing
    // state_buf holds recovery data (from last sync_stop)
    // returns < 0 if a failure occurred
    // returns 0 if the name updates should fully restart
    // returns > 0 if the name updates should restart at last fence
    int (* sync_start)(struct sync_plumbing *sd,
                       struct ccn_charbuf *state_buf);
    
    // call to add a name to the sync trees
    // from r_sync_enumerate (if enum_index > 0)
    // or from the name updates (if enum_index == 0)
    //    (for name updates, the seq_num can be used to set the fence)
    // if (name == NULL), marks the end of enumeration
    // returns < 0 to indicate an error, and terminate the enumeration
    // returns 0 if the name was not used
    // returns > 0 if the name was used
    int (* sync_notify)(struct sync_plumbing *sd,
                        struct ccn_charbuf *name,
                        int enum_index,
                        uint64_t seq_num);
    
    // call to stop sync processing (and return resources)
    // state_buf holds recovery data (for next sync_start)
    void (* sync_stop)(struct sync_plumbing *sd,
                       struct ccn_charbuf *state_buf);
};

struct sync_plumbing_client_methods {
    // logging facility
    void (* r_sync_msg)(struct sync_plumbing *sd,
                        const char *fmt, ...);
    
    // sets a fence for repo-style recovery (see below),
    // where the fence is set no later than the seq_num
    // returns < 0 for an error
    // returns >= 0 for success
    // method is NULL if fence not supported
    int (* r_sync_fence)(struct sync_plumbing *sd,
                         uint64_t seq_num);
    
    // starts a name enumeration (via sync_notify), returns immediately
    // returns < 0 for an error
    // returns 0 if no names match the interest (optional)
    // returns an enumeration index > 0 if successful
    // (index will be passed to sync_notify)
    // method is NULL if no local enumeration
    int (* r_sync_enumerate)(struct sync_plumbing *sd,
                             struct ccn_charbuf *interest);
    
    // lookup interest locally (no pause)
    // returns < 0 for an error or not present, >= 0 if fetched
    // if (content != NULL), fills content with the signed content object
    // method is NULL if no local lookup
    int (* r_sync_lookup)(struct sync_plumbing *sd,
                          struct ccn_charbuf *interest,
                          struct ccn_charbuf *content);
    
    // stores signed content (no pause)
    // returns < 0 for an error
    // returns 0 if already stored (or is in progress)
    // returns > 0 if newly stored
    // method is NULL if no local store
    int (* r_sync_local_store)(struct sync_plumbing *sd,
                               struct ccn_charbuf *content);
    
    // stores signed content (no pause) from inside a content handler
    // returns < 0 for an error
    // returns 0 if already stored (or is in progress)
    // returns > 0 if newly stored
    // method is NULL if no local store
    enum ccn_upcall_res (* r_sync_upcall_store)(struct sync_plumbing *sd,
                                                enum ccn_upcall_kind kind,
                                                struct ccn_upcall_info *info);
};

/*
 * Intended use of Sync, Repo, and the fence.
 *
 * A call to r_sync_fence sets a "fence" marker that is remembered for any
 * clean shut down of a repo/sync pair.
 
 When the repo starts up after a clean shutdown then
 // the repo will call sync_notify with names for objects that were stored
 // no later than the last fence operation executed before the shut down
 // (this ensures that no names will be missed when updating the sync trees)
 // It is intended that Sync calls fence whenever all collections are stable,
 // which menas that no collections have pending names.  Since this may not 
 */

#endif



