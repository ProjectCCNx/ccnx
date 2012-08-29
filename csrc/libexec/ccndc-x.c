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

#include "ccndc-log.h"

#include <stdlib.h>
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
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>
#include <ccn/face_mgmt.h>
#include <ccn/reg_mgmt.h>
#include <ccn/signing.h>

#define CMD_ADD 0
#define CMD_DEL 1
#define CMD_DELWITHFACE 2
#define CMD_DESTROYFACE 3

/// interest template for ccnget calls, specifying scope 1 (Local)
static struct ccn_charbuf *local_scope_template = NULL;

/// empty name necessary for signing purposes
static struct ccn_charbuf *no_name = NULL;

void
ccndc_initialize (void) {
    const char *msg = "Unable to initialize global data.";
    /* Set up an Interest template to indicate scope 1 (Local) */
    local_scope_template = ccn_charbuf_create();
    if (local_scope_template == NULL) {
        ON_ERROR_EXIT(-1, msg);
    }
    
    ON_ERROR_EXIT(ccn_charbuf_append_tt(local_scope_template, CCN_DTAG_Interest, CCN_DTAG), msg);
    ON_ERROR_EXIT(ccn_charbuf_append_tt(local_scope_template, CCN_DTAG_Name, CCN_DTAG), msg);
    ON_ERROR_EXIT(ccn_charbuf_append_closer(local_scope_template), msg);	/* </Name> */
    ON_ERROR_EXIT(ccnb_tagged_putf(local_scope_template, CCN_DTAG_Scope, "1"), msg);
    ON_ERROR_EXIT(ccn_charbuf_append_closer(local_scope_template), msg);	/* </Interest> */
    
    /* Create a null name */
    no_name = ccn_charbuf_create();
    if (no_name == NULL) {
        ON_ERROR_EXIT(-1, msg);
    }
    ON_ERROR_EXIT(ccn_name_init(no_name), msg);
}

void
ccndc_destroy (void) {
    ccn_charbuf_destroy(no_name);
    ccn_charbuf_destroy(local_scope_template);
}

static struct prefix_face_list_item *prefix_face_list_item_create(int cmdCode,
                                                                  struct ccn_charbuf *prefix,
                                                                  int ipproto,
                                                                  int mcast_ttl,
                                                                  char *host,
                                                                  char *port,
                                                                  char *mcastif,
                                                                  int lifetime,
                                                                  int flags,
                                                                  int create,
                                                                  unsigned faceid)
{
    struct prefix_face_list_item *pfl = calloc(1, sizeof(struct prefix_face_list_item));
    struct ccn_face_instance *fi = calloc(1, sizeof(*fi));
    struct ccn_charbuf *store = ccn_charbuf_create();
    int host_off = -1;
    int port_off = -1;
    int mcast_off = -1;

    pfl->cmd = cmdCode;
    if (pfl == NULL || fi == NULL || store == NULL) {
        if (pfl) free(pfl);
        if (fi) ccn_face_instance_destroy(&fi);
        if (store) ccn_charbuf_destroy(&store);
        return (NULL);
    }
    pfl->fi = fi;
    pfl->fi->store = store;
    
    pfl->prefix = prefix;
    pfl->fi->descr.ipproto = ipproto;
    pfl->fi->descr.mcast_ttl = mcast_ttl;
    pfl->fi->lifetime = lifetime;
    if (faceid > 0)
        pfl->fi->faceid = faceid;
    pfl->flags = flags;
    
    if (cmdCode == CMD_DESTROYFACE) {
        ccn_charbuf_append(store, "destroyface", strlen("destroyface") + 1);
    }
    else {
        ccn_charbuf_append(store, "newface", strlen("newface") + 1);
    }
    
    host_off = store->length;
    ccn_charbuf_append(store, host, strlen(host) + 1);
    port_off = store->length;
    ccn_charbuf_append(store, port, strlen(port) + 1);
    if (mcastif != NULL) {
        mcast_off = store->length;
        ccn_charbuf_append(store, mcastif, strlen(mcastif) + 1);
    }
    // appending to a charbuf may move it, so we must wait until we have
    // finished appending before calculating the pointers into the store.
    char *b = (char *)store->buf;
    pfl->fi->action = b;
    pfl->fi->descr.address = b + host_off;
    pfl->fi->descr.port = b + port_off;
    pfl->fi->descr.source_address = (mcast_off == -1) ? NULL : b + mcast_off;
    
    return (pfl);
}

