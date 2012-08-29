/* -*- mode: C; c-file-style: "gnu"; c-basic-offset: 4; indent-tabs-mode:nil; -*- */
/**
 * @file ccndc.c
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

#include "ccndc-ccnb.h"

#include "ccndc-log.h"

// #include <limits.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <limits.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netdb.h>
#include <netinet/in.h>

#define BIND_8_COMPAT
#include <arpa/nameser.h>
#include <resolv.h>
#include <errno.h>

#include "ccn/ccn.h"
#include <ccn/ccnd.h>
#include "ccn/uri.h"
#include <ccn/face_mgmt.h>
#include <ccn/reg_mgmt.h>
#include <ccn/signing.h>

///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
// forward declarations "private" methods
static int
ccndc_get_ccnd_id (struct ccndc_data *s);

static struct ccn_face_instance *
do_face_action(struct ccndc_data *self,
               const char *action,
               struct ccn_face_instance *face_instance);

static int
do_prefix_action(struct ccndc_data *self,
                 const char *action,
                 struct ccn_forwarding_entry *forwarding_entry);

///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
    
struct ccndc_data *
ccndc_initialize (void) {
    struct ccndc_data *self;
    const char *msg = "Unable to initialize ccndc";

    self = calloc (1, sizeof(struct ccndc_data));
    if (self == NULL) {
        ON_ERROR_EXIT (-1, msg);
    }

    self->ccn_handle = ccn_create ();
    ON_ERROR_EXIT (ccn_connect (self->ccn_handle, NULL), "Unable to connect to local ccnd");

    ON_ERROR_EXIT (ccndc_get_ccnd_id (self), "Unable to obtain ID of local ccnd");
    
    /* Set up an Interest template to indicate scope 1 (Local) */
    self->local_scope_template = ccn_charbuf_create();
    if (self->local_scope_template == NULL) {
        ON_ERROR_EXIT(-1, msg);
    }
    
    ON_ERROR_EXIT (ccn_charbuf_append_tt    (self->local_scope_template, CCN_DTAG_Interest, CCN_DTAG), msg);
    ON_ERROR_EXIT (ccn_charbuf_append_tt    (self->local_scope_template, CCN_DTAG_Name, CCN_DTAG), msg);
    ON_ERROR_EXIT (ccn_charbuf_append_closer(self->local_scope_template), msg);	/* </Name> */
    ON_ERROR_EXIT (ccnb_tagged_putf         (self->local_scope_template, CCN_DTAG_Scope, "1"), msg);
    ON_ERROR_EXIT (ccn_charbuf_append_closer(self->local_scope_template), msg);	/* </Interest> */
    
    /* Create a null name */
    self->no_name = ccn_charbuf_create();
    if (self->no_name == NULL) {
        ON_ERROR_EXIT(-1, msg);
    }
    ON_ERROR_EXIT(ccn_name_init(self->no_name), msg);

    return self;
}

void
ccndc_destroy (struct ccndc_data **data) {
    struct ccndc_data *self = *data;

    if (self != NULL) {
        ccn_charbuf_destroy (&self->no_name);
        ccn_charbuf_destroy (&self->local_scope_template);

        ccn_disconnect (self->ccn_handle);
        ccn_destroy (&self->ccn_handle);
        free (self);
        *data = 0;
    }
}

struct ccn_forwarding_entry *
parse_ccn_forwarding_entry (struct ccndc_data *self,
                            const char *cmd_uri,
                            const char *cmd_flags,
                            int freshness)
{
    int res = 0;
    struct ccn_forwarding_entry *entry = calloc (1, sizeof (struct ccn_forwarding_entry));

    entry->name_prefix = ccn_charbuf_create ();
    if (entry->name_prefix == NULL) {
        ccndc_warn(__LINE__, "Fatal error: memory allocation failed");
        goto ExitOnError;
    }
    
    // copy static info
    entry->ccnd_id = (const unsigned char *)self->ccnd_id;
    entry->ccnd_id_size = self->ccnd_id_size;

    /* we will be creating the face to either add/delete a prefix on it */
    if (cmd_uri == NULL) {
        ccndc_warn(__LINE__, "command erro, missing CCNx URI\n");
        goto ExitOnError;
    }
    
    res = ccn_name_from_uri (entry->name_prefix, cmd_uri);
    if (res < 0) {
        ccndc_warn (__LINE__, "command error, bad CCNx URI '%s'\n", cmd_uri);
        goto ExitOnError;
    }

    entry->flags = -1;
    if (cmd_flags != NULL && cmd_flags[0] != 0) {
        char *endptr;
        entry->flags = strtol (cmd_flags, &endptr, 10);
        if ((endptr != &cmd_flags[strlen (cmd_flags)]) ||
            (entry->flags & ~CCN_FORW_PUBMASK) != 0) {
            ccndc_warn(__LINE__, "command error, invalid flags %s\n", cmd_flags);
            goto ExitOnError;
        }
    }

    entry->lifetime = freshness;
    
    return entry;

 ExitOnError:
    ccn_forwarding_entry_destroy (&entry);
    return NULL;
}


