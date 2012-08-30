/* -*- mode: C; c-file-style: "gnu"; c-basic-offset: 4; indent-tabs-mode:nil; -*- */
/**
 * @file ccndc-ccnb.h
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

#ifndef CCNDC_CCNB_H
#define CCNDC_CCNB_H

#include <ccn/charbuf.h>
#include <netdb.h>
#include <ccn/sockcreate.h>
#include <ccn/face_mgmt.h>
#include <ccn/reg_mgmt.h>

struct ccndc_data;
struct ccndc_prefix_entry;

/**
 * @brief Initialize internal data structures
 * @returns "this" pointer
 */
struct ccndc_data *
ccndc_initialize (void);

/**
 * @brief Destroy internal data structures
 * @brief data pointer to "this"
 */
void
ccndc_destroy (struct ccndc_data **data);

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
ccndc_dispatch_cmd (struct ccndc_data *self,
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
ccndc_add (struct ccndc_data *self,
           int check_only,
           const char *cmd);


/**
 * @brief Delete a FIB entry if it exists
 *
 * By default del command doesn't remove associated face. If it is indended, "destroyface" modifier
 * should be specified at the end of the command
 *
 * cmd format:
 *   uri (udp|tcp) host [port [flags [mcastttl [mcastif [destroyface]]]]])
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (nothing will be removed)
 * @param cmd           del command without leading 'del' component
 * @param destroyface   flag requesting destruction of an associate face (either command
 *                      line or this flag will activate face destruction)
 * @returns 0 on success
 */
int
ccndc_del (struct ccndc_data *self,
           int check_only,
           const char *cmd,
           int destroyface);

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
ccndc_destroyface (struct ccndc_data *self,
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
ccndc_get_ccnd_id (struct ccndc_data *self);

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


/**
 * @brief internal data structure for ccndc
 */
struct ccndc_data
{
    /// CCN handle
    struct ccn          *ccn_handle;

    /// storage of ID of local CCND
    char                ccnd_id [32];
    /// size of the stored ID
    size_t              ccnd_id_size;

    /// interest template for ccnget calls, specifying scope 1 (Local)
    struct ccn_charbuf  *local_scope_template;

    /// empty name necessary for signing purposes
    struct ccn_charbuf  *no_name;
};

//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////

struct ccn_forwarding_entry *
parse_ccn_forwarding_entry (struct ccndc_data *self,
                            const char *cmd_uri,
                            const char *cmd_flags,
                            int freshness);

struct ccn_face_instance *
parse_ccn_face_instance (struct ccndc_data *self,
                         const char *cmd_proto,
                         const char *cmd_host,     const char *cmd_port,
                         const char *cmd_mcastttl, const char *cmd_mcastif,
                         int freshness);

struct ccn_face_instance *
parse_ccn_face_instance_from_face (struct ccndc_data *self,
                                   const char *cmd_faceid);

#endif // CCNDC_CCNB_H