static void prefix_face_list_destroy(struct prefix_face_list_item **pflpp)
{
    struct prefix_face_list_item *pflp = *pflpp;
    struct prefix_face_list_item *next;
    
    if (pflp == NULL) return;
    while (pflp) {
        ccn_face_instance_destroy(&pflp->fi);
        ccn_charbuf_destroy(&pflp->prefix);
        next = pflp->next;
        free(pflp);
        pflp = next;
    }
    *pflpp = NULL;
}

/**
 *  @brief Create or delete a face based on the face attributes
 *  @param h  the ccnd handle
 *  @param face_instance  the parameters of the face to be created
 *  @param flags
 *  @result returns new face_instance representing the face created/deleted
 */
static struct ccn_face_instance *
do_face_action(struct ccn *h, struct ccn_face_instance *face_instance)
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
    
    /* Encode the given face instance */
    newface = ccn_charbuf_create();
    ON_NULL_CLEANUP(newface);
    ON_ERROR_CLEANUP(ccnb_append_face_instance(newface, face_instance));

    temp = ccn_charbuf_create();
    ON_NULL_CLEANUP(temp);
    res = ccn_sign_content(h, temp, no_name, NULL, newface->buf, newface->length);
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
    res = ccn_get(h, name, local_scope_template, 1000, resultbuf, &pcobuf, NULL, 0);
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
/**
 *  @brief Register an interest prefix as being routed to a given face
 *  @param h  the ccnd handle
 *  @param name_prefix  the prefix to be registered
 *  @param face_instance  the face to which the interests with the prefix should be routed
 *  @param flags
 *  @result returns (positive) faceid on success, -1 on error
 */
static int
register_unregister_prefix(struct ccn *h,
                           int operation,
                           struct ccn_charbuf *name_prefix,
                           struct ccn_face_instance *face_instance,
                           int flags)
{
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *prefixreg = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    struct ccn_forwarding_entry forwarding_entry_storage = {0};
    struct ccn_forwarding_entry *forwarding_entry = &forwarding_entry_storage;
    struct ccn_forwarding_entry *new_forwarding_entry;
    const unsigned char *ptr = NULL;
    size_t length = 0;
    int res;
    
    /* Register or unregister the prefix */
    forwarding_entry->action = (operation == OP_REG) ? "prefixreg" : "unreg";
    forwarding_entry->name_prefix = name_prefix;
    forwarding_entry->ccnd_id = face_instance->ccnd_id;
    forwarding_entry->ccnd_id_size = face_instance->ccnd_id_size;
    forwarding_entry->faceid = face_instance->faceid;
    forwarding_entry->flags = flags;
    forwarding_entry->lifetime = (~0U) >> 1;
    
    prefixreg = ccn_charbuf_create();
    ON_NULL_CLEANUP(prefixreg);
    ON_ERROR_CLEANUP(ccnb_append_forwarding_entry(prefixreg, forwarding_entry));
    temp = ccn_charbuf_create();
    ON_NULL_CLEANUP(temp);
    res = ccn_sign_content(h, temp, no_name, NULL, prefixreg->buf, prefixreg->length);
    ON_ERROR_CLEANUP(res);    
    resultbuf = ccn_charbuf_create();
    ON_NULL_CLEANUP(resultbuf);
    name = ccn_charbuf_create();
    ON_ERROR_CLEANUP(ccn_name_init(name));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, "ccnx"));
    ON_ERROR_CLEANUP(ccn_name_append(name, face_instance->ccnd_id, face_instance->ccnd_id_size));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, (operation == OP_REG) ? "prefixreg" : "unreg"));
    ON_ERROR_CLEANUP(ccn_name_append(name, temp->buf, temp->length));
    res = ccn_get(h, name, local_scope_template, 1000, resultbuf, &pcobuf, NULL, 0);
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