// creates a full structure without action
struct ccn_face_instance *
parse_ccn_face_instance (struct ccndc_data *self,
                         const char *cmd_proto,
                         const char *cmd_host,     const char *cmd_port,
                         const char *cmd_mcastttl, const char *cmd_mcastif,
                         int freshness)
{
    struct ccn_face_instance *entry = calloc (1, sizeof (struct ccn_face_instance));
    
    struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG)};
    struct addrinfo mcasthints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG | AI_NUMERICHOST)};
    struct addrinfo *raddrinfo = NULL;
    struct addrinfo *mcastifaddrinfo = NULL;
    char rhostnamebuf [NI_MAXHOST];
    char rhostportbuf [NI_MAXSERV];

    int off_address = -1, off_port = -1, off_source_address = -1;

    int res;
    int socktype;

    // allocate storage for Face data
    entry->store = ccn_charbuf_create ();

    // copy static info
    entry->ccnd_id = (const unsigned char *)self->ccnd_id;
    entry->ccnd_id_size = self->ccnd_id_size;

    if (cmd_proto == NULL) {
        ccndc_warn(__LINE__, "command error, missing address type\n");
        goto ExitOnError;
    }
    if (strcasecmp(cmd_proto, "udp") == 0) {
        entry->descr.ipproto = IPPROTO_UDP;
        socktype = SOCK_DGRAM;
    }
    else if (strcasecmp(cmd_proto, "tcp") == 0) {
        entry->descr.ipproto = IPPROTO_TCP;
        socktype = SOCK_STREAM;
    }
    else {
        ccndc_warn(__LINE__, "command error, unrecognized address type '%s'\n", cmd_proto);
        goto ExitOnError;
    }
        
    if (cmd_host == NULL) {
        ccndc_warn(__LINE__, "command error, missing hostname\n");
        goto ExitOnError;
    }
        
    if (cmd_port == NULL || cmd_port[0] == 0)
        cmd_port = CCN_DEFAULT_UNICAST_PORT;

    hints.ai_socktype = socktype;
    res = getaddrinfo (cmd_host, cmd_port, &hints, &raddrinfo);
    if (res != 0 || raddrinfo == NULL) {
        ccndc_warn(__LINE__, "command error, getaddrinfo for host [%s] port [%s]: %s\n", cmd_host, cmd_port, gai_strerror (res));
        goto ExitOnError;
    }
    res = getnameinfo(raddrinfo->ai_addr, raddrinfo->ai_addrlen,
                      rhostnamebuf, sizeof(rhostnamebuf),
                      rhostportbuf, sizeof(rhostportbuf),
                      NI_NUMERICHOST | NI_NUMERICSERV);
    freeaddrinfo(raddrinfo);
    if (res != 0) {
        ccndc_warn(__LINE__, "command error, getnameinfo: %s\n", gai_strerror(res));
        goto ExitOnError;
    }

    off_address = entry->store->length;
    res = ccn_charbuf_append (entry->store, rhostnamebuf, strlen (rhostnamebuf)+1);
    if (res != 0) {
        ccndc_warn(__LINE__, "Cannot append to charbuf");
        goto ExitOnError;
    }

    off_port = entry->store->length;
    res = ccn_charbuf_append (entry->store, rhostportbuf, strlen (rhostportbuf)+1);
    if (res != 0) {
        ccndc_warn(__LINE__, "Cannot append to charbuf");
        goto ExitOnError;
    }
        
    entry->descr.mcast_ttl = -1;
    if (cmd_mcastttl != NULL) {
        char *endptr;
        entry->descr.mcast_ttl = strtol (cmd_mcastttl, &endptr, 10); 
        if ((endptr != &cmd_mcastttl[strlen (cmd_mcastttl)]) ||
            entry->descr.mcast_ttl < 0 || entry->descr.mcast_ttl > 255) {
            ccndc_warn(__LINE__, "command error, invalid multicast ttl: %s\n", cmd_mcastttl);
            goto ExitOnError;
        }
    }
        
    if (cmd_mcastif != NULL) {
        res = getaddrinfo(cmd_mcastif, NULL, &mcasthints, &mcastifaddrinfo);
        if (res != 0) {
            ccndc_warn(__LINE__, "command error, incorrect multicat interface [%s]: "
                       "mcastifaddr getaddrinfo: %s\n", cmd_mcastif, gai_strerror(res));
            goto ExitOnError;
        }

        res = getnameinfo(mcastifaddrinfo->ai_addr, mcastifaddrinfo->ai_addrlen,
                          rhostnamebuf, sizeof(rhostnamebuf),
                          NULL, 0,
                          NI_NUMERICHOST | NI_NUMERICSERV);
        freeaddrinfo(mcastifaddrinfo);
        if (res != 0) {
            ccndc_warn(__LINE__, "command error, getnameinfo: %s\n", gai_strerror(res));
            goto ExitOnError;
        }

        off_source_address = entry->store->length;
        res = ccn_charbuf_append (entry->store, rhostnamebuf, strlen (rhostnamebuf)+1);
        if (res != 0) {
            ccndc_warn(__LINE__, "Cannot append to charbuf");
            goto ExitOnError;
        }
    }

    entry->descr.address = (const char*)(entry->store->buf + off_address);
    entry->descr.port    = (const char*)(entry->store->buf + off_port);
    if (off_source_address >= 0) {
        entry->descr.source_address = (const char*)(entry->store->buf + off_source_address);
    }

    entry->lifetime = freshness;
    
    return entry;
    
 ExitOnError:
    ccn_face_instance_destroy (&entry);
    return NULL;
}

                    
#define GET_NEXT_TOKEN(token_var)                       \
    do {                                                \
        token_var = strsep (&cmd, " \t");               \
    } while (token_var != NULL && token_var[0] == 0);

