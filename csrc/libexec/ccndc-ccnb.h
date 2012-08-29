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
 */
int
ccndc_del (struct ccndc_data *self,
           int check_only,
           const char *cmd);

/**
 * @brief Destroy face if it exists
 *
 * cmd format:
 *   faceid
 *
 * @param self          data pointer to "this"
 * @param check_only    flag indicating that only command checking is requested (nothing will be destroyed)
 * @param cmd           destroyface command without leading 'destroyface' component
 */
int
ccndc_destroyface (struct ccndc_data *self,
                   int check_only,
                   const char *cmd);


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

// struct ccndc_prefix_entry *
// initialize_prefix_entry (void);

// void
// destroy_prefix_entry (struct ccndc_prefix_entry **entry);

// int
// parse_prefix_entry (struct ccndc_prefix_entry *entry,
//                     char *cmd_uri,
//                     char *cmd_proto,
//                     char *cmd_host, char *cmd_port,
//                     char *cmd_flags,
//                     char *cmd_mcastttl, char *cmd_mcastif);

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

// struct ccn_forwarding_entry {
//     const char *         action;         check
//     struct ccn_charbuf * name_prefix;    check
//     const unsigned char *ccnd_id;        check
//     size_t               ccnd_id_size;   check
//     unsigned             faceid;         
//     int                  flags;
//     int                  lifetime;
//     unsigned char store[48];
// };

// struct ccn_face_instance {
//     const char *         action;
//     const unsigned char *ccnd_id;
//     size_t               ccnd_id_size;
//     unsigned             faceid;
//     struct ccn_sockdescr descr;
//     int                  lifetime;
//     struct ccn_charbuf * store;
// };

// ccn_forwarding_entry
// struct ccn_forwarding_entry {
    
//     struct ccn_charbuf *prefix;
//     int iflags;

//     struct ccn_face_instance *fi;
//     // int imcastttl;
//     // int createface;
//     // int facenumber;
//     // char rhostnamebuf [NI_MAXHOST];
//     // char rhostportbuf [NI_MAXSERV];
// };


#endif // CCNDC_CCNB_H