static int
process_command_tokens(struct prefix_face_list_item *pfltail,
                       int lineno,
                       char *cmd,
                       char *uri,
                       char *proto,
                       char *host,
                       char *port,
                       char *flags,
                       char *mcastttl,
                       char *mcastif)
{
    int lifetime = 0;
    struct ccn_charbuf *prefix = NULL;
    int ipproto = 0;
    int socktype = 0;
    int iflags = 0;
    int imcastttl = 0;
    int createface = 0;
    int facenumber = 0;
    char rhostnamebuf[NI_MAXHOST];
    char rhostportbuf[NI_MAXSERV];
    struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG)};
    struct addrinfo mcasthints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG | AI_NUMERICHOST)};
    struct addrinfo *raddrinfo = NULL;
    struct addrinfo *mcastifaddrinfo = NULL;
    struct prefix_face_list_item *pflp = NULL;
    int res;
    int cmdCode = CMD_ADD;
    
    if (cmd == NULL) {
        ccndc_warn(__LINE__, "command error (line %d), missing command\n", lineno);
        return (-1);
    }
    createface = 1;
    if (strcasecmp(cmd, "add") == 0) {
        lifetime = (~0U) >> 1;
        cmdCode = CMD_ADD;
    }
    else if (strcasecmp(cmd, "del") == 0) {
        lifetime = 0;
        cmdCode = CMD_DEL;
    }
    else if (strcasecmp(cmd, "delwithface") == 0) {
        lifetime = 0;
        createface = 0;
        cmdCode = CMD_DELWITHFACE;
    }
    else if (strcasecmp(cmd, "destroyface") == 0) {
        createface = 0;
        cmdCode = CMD_DESTROYFACE;
    }
    else {
        ccndc_warn(__LINE__, "command error (line %d), unrecognized command '%s'\n", lineno, cmd);
        return (-1);
    }
    if (strcasecmp(cmd, "destroyface") != 0) {
        /* we will be creating the face to either add/delete a prefix on it */
        if (uri == NULL) {
            ccndc_warn(__LINE__, "command error (line %d), missing CCNx URI\n", lineno);
            return (-1);
        }   
        prefix = ccn_charbuf_create();
        res = ccn_name_from_uri(prefix, uri);
        if (res < 0) {
            ccndc_warn(__LINE__, "command error (line %d), bad CCNx URI '%s'\n", lineno, uri);
            return (-1);
        }
        
        if (proto == NULL) {
            ccndc_warn(__LINE__, "command error (line %d), missing address type\n", lineno);
            return (-1);
        }
        if (strcasecmp(proto, "udp") == 0) {
            ipproto = IPPROTO_UDP;
            socktype = SOCK_DGRAM;
        }
        else if (strcasecmp(proto, "tcp") == 0) {
            ipproto = IPPROTO_TCP;
            socktype = SOCK_STREAM;
        }
        else {
            ccndc_warn(__LINE__, "command error (line %d), unrecognized address type '%s'\n", lineno, proto);
            return (-1);
        }
        
        if (host == NULL) {
            ccndc_warn(__LINE__, "command error (line %d), missing hostname\n", lineno);
            return (-1);
        }
        
        if (port == NULL || port[0] == 0)
            port = CCN_DEFAULT_UNICAST_PORT;
        
        hints.ai_socktype = socktype;
        res = getaddrinfo(host, port, &hints, &raddrinfo);
        if (res != 0 || raddrinfo == NULL) {
            ccndc_warn(__LINE__, "command error (line %d), getaddrinfo: %s\n", lineno, gai_strerror(res));
            return (-1);
        }
        res = getnameinfo(raddrinfo->ai_addr, raddrinfo->ai_addrlen,
                          rhostnamebuf, sizeof(rhostnamebuf),
                          rhostportbuf, sizeof(rhostportbuf),
                          NI_NUMERICHOST | NI_NUMERICSERV);
        freeaddrinfo(raddrinfo);
        if (res != 0) {
            ccndc_warn(__LINE__, "command error (line %d), getnameinfo: %s\n", lineno, gai_strerror(res));
            return (-1);
        }
        
        iflags = -1;
        if (flags != NULL && flags[0] != 0) {
            iflags = atoi(flags);
            if ((iflags & ~CCN_FORW_PUBMASK) != 0) {
                ccndc_warn(__LINE__, "command error (line %d), invalid flags 0x%x\n", lineno, iflags);
                return (-1);
            }
        }
        
        imcastttl = -1;
        if (mcastttl != NULL) {
            imcastttl = atoi(mcastttl);
            if (imcastttl < 0 || imcastttl > 255) {
                ccndc_warn(__LINE__, "command error (line %d), invalid multicast ttl: %s\n", lineno, mcastttl);
                return (-1);
            }
        }
        
        if (mcastif != NULL) {
            res = getaddrinfo(mcastif, NULL, &mcasthints, &mcastifaddrinfo);
            if (res != 0) {
                ccndc_warn(__LINE__, "command error (line %d), mcastifaddr getaddrinfo: %s\n", lineno, gai_strerror(res));
                return (-1);
            }
        }
    } else {
        /* destroy a face - the URI field will hold the face number */
        if (uri == NULL) {
            ccndc_warn(__LINE__, "command error (line %d), missing face number for destroyface\n", lineno);
            return (-1);
        }
        facenumber = atoi(uri);
        if (facenumber < 0) {
            ccndc_warn(__LINE__, "command error (line %d), invalid face number for destroyface: %d\n", lineno, facenumber);
            return (-1);
        }
        
    }
    /* we have successfully parsed a command line */
    pflp = prefix_face_list_item_create(cmdCode,
                                        prefix, ipproto, imcastttl, rhostnamebuf,
                                        rhostportbuf, mcastif, lifetime, iflags,
                                        createface, facenumber);
    if (pflp == NULL) {
        ccndc_fatal(__LINE__, "Unable to allocate prefix_face_list_item\n");
    }
    pfltail->next = pflp;
    return (0);
}