/*
 *   uri (udp|tcp) host [port [flags [mcastttl [mcastif]]]])
 */
int
ccndc_add (struct ccndc_data *self,
           int check_only,
           const char *cmd_orig)
{
    int ret_code = 0;
    char *cmd, *cmd_fixed = NULL;
    char *cmd_uri = NULL,
        *cmd_proto = NULL,
        *cmd_host = NULL,
        *cmd_port = NULL,
        *cmd_flags = NULL,
        *cmd_mcastttl = NULL,
        *cmd_mcastif = NULL;
    // struct ccndc_prefix_entry *entry;
    
    if (cmd_orig == NULL) {
        ccndc_warn (__LINE__, "command error\n");
        return -1;
    }
    
    cmd = calloc (strlen (cmd_orig)+1, sizeof(char));
    cmd_fixed = cmd;
    if (cmd == NULL) {
        ccndc_warn (__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
        
    strcpy (cmd, cmd_orig);

    GET_NEXT_TOKEN (cmd_uri);
    GET_NEXT_TOKEN (cmd_proto);
    GET_NEXT_TOKEN (cmd_host);
    GET_NEXT_TOKEN (cmd_port);
    GET_NEXT_TOKEN (cmd_flags);
    GET_NEXT_TOKEN (cmd_mcastttl);
    GET_NEXT_TOKEN (cmd_mcastif);
   
    // perform sanity checking

    struct ccn_face_instance *face =
        parse_ccn_face_instance (self,
                                 cmd_proto,
                                 cmd_host,     cmd_port,
                                 cmd_mcastttl, cmd_mcastif,
                                 (~0U) >> 1);

    struct ccn_forwarding_entry *prefix =
        parse_ccn_forwarding_entry (self,
                                    cmd_uri,
                                    cmd_flags,
                                    (~0U) >> 1);
    
    if (face == NULL || prefix == NULL) {
        ret_code = -1;
    }

    if (ret_code == 0 && check_only == 0) {
        // do something
        struct ccn_face_instance *newface =
            do_face_action (self, "newface", face);

        if (newface == NULL)
            {
                ccndc_warn (__LINE__, "Cannot create/lookup face");
                goto Cleanup;
            }

        prefix->faceid = newface->faceid;
        ccn_face_instance_destroy (&newface);

        ret_code = do_prefix_action (self, "prefixreg", prefix);
        if (ret_code < 0) {
            ccndc_warn (__LINE__, "Cannot register prefix [%s]\n", cmd_uri);
        }
    }

 Cleanup:
    ccn_face_instance_destroy (&face);
    ccn_forwarding_entry_destroy (&prefix);

    free (cmd_fixed);

    return ret_code;
}


int
ccndc_del (struct ccndc_data *self,
           int check_only,
           const char *cmd_orig)
{
    int ret_code = 0;
    char *cmd, *cmd_fixed = NULL;
    char *cmd_uri = NULL,
        *cmd_proto = NULL,
        *cmd_host = NULL,
        *cmd_port = NULL,
        *cmd_flags = NULL,
        *cmd_mcastttl = NULL,
        *cmd_mcastif = NULL,
        *cmd_destroyface = NULL;
    
    // struct ccndc_prefix_entry *entry;
    
    if (cmd_orig == NULL) {
        ccndc_warn (__LINE__, "command error\n");
        return -1;
    }
    
    cmd = calloc (strlen (cmd_orig)+1, sizeof(char));
    cmd_fixed = cmd;
    if (cmd == NULL) {
        ccndc_warn (__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
        
    strcpy (cmd, cmd_orig);

    GET_NEXT_TOKEN (cmd_uri);
    GET_NEXT_TOKEN (cmd_proto);
    GET_NEXT_TOKEN (cmd_host);
    GET_NEXT_TOKEN (cmd_port);
    GET_NEXT_TOKEN (cmd_flags);
    GET_NEXT_TOKEN (cmd_mcastttl);
    GET_NEXT_TOKEN (cmd_mcastif);
    GET_NEXT_TOKEN (cmd_destroyface);

    struct ccn_face_instance *face =
        parse_ccn_face_instance (self,
                                 cmd_proto,
                                 cmd_host,     cmd_port,
                                 cmd_mcastttl, cmd_mcastif,
                                 (~0U) >> 1);

    struct ccn_forwarding_entry *prefix =
        parse_ccn_forwarding_entry (self,
                                    cmd_uri,
                                    cmd_flags,
                                    (~0U) >> 1);

    if (face == NULL || prefix == NULL) {
        ret_code = -1;
    }
    
    if (ret_code == 0) { // do one more check
        if (cmd_destroyface != NULL &&
            strcasecmp (cmd_destroyface, "destroyface") != 0) {
            ccndc_warn(__LINE__, "command format error\n");
            ret_code = -1;
        }
    }
    
    if (ret_code == 0 && check_only == 0) {
        // do something
        struct ccn_face_instance *newface =
            do_face_action (self, "newface", face);

        if (newface == NULL)
            {
                ccndc_warn (__LINE__, "Cannot create/lookup face\n");
                goto Cleanup;
            }

        if (cmd_destroyface != NULL) {
            face->faceid = newface->faceid;
            ccn_face_instance_destroy (&newface);
            
            newface = do_face_action (self, "destroyface", face);
            if (newface == NULL) {
                ccndc_warn (__LINE__, "Cannot destroy face\n");
            } else {
                ccn_face_instance_destroy (&newface);
            }
        } else {
            prefix->faceid = newface->faceid;
            ccn_face_instance_destroy (&newface);
            
            ret_code = do_prefix_action (self, "unreg", prefix);
            if (ret_code < 0) {
                ccndc_warn (__LINE__, "Cannot unregister prefix [%s]\n", cmd_uri);
            }
        }
    }

 Cleanup:
    ccn_face_instance_destroy (&face);
    ccn_forwarding_entry_destroy (&prefix);
    free (cmd_fixed);

    return ret_code;
}


int
ccndc_destroyface (struct ccndc_data *self,
                   int check_only,
                   const char *cmd_orig)
{
    int ret_code = 0;
    if (cmd_orig == NULL) {
        ccndc_warn (__LINE__, "command error\n");
        return -1;
    }
    
    char *cmd = calloc (strlen (cmd_orig)+1, sizeof(char));
    char *cmd_fixed = cmd;
    if (cmd == NULL) {
        ccndc_warn (__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
        
    strcpy (cmd, cmd_orig);

    char *cmd_faceid;
    GET_NEXT_TOKEN (cmd_faceid);

    /* destroy a face - the URI field will hold the face number */
    if (cmd_faceid == NULL) {
        ccndc_warn(__LINE__, "command error, missing face number for destroyface\n");
        ret_code = -1;
        goto Cleanup;
    }

    char *endptr;
    int facenumber = strtol (cmd_faceid, &endptr, 10);
    if ((endptr != &cmd_faceid[strlen (cmd_faceid)]) ||
        facenumber < 0) {
        ccndc_warn(__LINE__, "command error invalid face number for destroyface: %d\n", facenumber);
        ret_code = -1;
        goto Cleanup;
    }

    struct ccn_face_instance *face = calloc (1, sizeof (struct ccn_face_instance));
    if (face == NULL) {
        ccndc_warn(__LINE__, "Cannot allocate memory\n");
        ret_code = -1;
        goto Cleanup;
    }

    // copy static info
    face->ccnd_id = (const unsigned char *)self->ccnd_id;
    face->ccnd_id_size = self->ccnd_id_size;
    
    if (check_only == 0) {
        face->faceid = facenumber;
        
        struct ccn_face_instance *newface = do_face_action (self, "destroyface", face);
        if (newface == NULL) {
            ccndc_warn(__LINE__, "Cannot destroy face %d or the face does not exist\n", facenumber);        
        } else {
            ccn_face_instance_destroy (&newface);
        }
    }
    
    ccn_face_instance_destroy (&face);
    
 Cleanup:
    free (cmd_fixed);

    return ret_code;
}

///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
// "private section
///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

/**
 * @brief Get ID of the local CCND
 */
static int
ccndc_get_ccnd_id (struct ccndc_data *self)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    char ccndid_uri[] = "ccnx:/%C1.M.S.localhost/%C1.M.SRV/ccnd/KEY";
    const unsigned char *ccndid_result;
    int res = 0;
    
    name = ccn_charbuf_create ();
    if (name == NULL) {
        ccndc_warn (__LINE__, "Unable to allocate storage for service locator name charbuf\n");
        return -1;
    }
    
    resultbuf = ccn_charbuf_create ();
    if (resultbuf == NULL) {
        ccndc_warn (__LINE__, "Unable to allocate storage for result charbuf");
        res = -1;
        goto Cleanup2;
    }

    res = ccn_name_from_uri (name, ccndid_uri);
    if (res < 0) {
        ccndc_warn (__LINE__, "Unable to parse service locator URI for ccnd key");
        goto Cleanup;
    }

    res = ccn_get (self->ccn_handle,
                   name,
                   self->local_scope_template,
                   4500, resultbuf, &pcobuf, NULL, 0);
    if (res < 0) {
        ccndc_warn (__LINE__, "Unable to get key from ccnd");
        goto Cleanup;
    }
    
    res = ccn_ref_tagged_BLOB (CCN_DTAG_PublisherPublicKeyDigest,
                               resultbuf->buf,
                               pcobuf.offset[CCN_PCO_B_PublisherPublicKeyDigest],
                               pcobuf.offset[CCN_PCO_E_PublisherPublicKeyDigest],
                               &ccndid_result, &self->ccnd_id_size);
    if (res < 0) {
        ccndc_warn (__LINE__, "Unable to parse ccnd response for ccnd id");
        goto Cleanup;
    }
    
    if (self->ccnd_id_size > sizeof (self->ccnd_id))
        {
            ccndc_warn (__LINE__, "Incorrect size for ccnd id in response");
            goto Cleanup;
        }
    
    memcpy (self->ccnd_id, ccndid_result, self->ccnd_id_size);
    
 Cleanup:
    ccn_charbuf_destroy (&name);
 Cleanup2:
    ccn_charbuf_destroy (&resultbuf);
    return res;
}


static struct ccn_face_instance *
do_face_action(struct ccndc_data *self,
               const char *action,
               struct ccn_face_instance *face_instance)
{
    struct ccn_charbuf *newface = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    struct ccn_face_instance *new_face_instance = NULL;
    const unsigned char *ptr = NULL;
    size_t length = 0;
    int res = 0;

    face_instance->action = action;

    /* Encode the given face instance */
    newface = ccn_charbuf_create();
    ON_NULL_CLEANUP(newface);
    ON_ERROR_CLEANUP(ccnb_append_face_instance(newface, face_instance));

    temp = ccn_charbuf_create();
    ON_NULL_CLEANUP(temp);
    res = ccn_sign_content(self->ccn_handle, temp, self->no_name, NULL, newface->buf, newface->length);
    ON_ERROR_CLEANUP(res);
    resultbuf = ccn_charbuf_create();
    ON_NULL_CLEANUP(resultbuf);
    
    /* Construct the Interest name that will create the face */
    name = ccn_charbuf_create();
    ON_NULL_CLEANUP(name);
    ON_ERROR_CLEANUP(ccn_name_init(name));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, "ccnx"));
    ON_ERROR_CLEANUP(ccn_name_append(name, face_instance->ccnd_id, face_instance->ccnd_id_size));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, face_instance->action));
    ON_ERROR_CLEANUP(ccn_name_append(name, temp->buf, temp->length));
    
    res = ccn_get(self->ccn_handle, name, self->local_scope_template, 1000, resultbuf, &pcobuf, NULL, 0);
    ON_ERROR_CLEANUP(res);
    
    ON_ERROR_CLEANUP(ccn_content_get_value(resultbuf->buf, resultbuf->length, &pcobuf, &ptr, &length));
    new_face_instance = ccn_face_instance_parse(ptr, length);
    ON_NULL_CLEANUP(new_face_instance);
    ccn_charbuf_destroy(&newface);
    ccn_charbuf_destroy(&signed_info);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&name);
    return (new_face_instance);
    
Cleanup:
    ccn_charbuf_destroy(&newface);
    ccn_charbuf_destroy(&signed_info);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&name);
    ccn_face_instance_destroy(&new_face_instance);
    return (NULL);
}

