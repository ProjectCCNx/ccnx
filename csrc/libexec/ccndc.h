/**
 * @file ccndc.h
 * @brief Bring up a link to another ccnd.
 *
 * A CCNx program.
 *
 * Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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

#ifndef CCNDC_H
#define CCNDC_H

#include <ccn/charbuf.h>

struct ccndc_prefix_entry;
struct ccn_forwarding_entry;
struct ccn_face_instance;

/**
 * @brief Internal data structure for ccndc
 */
struct ccndc_data
{
    struct ccn          *ccn_handle;
    char                ccnd_id[32];       //id of local ccnd
    size_t              ccnd_id_size;
    int                 lifetime;
    struct ccn_charbuf  *local_scope_template; // scope 1 template
    struct ccn_charbuf  *no_name;   // an empty name
};

/**
 * @brief Initialize internal data structures
 * @returns "this" pointer
 */
struct ccndc_data *
ccndc_initialize_data(void);

/**
 * @brief Destroy internal data structures
 * @brief data pointer to "this"
 */
void
ccndc_destroy_data(struct ccndc_data **data);

/**
 * @brief Select a correct command based on the supplied argument
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (no messages are exchanged with ccnd)
 * @param cmd           command name (e.g., add, del, or destroyface)
 * @param options       command options
 * @param num_options   number of command line options (not checked if < 0)
 * @returns 0 on success, non zero means error, -99 means command line error
 */
int
ccndc_dispatch_cmd(struct ccndc_data *self,
                   int check_only,
                   const char *cmd,
                   const char *options,
                   int num_options);


/**
 * @brief Create a new FIB entry if it doesn't exist
 *
 * The call also automatically creates a face (if it doesn't exist)
 *
 * cmd format:
 *   uri (udp|tcp) host [port [flags [mcastttl [mcastif]]]])
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (nothing will be created)
 * @param cmd           add command without leading 'add' component
 * @returns 0 on success
 */
int
ccndc_add(struct ccndc_data *self,
          int check_only,
          const char *cmd);


/**
 * @brief Delete a FIB entry if it exists
 *
 * cmd format:
 *   uri (udp|tcp) host [port [flags [mcastttl [mcastif]]]])
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (nothing will be removed)
 * @param cmd           del command without leading 'del' component
 * @returns 0 on success
 */
int
ccndc_del(struct ccndc_data *self,
          int check_only,
          const char *cmd);

/**
 * @brief Delete a face and recreate it with the specified parameters and prefix
 *
 * cmd format:
 *   uri (udp|tcp) host [port [flags [mcastttl [mcastif]]]])
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (nothing will be created)
 * @param cmd           add command without leading 'renew' component
 * @returns 0 on success
 */
int
ccndc_renew(struct ccndc_data *self,
          int check_only,
          const char *cmd);

/**
 * @brief Create a new face without adding any prefix to it
 *
 * cmd format:
 *   (udp|tcp) host [port [flags [mcastttl [mcastif]]]])
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (nothing will be created)
 * @param cmd           create command without leading 'create' component
 * @returns 0 on success
 */
int
ccndc_create(struct ccndc_data *self,
          int check_only,
          const char *cmd);


/**
 * @brief Destroy a face
 *
 * cmd format:
 *   (udp|tcp) host [port [flags [mcastttl [mcastif [destroyface]]]]])
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (nothing will be removed)
 * @param cmd           destroy command without leading 'destroy' component
 * @returns 0 on success
 */
int
ccndc_destroy(struct ccndc_data *self,
              int check_only,
              const char *cmd);

/**
 * @brief Set/Get/Remove strategy for a prefix
 *
 * cmd format:
 *   <prefix> <strategy> <parameters> <freshness>
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (no operations will be performed)
 * @param cmd           action (set/get/remove strategy)
 * @param options       command without leading action component
 * @returns 0 on success
 */
enum strat_cmd {
    STRAT_SET, STRAT_GET, STRAT_REMOVE
};

int
ccndc_strategy(struct ccndc_data *self,
               int check_only,
               enum strat_cmd cmd,
               const char *options);

/**
 * brief Add (and if exists recreated) FIB entry based on guess from SRV records for a specified domain
 * @param self          data pointer to "this"
 * @param domain        domain name
 * @param domain_size   size of the "domain" variable
 *
 * @returns 0 on success
 */
int
ccndc_srv(struct ccndc_data *self,
          const unsigned char *domain,
          size_t domain_size);

/**
 * @brief Destroy face if it exists
 *
 * cmd format:
 *   faceid
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (nothing will be destroyed)
 * @param cmd           destroyface command without leading 'destroyface' component
 * @returns 0 on success
 */
int
ccndc_destroyface(struct ccndc_data *self,
                  int check_only,
                  const char *cmd);

/**
 * @brief Get ID of the local CCND
 *
 * CCND ID is recorded in supplied ccndc_data data structure
 *
 * @param self          data pointer to "this"
 */
int
ccndc_get_ccnd_id(struct ccndc_data *self);

/**
 * @brief Perform action using face management protocol
 * @param self          data pointer to "this"
 * @param action        action string
 * @param face_instance filled ccn_face_instance structure
 * @returns on success returns a new struct ccn_face_instance, describing created/destroyed face
 *         the structure needs to be manually destroyed
 */
struct ccn_face_instance *
ccndc_do_face_action(struct ccndc_data *self,
                     const char *action,
                     struct ccn_face_instance *face_instance);

/**
 * @brief Perform action using prefix management protocol
 * @param self          data pointer to "this"
 * @param action        action string
 * @param forwarding_entry filled ccn_forwarding_entry structure
 * @returns 0 on success
 */
int
ccndc_do_prefix_action(struct ccndc_data *self,
                       const char *action,
                       struct ccn_forwarding_entry *forwarding_entry);


struct ccn_strategy_selection *
ccndc_do_strategy_action(struct ccndc_data *self,
                         const char *action,
                         struct ccn_strategy_selection *strategy_selection);

//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////

struct ccn_forwarding_entry *
parse_ccn_forwarding_entry(struct ccndc_data *self,
                           const char *cmd_uri,
                           const char *cmd_flags,
                           int freshness);

struct ccn_face_instance *
parse_ccn_face_instance(struct ccndc_data *self,
                        const char *cmd_proto,
                        const char *cmd_host,     const char *cmd_port,
                        const char *cmd_mcastttl, const char *cmd_mcastif,
                        int freshness);

struct ccn_face_instance *
parse_ccn_face_instance_from_face(struct ccndc_data *self,
                                  const char *cmd_faceid);
struct ccn_strategy_selection *
parse_ccn_strategy_selection(struct ccndc_data *self,
                             const char *cmd_prefix,
                             const char *cmd_strategy,
                             const char *cmd_params,
                             int freshness);

#endif