static int
read_configfile(const char *filename, struct prefix_face_list_item *pfltail)
{
    int configerrors = 0;
    int lineno = 0;
    char *cmd;
    char *uri;
    char *proto;
    char *host;
    char *port;
    char *flags;
    char *mcastttl;
    char *mcastif;
    FILE *cfg;
    char buf[1024];
    const char *seps = " \t\n";
    char *cp = NULL;
    char *last = NULL;
    int res;
    
    cfg = fopen(filename, "r");
    if (cfg == NULL)
        ccndc_fatal(__LINE__, "%s (%s)\n", strerror(errno), filename);
    
    while (fgets((char *)buf, sizeof(buf), cfg)) {
        int len;
        lineno++;
        len = strlen(buf);
        if (buf[0] == '#' || len == 0)
            continue;
        if (buf[len - 1] == '\n')
            buf[len - 1] = '\0';
        cp = index(buf, '#');
        if (cp != NULL)
            *cp = '\0';
        
        cmd = strtok_r(buf, seps, &last);
        if (cmd == NULL)	/* blank line */
            continue;
        uri = strtok_r(NULL, seps, &last);
        proto = strtok_r(NULL, seps, &last);
        host = strtok_r(NULL, seps, &last);
        port = strtok_r(NULL, seps, &last);
        flags = strtok_r(NULL, seps, &last);
        mcastttl = strtok_r(NULL, seps, &last);
        mcastif = strtok_r(NULL, seps, &last);
        res = process_command_tokens(pfltail, lineno, cmd, uri, proto, host, port, flags, mcastttl, mcastif);
        if (res < 0) {
            configerrors--;
        } else {
            pfltail = pfltail->next;
        }
    }
    fclose(cfg);
    return (configerrors);
}