// /**
//  *  @brief Register an interest prefix as being routed to a given face
//  *  @result returns (positive) faceid on success, -1 on error
//  */
static int
do_prefix_action(struct ccndc_data *self,
                 const char *action,
                 struct ccn_forwarding_entry *forwarding_entry)
{
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *prefixreg = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    struct ccn_forwarding_entry *new_forwarding_entry = NULL;

    const unsigned char *ptr = NULL;
    size_t length = 0;
    int res;

    forwarding_entry->action = action;
        
    prefixreg = ccn_charbuf_create();
    ON_NULL_CLEANUP(prefixreg);
    ON_ERROR_CLEANUP(ccnb_append_forwarding_entry(prefixreg, forwarding_entry));
    temp = ccn_charbuf_create();
    ON_NULL_CLEANUP(temp);
    res = ccn_sign_content(self->ccn_handle, temp, self->no_name, NULL, prefixreg->buf, prefixreg->length);
    ON_ERROR_CLEANUP(res);    
    resultbuf = ccn_charbuf_create();
    ON_NULL_CLEANUP(resultbuf);
    name = ccn_charbuf_create();
    ON_ERROR_CLEANUP(ccn_name_init(name));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, "ccnx"));
    ON_ERROR_CLEANUP(ccn_name_append(name, forwarding_entry->ccnd_id, forwarding_entry->ccnd_id_size));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, forwarding_entry->action));
    ON_ERROR_CLEANUP(ccn_name_append(name, temp->buf, temp->length));
    res = ccn_get(self->ccn_handle, name, self->local_scope_template, 1000, resultbuf, &pcobuf, NULL, 0);
    ON_ERROR_CLEANUP(res);
    ON_ERROR_CLEANUP(ccn_content_get_value(resultbuf->buf, resultbuf->length, &pcobuf, &ptr, &length));
    new_forwarding_entry = ccn_forwarding_entry_parse(ptr, length);
    ON_NULL_CLEANUP(new_forwarding_entry);
    
    res = new_forwarding_entry->faceid;

    ccn_forwarding_entry_destroy(&new_forwarding_entry);
    ccn_charbuf_destroy(&signed_info);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&prefixreg);
    
    return (res);
    
    /* This is where ON_ERROR_CLEANUP sends us in case of an error
     * and we must free any storage we allocated before returning.
     */
Cleanup:
    ccn_charbuf_destroy(&signed_info);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&prefixreg);
    
    return (-1);
}