void
process_prefix_face_list_item(struct ccn *h,
                              struct prefix_face_list_item *pfl) 
{
    struct ccn_face_instance *nfi;
    struct ccn_charbuf *temp;
    struct prefix_face_list_item *pflp;
    int op;
    int res;
    
    op = (pfl->fi->lifetime > 0) ? OP_REG : OP_UNREG;
    pfl->fi->ccnd_id = ccndid;
    pfl->fi->ccnd_id_size = ccndid_size;

    nfi = do_face_action(h, pfl->fi);
    if (pfl->cmd == CMD_DESTROYFACE) {
        ccndc_warn (__LINE__, "Destroying face %d\n", pfl->fi->faceid);
        if (nfi == NULL) {
            ccndc_warn(__LINE__, "Unable to destroy face %d\n",
                       pfl->fi->faceid);
            return;
        }
    } else if (pfl->cmd == CMD_DELWITHFACE) {
        ccndc_warn (__LINE__, "Deleting face for route\n");
        if (nfi == NULL) {
            temp = ccn_charbuf_create();
            ccn_uri_append(temp, pfl->prefix->buf, pfl->prefix->length, 1);
            ccndc_warn(__LINE__, "Unable to destroy face for FIB entry %s %s %s %s %s\n",
                       (pfl->fi->descr.ipproto == IPPROTO_UDP) ? "udp" : "tcp",
                       pfl->fi->descr.address,
                       pfl->fi->descr.port);
            ccn_charbuf_destroy(&temp);
            return;
        }
        ccndc_warn(__LINE__, "not implemented yet\n");

        pflp = prefix_face_list_item_create(CMD_DESTROYFACE,
                                            NULL, pfl->fi->descr.ipproto, pfl->fi->descr.mcast_ttl, rhostnamebuf,
                                            rhostportbuf, mcastif, lifetime, iflags,
                                            createface, facenumber);
    } else {
        if (nfi == NULL) {
            temp = ccn_charbuf_create();
            ccn_uri_append(temp, pfl->prefix->buf, pfl->prefix->length, 1);
            ccndc_warn(__LINE__, "Unable to create face for %s %s %s %s %s\n",
                       (op == OP_REG) ? "add" : "del", ccn_charbuf_as_string(temp),
                       (pfl->fi->descr.ipproto == IPPROTO_UDP) ? "udp" : "tcp",
                       pfl->fi->descr.address,
                       pfl->fi->descr.port);
            ccn_charbuf_destroy(&temp);
            return;
        }
        res = register_unregister_prefix(h, op, pfl->prefix, nfi, pfl->flags);
        if (res < 0) {
            temp = ccn_charbuf_create();
            ccn_uri_append(temp, pfl->prefix->buf, pfl->prefix->length, 1);
            ccndc_warn(__LINE__, "Unable to %sregister prefix on face %d for %s %s %s %s %s\n",
                       (op == OP_UNREG) ? "un" : "", nfi->faceid,
                       (op == OP_REG) ? "add" : "del",
                       ccn_charbuf_as_string(temp),
                       (pfl->fi->descr.ipproto == IPPROTO_UDP) ? "udp" : "tcp",
                       pfl->fi->descr.address,
                       pfl->fi->descr.port);
            ccn_charbuf_destroy(&temp);
        }
    }
    ccn_face_instance_destroy(&nfi);
    return;
}

