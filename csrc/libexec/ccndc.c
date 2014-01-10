/**
 * @file ccndc.c
 * @brief Bring up a link to another ccnd.
 *
 * A CCNx program.
 *
 * Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
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

#include "ccndc.h"
#include "ccndc-log.h"
#include "ccndc-srv.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <limits.h>
#include <netdb.h>
#include <netinet/in.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#if defined(NEED_GETADDRINFO_COMPAT)
#include "getaddrinfo.h"
#include "dummyin6.h"
#endif
#ifndef AI_ADDRCONFIG
#define AI_ADDRCONFIG 0 /*IEEE Std 1003.1-2001/Cor 1-2002, item XSH/TC1/D6/20*/
#endif

#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/uri.h>
#include <ccn/signing.h>
#include <ccn/face_mgmt.h>
#include <ccn/reg_mgmt.h>
#include <ccn/strategy_mgmt.h>

#define ON_ERROR_CLEANUP(resval) {                                      \
if ((resval) < 0) {                                                 \
if (verbose > 0) ccndc_warn(__LINE__, "OnError cleanup\n");    \
goto Cleanup;                                                   \
}                                                                   \
}

#define ON_NULL_CLEANUP(resval) {                                       \
if ((resval) == NULL) {                                             \
if (verbose > 0) ccndc_warn(__LINE__, "OnNull cleanup\n");      \
goto Cleanup;                                                   \
}                                                                   \
}

#define ON_ERROR_EXIT(resval, msg) {                                    \
int _resval = (resval);                                             \
if (_resval < 0)                                                     \
ccndc_fatal(__LINE__, "fatal error, res = %d, %s\n", _resval, msg);  \
}

struct ccndc_data *
ccndc_initialize_data(void) {
    struct ccndc_data *self;
    const char *msg = "Unable to initialize ccndc";
    int res;
    
    self = calloc(1, sizeof(*self));
    if (self == NULL) {
        ON_ERROR_EXIT (-1, msg);
    }
    
    self->ccn_handle = ccn_create();
    ON_ERROR_EXIT(ccn_connect(self->ccn_handle, NULL), "Unable to connect to local ccnd");
    ON_ERROR_EXIT(ccndc_get_ccnd_id(self), "Unable to obtain ID of local ccnd");
    
    /* Set up an Interest template to indicate scope 1 (Local) */
    self->local_scope_template = ccn_charbuf_create();
    res = ccnb_element_begin(self->local_scope_template, CCN_DTAG_Interest);
    res |= ccnb_element_begin(self->local_scope_template, CCN_DTAG_Name);
    res |= ccnb_element_end(self->local_scope_template);	/* </Name> */
    res |= ccnb_tagged_putf(self->local_scope_template, CCN_DTAG_Scope, "1");
    res |= ccnb_element_end(self->local_scope_template);	/* </Interest> */
    ON_ERROR_EXIT(res, msg);
    
    /* Create a null name */
    self->no_name = ccn_charbuf_create();
    ON_ERROR_EXIT(ccn_name_init(self->no_name), msg);
    
    self->lifetime = (~0U) >> 1;
    
    return self;
}

void
ccndc_destroy_data(struct ccndc_data **data) {
    struct ccndc_data *self = *data;
    
    if (self != NULL) {
        ccn_charbuf_destroy(&self->no_name);
        ccn_charbuf_destroy(&self->local_scope_template);
        ccn_destroy(&self->ccn_handle);
        free(self);
        *data = NULL;
    }
}

int
ccndc_dispatch_cmd(struct ccndc_data *ccndc,
                   int check_only,
                   const char *cmd,
                   const char *options,
                   int num_options)
{
    if (strcasecmp(cmd, "add") == 0) {
        if (num_options >= 0 && (num_options < 3 || num_options > 7))
            return INT_MIN;
        return ccndc_add(ccndc, check_only, options);
    }
    if (strcasecmp(cmd, "del") == 0) {
        if (num_options >= 0 && (num_options < 3 || num_options > 7))
            return INT_MIN;
        return ccndc_del(ccndc, check_only, options);
    }
    if (strcasecmp(cmd, "create") == 0) {
        if (num_options >= 0 && (num_options < 2 || num_options > 5))
            return INT_MIN;
        return ccndc_create(ccndc, check_only, options);
    }
    if (strcasecmp(cmd, "destroy") == 0) {
        if (num_options >= 0 && (num_options < 2 || num_options > 5))
            return INT_MIN;
        return ccndc_destroy(ccndc, check_only, options);
    }
    if (strcasecmp(cmd, "destroyface") == 0) {
        if (num_options >= 0 && num_options != 1)
            return INT_MIN;
        return ccndc_destroyface(ccndc, check_only, options);
    }

    if (strcasecmp(cmd, "setstrategy") == 0) {
        if (num_options >= 0 && (num_options < 2 || num_options > 4))
            return INT_MIN;
        return ccndc_strategy(ccndc, check_only, STRAT_SET, options);
    }

    if (strcasecmp(cmd, "getstrategy") == 0) {
        if (num_options >= 0 && num_options != 1)
            return INT_MIN;
        return ccndc_strategy(ccndc, check_only, STRAT_GET, options);
    }

    if (strcasecmp(cmd, "removestrategy") == 0) {
        if (num_options >= 0 && num_options != 1)
            return INT_MIN;
        return ccndc_strategy(ccndc, check_only, STRAT_REMOVE, options);
    }

    if (strcasecmp(cmd, "srv") == 0) {
        // attempt to guess parameters using SRV record of a domain in search list
        if (num_options >= 0 && num_options != 0)
            return INT_MIN;
        if (check_only) return 0;
        return ccndc_srv(ccndc, NULL, 0);
    }
    if (strcasecmp(cmd, "renew") == 0) {
        if (num_options >= 0 && (num_options < 3 || num_options > 7))
            return INT_MIN;
        return ccndc_renew(ccndc, check_only, options);
    }
    return INT_MIN;
}


#define GET_NEXT_TOKEN(_cmd, _token_var) do {       \
_token_var = strsep(&_cmd, " \t");             \
} while (_token_var != NULL && _token_var[0] == 0);

/*
 *   uri (udp|tcp) host [port [flags [mcastttl [mcastif]]]])
 *   uri face faceid
 */
int
ccndc_add(struct ccndc_data *self,
          int check_only,
          const char *cmd_orig)
{
    int ret_code = -1;
    char *cmd, *cmd_token;
    char *cmd_uri = NULL;
    char *cmd_proto = NULL;
    char *cmd_host = NULL;
    char *cmd_port = NULL;
    char *cmd_flags = NULL;
    char *cmd_mcastttl = NULL;
    char *cmd_mcastif = NULL;
    struct ccn_face_instance *face = NULL;
    struct ccn_face_instance *newface = NULL;
    struct ccn_forwarding_entry *prefix = NULL;
    
    if (cmd_orig == NULL) {
        ccndc_warn(__LINE__, "command error\n");
        return -1;
    }
    
    cmd = strdup(cmd_orig);
    if (cmd == NULL) {
        ccndc_warn(__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
    cmd_token = cmd;
    GET_NEXT_TOKEN(cmd_token, cmd_uri);
    GET_NEXT_TOKEN(cmd_token, cmd_proto);
    GET_NEXT_TOKEN(cmd_token, cmd_host);
    GET_NEXT_TOKEN(cmd_token, cmd_port);
    GET_NEXT_TOKEN(cmd_token, cmd_flags);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastttl);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastif);
    
    // perform sanity checking
    face = parse_ccn_face_instance(self, cmd_proto, cmd_host, cmd_port,
                                   cmd_mcastttl, cmd_mcastif, (~0U) >> 1);
    prefix = parse_ccn_forwarding_entry(self, cmd_uri, cmd_flags, self->lifetime);
    if (face == NULL || prefix == NULL)
        goto Cleanup;
    
    if (!check_only) {
        if (0 != strcasecmp(cmd_proto, "face")) {
            newface = ccndc_do_face_action(self, "newface", face);
            if (newface == NULL) {
                ccndc_warn(__LINE__, "Cannot create/lookup face\n");
                goto Cleanup;
            }
            prefix->faceid = newface->faceid;
            ccn_face_instance_destroy(&newface);
        } else {
            prefix->faceid = face->faceid;
        }
        ret_code = ccndc_do_prefix_action(self, "prefixreg", prefix);
        if (ret_code < 0) {
            ccndc_warn(__LINE__, "Cannot register prefix [%s]\n", cmd_uri);
            goto Cleanup;
        }
    }  
    ret_code = 0;
Cleanup:
    ccn_face_instance_destroy(&face);
    ccn_forwarding_entry_destroy(&prefix);
    free(cmd);
    return (ret_code);
}


int
ccndc_del(struct ccndc_data *self,
          int check_only,
          const char *cmd_orig)
{
    int ret_code = -1;
    char *cmd, *cmd_token;
    char *cmd_uri = NULL;
    char *cmd_proto = NULL;
    char *cmd_host = NULL;
    char *cmd_port = NULL;
    char *cmd_flags = NULL;
    char *cmd_mcastttl = NULL;
    char *cmd_mcastif = NULL;
    struct ccn_face_instance *face = NULL;
    struct ccn_face_instance *newface = NULL;
    struct ccn_forwarding_entry *prefix = NULL;
    
    if (cmd_orig == NULL) {
        ccndc_warn(__LINE__, "command error\n");
        return -1;
    }
    
    cmd = strdup(cmd_orig);
    if (cmd == NULL) {
        ccndc_warn(__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
    cmd_token = cmd;
    GET_NEXT_TOKEN(cmd_token, cmd_uri);
    GET_NEXT_TOKEN(cmd_token, cmd_proto);
    GET_NEXT_TOKEN(cmd_token, cmd_host);
    GET_NEXT_TOKEN(cmd_token, cmd_port);
    GET_NEXT_TOKEN(cmd_token, cmd_flags);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastttl);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastif);
    
    face = parse_ccn_face_instance(self, cmd_proto, cmd_host, cmd_port,
                                   cmd_mcastttl, cmd_mcastif, (~0U) >> 1);
    prefix = parse_ccn_forwarding_entry(self, cmd_uri, cmd_flags, (~0U) >> 1);
    if (face == NULL || prefix == NULL)
        goto Cleanup;
    
    if (!check_only) {
        if (0 != strcasecmp(cmd_proto, "face")) {
            newface = ccndc_do_face_action(self, "newface", face);
            if (newface == NULL) {
                ccndc_warn(__LINE__, "Cannot create/lookup face\n");
                goto Cleanup;
            }
            prefix->faceid = newface->faceid;
            ccn_face_instance_destroy(&newface);
        } else {
            prefix->faceid = face->faceid;
        }
        ret_code = ccndc_do_prefix_action(self, "unreg", prefix);
        if (ret_code < 0) {
            ccndc_warn(__LINE__, "Cannot unregister prefix [%s]\n", cmd_uri);
            goto Cleanup;
        }
    }
    ret_code = 0;
Cleanup:
    ccn_face_instance_destroy(&face);
    ccn_forwarding_entry_destroy(&prefix);
    free(cmd);
    return (ret_code);
}

/*
 *   (udp|tcp) host [port [mcastttl [mcastif]]]
 */
int
ccndc_create(struct ccndc_data *self,
             int check_only,
             const char *cmd_orig)
{
    int ret_code = -1;
    char *cmd, *cmd_token;
    char *cmd_proto = NULL;
    char *cmd_host = NULL;
    char *cmd_port = NULL;
    char *cmd_mcastttl = NULL;
    char *cmd_mcastif = NULL;
    struct ccn_face_instance *face = NULL;
    struct ccn_face_instance *newface = NULL;
    
    if (cmd_orig == NULL) {
        ccndc_warn(__LINE__, "command error\n");
        return -1;
    }
    
    cmd = strdup(cmd_orig);
    if (cmd == NULL) {
        ccndc_warn(__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
    cmd_token = cmd;
    GET_NEXT_TOKEN(cmd_token, cmd_proto);
    GET_NEXT_TOKEN(cmd_token, cmd_host);
    GET_NEXT_TOKEN(cmd_token, cmd_port);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastttl);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastif);
    
    // perform sanity checking
    face = parse_ccn_face_instance(self, cmd_proto, cmd_host, cmd_port,
                                   cmd_mcastttl, cmd_mcastif, self->lifetime);
    if (face == NULL)
        goto Cleanup;
    
    if (!check_only) {
        newface = ccndc_do_face_action(self, "newface", face);
        if (newface == NULL) {
            ccndc_warn(__LINE__, "Cannot create/lookup face\n");
            goto Cleanup;
        }
        ccn_face_instance_destroy(&newface);
    }
    ret_code = 0;
Cleanup:
    ccn_face_instance_destroy(&face);
    free(cmd);
    return (ret_code);
}    

/*
 *   (udp|tcp) host [port [mcastttl [mcastif]]]
 */
int
ccndc_destroy(struct ccndc_data *self,
              int check_only,
              const char *cmd_orig)
{
    int ret_code = -1;
    char *cmd, *cmd_token;
    char *cmd_proto = NULL;
    char *cmd_host = NULL;
    char *cmd_port = NULL;
    char *cmd_mcastttl = NULL;
    char *cmd_mcastif = NULL;
    struct ccn_face_instance *face = NULL;
    struct ccn_face_instance *newface = NULL;
    
    if (cmd_orig == NULL) {
        ccndc_warn(__LINE__, "command error\n");
        return -1;
    }
    
    cmd = strdup(cmd_orig);
    if (cmd == NULL) {
        ccndc_warn(__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
    cmd_token = cmd;
    GET_NEXT_TOKEN(cmd_token, cmd_proto);
    GET_NEXT_TOKEN(cmd_token, cmd_host);
    GET_NEXT_TOKEN(cmd_token, cmd_port);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastttl);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastif);
    
    // perform sanity checking
    face = parse_ccn_face_instance(self, cmd_proto, cmd_host, cmd_port,
                                   cmd_mcastttl, cmd_mcastif, (~0U) >> 1);
    if (face == NULL)
        goto Cleanup;
    
    if (!check_only) {
        // TODO: should use queryface when implemented
        if (0 != strcasecmp(cmd_proto, "face")) {
            newface = ccndc_do_face_action(self, "newface", face);
            if (newface == NULL) {
                ccndc_warn(__LINE__, "Cannot create/lookup face\n");
                goto Cleanup;
            }
            face->faceid = newface->faceid;
            ccn_face_instance_destroy(&newface);
        }
        newface = ccndc_do_face_action(self, "destroyface", face);
        if (newface == NULL) {
            ccndc_warn(__LINE__, "Cannot destroy face %d or the face does not exist\n", face->faceid);
            goto Cleanup;
        }
        ccn_face_instance_destroy(&newface);
    }  
    ret_code = 0;
Cleanup:
    ccn_face_instance_destroy(&face);
    free(cmd);
    return ret_code;
}    

int
ccndc_strategy(struct ccndc_data *self,
                  int check_only,
                enum strat_cmd cmd,
                  const char *options_orig)
{
    char *options, *cmd_token;
    struct ccn_strategy_selection *strategy = NULL;
    struct ccn_strategy_selection *new_strategy = NULL;
    struct ccn_charbuf *prefix = NULL;
    int ret_code = 0;

    char *cmd_prefix = NULL;
    char *cmd_strategy = NULL;
    char *cmd_params = NULL;
    char *cmd_lifetime = NULL;
    
    if (options_orig == NULL) {
        ccndc_warn(__LINE__, "command error\n");
        return -1;
    }
    
    options = strdup(options_orig);
    if (options == NULL) {
        ccndc_warn(__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
    cmd_token = options;
    GET_NEXT_TOKEN(cmd_token, cmd_prefix);
    if (cmd == STRAT_SET) {
        GET_NEXT_TOKEN(cmd_token, cmd_strategy);
        GET_NEXT_TOKEN(cmd_token, cmd_params);
        GET_NEXT_TOKEN(cmd_token, cmd_lifetime);
    }
    
    // sanity check
    strategy = parse_ccn_strategy_selection(self, cmd_prefix, cmd_strategy, cmd_params, 0);
    if (strategy == NULL)
        goto Cleanup;
    
    if (!check_only) {
        char *action = NULL;
        switch (cmd) {
            case STRAT_SET:
                action = "setstrategy";
                break;
            case STRAT_GET:
                action = "getstrategy";
                break;
            case STRAT_REMOVE:
                action = "removestrategy";
                break;
            default:
                break;
        }
        new_strategy = ccndc_do_strategy_action(self, action, strategy);
        if (new_strategy == NULL) {
            ccndc_warn(__LINE__, "Unable to %s\n", action);
            ret_code = -1;
            goto Cleanup;
        }
        prefix = ccn_charbuf_create();
        ccn_uri_append(prefix, new_strategy->name_prefix->buf,
                       new_strategy->name_prefix->length, 0);
        printf("Strategy at %s is %s %s\n", ccn_charbuf_as_string(prefix),
               new_strategy->strategyid, new_strategy->parameters);
    }
    ret_code = 0;
Cleanup:
    ccn_strategy_selection_destroy(&strategy);
    ccn_strategy_selection_destroy(&new_strategy);
    ccn_charbuf_destroy(&prefix);
    free(options);
    return ret_code;
}

/*
 *   (udp|tcp) host [port [mcastttl [mcastif]]]
 */
/*
 *   uri (udp|tcp) host [port [flags [mcastttl [mcastif]]]])
 *   uri face faceid
 */
int
ccndc_renew(struct ccndc_data *self,
            int check_only,
            const char *cmd_orig)
{
    int ret_code = -1;
    char *cmd, *cmd_token;
    char *cmd_uri = NULL;
    char *cmd_proto = NULL;
    char *cmd_host = NULL;
    char *cmd_port = NULL;
    char *cmd_flags = NULL;
    char *cmd_mcastttl = NULL;
    char *cmd_mcastif = NULL;
    struct ccn_face_instance *face = NULL;
    struct ccn_face_instance *newface = NULL;
    struct ccn_forwarding_entry *prefix = NULL;
    
    if (cmd_orig == NULL) {
        ccndc_warn(__LINE__, "command error\n");
        return -1;
    }
    
    cmd = strdup(cmd_orig);
    if (cmd == NULL) {
        ccndc_warn(__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
    cmd_token = cmd;
    GET_NEXT_TOKEN(cmd_token, cmd_uri);
    GET_NEXT_TOKEN(cmd_token, cmd_proto);
    GET_NEXT_TOKEN(cmd_token, cmd_host);
    GET_NEXT_TOKEN(cmd_token, cmd_port);
    GET_NEXT_TOKEN(cmd_token, cmd_flags);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastttl);
    GET_NEXT_TOKEN(cmd_token, cmd_mcastif);
    
    // perform sanity checking
    face = parse_ccn_face_instance(self, cmd_proto, cmd_host, cmd_port,
                                   cmd_mcastttl, cmd_mcastif, (~0U) >> 1);
    prefix = parse_ccn_forwarding_entry(self, cmd_uri, cmd_flags, self->lifetime);
    if (face == NULL || prefix == NULL)
        goto Cleanup;
    
    if (!check_only) {
        // look up the old face ("queryface" would be useful)
        newface = ccndc_do_face_action(self, "newface", face);
        if (newface == NULL) {
            ccndc_warn(__LINE__, "Cannot create/lookup face\n");
            goto Cleanup;
        }
        face->faceid = newface->faceid;
        ccn_face_instance_destroy(&newface);
        // destroy the old face
        newface = ccndc_do_face_action(self, "destroyface", face);
        if (newface == NULL) {
            ccndc_warn(__LINE__, "Cannot destroy face %d or the face does not exist\n", face->faceid);
            goto Cleanup;
        }
        ccn_face_instance_destroy(&newface);
        // recreate the face
        newface = ccndc_do_face_action(self, "newface", face);
        if (newface == NULL) {
            ccndc_warn(__LINE__, "Cannot create/lookup face\n");
            goto Cleanup;
        }
        prefix->faceid = newface->faceid;
        ccn_face_instance_destroy(&newface);
        // and add the prefix to it
        ret_code = ccndc_do_prefix_action(self, "prefixreg", prefix);
        if (ret_code < 0) {
            ccndc_warn(__LINE__, "Cannot register prefix [%s]\n", cmd_uri);
            goto Cleanup;
        }
    }  
    ret_code = 0;
Cleanup:
    ccn_face_instance_destroy(&face);
    ccn_forwarding_entry_destroy(&prefix);
    free(cmd);
    return (ret_code);
}


int
ccndc_destroyface(struct ccndc_data *self,
                  int check_only,
                  const char *cmd_orig)
{
    int ret_code = 0;
    char *cmd, *cmd_token;
    char *cmd_faceid = NULL;
    struct ccn_face_instance *face;
    struct ccn_face_instance *newface;
    
    if (cmd_orig == NULL) {
        ccndc_warn(__LINE__, "command error\n");
        return -1;
    }
    
    cmd = strdup(cmd_orig);
    if (cmd == NULL) {
        ccndc_warn(__LINE__, "Cannot allocate memory for copy of the command\n");
        return -1;
    }            
    
    cmd_token = cmd;    
    GET_NEXT_TOKEN(cmd_token, cmd_faceid);
    
    face = parse_ccn_face_instance_from_face(self, cmd_faceid);
    if (face == NULL) {
        ret_code = -1;
    }
    
    if (ret_code == 0 && check_only == 0) {
        newface = ccndc_do_face_action(self, "destroyface", face);
        if (newface == NULL) {
            ccndc_warn(__LINE__, "Cannot destroy face %d or the face does not exist\n", face->faceid);        
        }
        ccn_face_instance_destroy(&newface);
    }
    
    ccn_face_instance_destroy(&face);
    free(cmd);
    return ret_code;
}


int
ccndc_srv(struct ccndc_data *self,
          const unsigned char *domain,
          size_t domain_size)
{
    char *proto = NULL;
    char *host = NULL;
    int port = 0;
    char port_str[10];
    struct ccn_charbuf *uri;
    struct ccn_charbuf *uri_auto = NULL;
    struct ccn_face_instance *face;
    struct ccn_face_instance *newface;
    struct ccn_forwarding_entry *prefix;
    struct ccn_forwarding_entry *prefix_auto;
    int res;
    
    res = ccndc_query_srv(domain, domain_size, &host, &port, &proto);
    if (res < 0) {
        return -1;
    }
    
    uri = ccn_charbuf_create();
    ccn_charbuf_append_string(uri, "ccnx:/");
    if (domain_size != 0) {
        ccn_uri_append_percentescaped(uri, domain, domain_size);
    }
    
    snprintf (port_str, sizeof(port_str), "%d", port);
    
    /* now process the results */
    /* pflhead, lineno=0, "add" "ccnx:/asdfasdf.com/" "tcp|udp", host, portstring, NULL NULL NULL */
    
    ccndc_note(__LINE__, " >>> trying:   add %s %s %s %s <<<\n", ccn_charbuf_as_string(uri), proto, host, port_str);
    
    face = parse_ccn_face_instance(self, proto, host, port_str, NULL, NULL,
                                   (~0U) >> 1);
    
    prefix = parse_ccn_forwarding_entry(self, ccn_charbuf_as_string(uri), NULL,
                                        self->lifetime);
    if (face == NULL || prefix == NULL) {
        res = -1;
        goto Cleanup;
    }
    
    // crazy operation
    // First. "Create" face, which will do nothing if face already exists
    // Second. Destroy the face
    // Third. Create face for real
    
    newface = ccndc_do_face_action(self, "newface", face);
    if (newface == NULL) {
        ccndc_warn(__LINE__, "Cannot create/lookup face\n");
        res = -1;
        goto Cleanup;
    }
    
    face->faceid = newface->faceid;
    ccn_face_instance_destroy(&newface);
    
    newface = ccndc_do_face_action(self, "destroyface", face);
    if (newface == NULL) {
        ccndc_warn(__LINE__, "Cannot destroy face\n");
    } else {
        ccn_face_instance_destroy(&newface);
    }
    
    newface = ccndc_do_face_action(self, "newface", face);
    if (newface == NULL) {
        ccndc_warn(__LINE__, "Cannot create/lookup face\n");
        res = -1;
        goto Cleanup;
    }
    
    prefix->faceid = newface->faceid;
    ccn_face_instance_destroy(&newface);
    
    res = ccndc_do_prefix_action(self, "prefixreg", prefix);
    if (res < 0) {
        ccndc_warn(__LINE__, "Cannot register prefix [%s]\n", ccn_charbuf_as_string(uri));
    }

    uri_auto = ccn_charbuf_create();
    ccn_charbuf_append_string(uri_auto, "ccnx:/autoconf-route");
    prefix_auto = parse_ccn_forwarding_entry(self, ccn_charbuf_as_string(uri_auto), NULL,
                                        self->lifetime);
    if (prefix_auto == NULL) {
        res = -1;
        goto Cleanup;
    }

    prefix_auto->faceid = prefix->faceid;
    res = ccndc_do_prefix_action(self, "prefixreg", prefix_auto);
    if (res < 0) {
        ccndc_warn(__LINE__, "Cannot register prefix_auto [%s]\n", ccn_charbuf_as_string(uri_auto));
    }
    
Cleanup:
    free(host);
    ccn_charbuf_destroy(&uri);
    ccn_charbuf_destroy(&uri_auto);
    ccn_face_instance_destroy(&face);
    ccn_forwarding_entry_destroy(&prefix);
    ccn_forwarding_entry_destroy(&prefix_auto);
    return res;
}


struct ccn_forwarding_entry *
parse_ccn_forwarding_entry(struct ccndc_data *self,
                           const char *cmd_uri,
                           const char *cmd_flags,
                           int freshness)
{
    int res = 0;
    struct ccn_forwarding_entry *entry;
    
    entry= calloc(1, sizeof(*entry));
    if (entry == NULL) {
        ccndc_warn(__LINE__, "Fatal error: memory allocation failed\n");
        goto ExitOnError;
    }
    
    entry->name_prefix = ccn_charbuf_create();
    if (entry->name_prefix == NULL) {
        ccndc_warn(__LINE__, "Fatal error: memory allocation failed\n");
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
    
    res = ccn_name_from_uri(entry->name_prefix, cmd_uri);
    if (res < 0) {
        ccndc_warn(__LINE__, "command error, bad CCNx URI '%s'\n", cmd_uri);
        goto ExitOnError;
    }
    
    entry->flags = -1;
    if (cmd_flags != NULL && cmd_flags[0] != 0) {
        char *endptr;
        entry->flags = strtol(cmd_flags, &endptr, 10);
        if ((endptr != &cmd_flags[strlen(cmd_flags)]) ||
            (entry->flags & ~CCN_FORW_PUBMASK) != 0) {
            ccndc_warn(__LINE__, "command error, invalid flags %s\n", cmd_flags);
            goto ExitOnError;
        }
    }
    
    entry->lifetime = freshness;
    return (entry);
    
ExitOnError:
    ccn_forwarding_entry_destroy(&entry);
    return (NULL);
}


// creates a full structure without action, if proto == "face" only the
// faceid (from cmd_host parameter) and lifetime will be filled in.
struct ccn_face_instance *
parse_ccn_face_instance(struct ccndc_data *self,
                        const char *cmd_proto,
                        const char *cmd_host,     const char *cmd_port,
                        const char *cmd_mcastttl, const char *cmd_mcastif,
                        int freshness)
{
    struct ccn_face_instance *entry;
    struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG)};
    struct addrinfo mcasthints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG | AI_NUMERICHOST)};
    struct addrinfo *raddrinfo = NULL;
    struct addrinfo *mcastifaddrinfo = NULL;
    char rhostnamebuf [NI_MAXHOST];
    char rhostportbuf [NI_MAXSERV];
    int off_address = -1, off_port = -1, off_source_address = -1;
    int res;
    int socktype;
    
    entry = calloc(1, sizeof(*entry));
    if (entry == NULL) {
        ccndc_warn(__LINE__, "Fatal error: memory allocation failed\n");
        goto ExitOnError;
    }
    // allocate storage for Face data
    entry->store = ccn_charbuf_create();
    if (entry->store == NULL) {
        ccndc_warn(__LINE__, "Fatal error: memory allocation failed\n");
        goto ExitOnError;
    }
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
    } else if (strcasecmp(cmd_proto, "tcp") == 0) {
        entry->descr.ipproto = IPPROTO_TCP;
        socktype = SOCK_STREAM;
    } else if (strcasecmp(cmd_proto, "face") == 0) {
        errno = 0;
        unsigned long faceid = strtoul(cmd_host, (char **)NULL, 10);
        if (errno == ERANGE || errno == EINVAL || faceid > UINT_MAX || faceid == 0) {
            ccndc_warn(__LINE__, "command error, face number invalid or out of range '%s'\n", cmd_host);
            goto ExitOnError;
        }
        entry->faceid = (unsigned) faceid;
        entry->lifetime = freshness;
        return (entry);
    } else {
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
    res = getaddrinfo(cmd_host, cmd_port, &hints, &raddrinfo);
    if (res != 0 || raddrinfo == NULL) {
        ccndc_warn(__LINE__, "command error, getaddrinfo for host [%s] port [%s]: %s\n", cmd_host, cmd_port, gai_strerror(res));
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
    res = ccn_charbuf_append(entry->store, rhostnamebuf, strlen(rhostnamebuf)+1);
    if (res != 0) {
        ccndc_warn(__LINE__, "Cannot append to charbuf\n");
        goto ExitOnError;
    }
    
    off_port = entry->store->length;
    res = ccn_charbuf_append(entry->store, rhostportbuf, strlen(rhostportbuf)+1);
    if (res != 0) {
        ccndc_warn(__LINE__, "Cannot append to charbuf\n");
        goto ExitOnError;
    }
    
    entry->descr.mcast_ttl = -1;
    if (cmd_mcastttl != NULL) {
        char *endptr;
        entry->descr.mcast_ttl = strtol(cmd_mcastttl, &endptr, 10); 
        if ((endptr != &cmd_mcastttl[strlen(cmd_mcastttl)]) ||
            entry->descr.mcast_ttl < 0 || entry->descr.mcast_ttl > 255) {
            ccndc_warn(__LINE__, "command error, invalid multicast ttl: %s\n", cmd_mcastttl);
            goto ExitOnError;
        }
    }
    
    if (cmd_mcastif != NULL) {
        res = getaddrinfo(cmd_mcastif, NULL, &mcasthints, &mcastifaddrinfo);
        if (res != 0) {
            ccndc_warn(__LINE__, "command error, incorrect multicast interface [%s]: "
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
        res = ccn_charbuf_append(entry->store, rhostnamebuf, strlen(rhostnamebuf)+1);
        if (res != 0) {
            ccndc_warn(__LINE__, "Cannot append to charbuf\n");
            goto ExitOnError;
        }
    }
    
    entry->descr.address = (const char *)(entry->store->buf + off_address);
    entry->descr.port = (const char *)(entry->store->buf + off_port);
    if (off_source_address >= 0) {
        entry->descr.source_address = (const char *)(entry->store->buf + off_source_address);
    }
    
    entry->lifetime = freshness;
    
    return entry;
    
ExitOnError:
    ccn_face_instance_destroy(&entry);
    return (NULL);
}

struct ccn_face_instance *
parse_ccn_face_instance_from_face(struct ccndc_data *self,
                                  const char *cmd_faceid)
{
    struct ccn_face_instance *entry = calloc(1, sizeof(*entry));
    
    // allocate storage for Face data
    entry->store = ccn_charbuf_create();
    
    // copy static info
    entry->ccnd_id = (const unsigned char *)self->ccnd_id;
    entry->ccnd_id_size = self->ccnd_id_size;
    
    /* destroy a face - the URI field will hold the face number */
    if (cmd_faceid == NULL) {
        ccndc_warn(__LINE__, "command error, missing face number for destroyface\n");
        goto ExitOnError;
    }
    
    char *endptr;
    int facenumber = strtol(cmd_faceid, &endptr, 10);
    if ((endptr != &cmd_faceid[strlen(cmd_faceid)]) ||
        facenumber < 0) {
        ccndc_warn(__LINE__, "command error invalid face number for destroyface: %d\n", facenumber);
        goto ExitOnError;
    }
    
    entry->faceid = facenumber;
    
    return entry;
    
ExitOnError:
    ccn_face_instance_destroy(&entry);
    return (NULL);
}

struct ccn_strategy_selection *
parse_ccn_strategy_selection(struct ccndc_data *self,
                             const char *cmd_prefix,
                             const char *cmd_strategy,
                             const char *cmd_params,
                             int freshness)
{
    int res = 0;
    struct ccn_strategy_selection *strategy;
    int off_strategy = -1, off_params = -1;
    
    strategy= calloc(1, sizeof(*strategy));
    if (strategy == NULL) {
        ccndc_warn(__LINE__, "Fatal error: memory allocation failed\n");
        goto ExitOnError;
    }
    
    // allocate storage for strategy data
    strategy->store = ccn_charbuf_create();
    if (strategy->store == NULL) {
        ccndc_warn(__LINE__, "Fatal error: memory allocation failed\n");
        goto ExitOnError;
    }
    
    // copy static info
    strategy->ccnd_id = (const unsigned char *)self->ccnd_id;
    strategy->ccnd_id_size = self->ccnd_id_size;
    
    /* we will be working with the strategy on this prefix */
    if (cmd_prefix == NULL) {
        ccndc_warn(__LINE__, "command error, missing CCNx URI for prefix\n");
        goto ExitOnError;
    }
    
    strategy->name_prefix = ccn_charbuf_create();
    if (strategy->name_prefix == NULL) {
        ccndc_warn(__LINE__, "Fatal error: memory allocation failed\n");
        goto ExitOnError;
    }
    
    res = ccn_name_from_uri(strategy->name_prefix, cmd_prefix);
    if (res < 0) {
        ccndc_warn(__LINE__, "command error, bad CCNx URI '%s'\n", cmd_prefix);
        goto ExitOnError;
    }
    
    if (cmd_strategy != NULL) {
        off_strategy = strategy->store->length;
        res = ccn_charbuf_append(strategy->store, cmd_strategy, 1+strlen(cmd_strategy));
    }
    
    if (cmd_params != NULL) {
        off_params = strategy->store->length;
        res = ccn_charbuf_append(strategy->store, cmd_params, 1+strlen(cmd_params));
    }
    if (off_strategy >= 0)
        strategy->strategyid = (const char *)(strategy->store->buf + off_strategy);
    if (off_params >= 0)
        strategy->parameters = (const char *)(strategy->store->buf + off_params);
    strategy->lifetime = freshness;
    return (strategy);
    
ExitOnError:
    ccn_strategy_selection_destroy(&strategy);
    return (NULL);
}


///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
// "private section
///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

int
ccndc_get_ccnd_id(struct ccndc_data *self)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    char ccndid_uri[] = "ccnx:/%C1.M.S.localhost/%C1.M.SRV/ccnd/KEY";
    const unsigned char *ccndid_result;
    int res = 0;
    
    name = ccn_charbuf_create();
    if (name == NULL) {
        ccndc_warn(__LINE__, "Unable to allocate storage for service locator name charbuf\n");
        return -1;
    }
    
    resultbuf = ccn_charbuf_create();
    if (resultbuf == NULL) {
        ccndc_warn(__LINE__, "Unable to allocate storage for result charbuf\n");
        res = -1;
        goto Cleanup;
    }
    
    res = ccn_name_from_uri(name, ccndid_uri);
    if (res < 0) {
        ccndc_warn(__LINE__, "Unable to parse service locator URI for ccnd key\n");
        goto Cleanup;
    }
    
    res = ccn_get(self->ccn_handle,
                  name,
                  self->local_scope_template,
                  4500, resultbuf, &pcobuf, NULL, 0);
    if (res < 0) {
        ccndc_warn(__LINE__, "Unable to get key from ccnd\n");
        goto Cleanup;
    }
    
    res = ccn_ref_tagged_BLOB (CCN_DTAG_PublisherPublicKeyDigest,
                               resultbuf->buf,
                               pcobuf.offset[CCN_PCO_B_PublisherPublicKeyDigest],
                               pcobuf.offset[CCN_PCO_E_PublisherPublicKeyDigest],
                               &ccndid_result, &self->ccnd_id_size);
    if (res < 0) {
        ccndc_warn(__LINE__, "Unable to parse ccnd response for ccnd id\n");
        goto Cleanup;
    }
    
    if (self->ccnd_id_size > sizeof (self->ccnd_id))
    {
        ccndc_warn(__LINE__, "Incorrect size for ccnd id in response\n");
        goto Cleanup;
    }
    
    memcpy(self->ccnd_id, ccndid_result, self->ccnd_id_size);
    
Cleanup:
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&resultbuf);
    return (res);
}


struct ccn_face_instance *
ccndc_do_face_action(struct ccndc_data *self,
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
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
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
    d = ccn_buf_decoder_start(&decoder, ptr, length);
    if (ccn_buf_match_dtag(d, CCN_DTAG_StatusResponse)) {
        unsigned errcode;
        struct ccn_charbuf *msg = ccn_charbuf_create();
        ccn_buf_advance(d);
        errcode = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_StatusCode);
        res = ccn_parse_tagged_string(d, CCN_DTAG_StatusText, msg);
        ccndc_warn(__LINE__, "Status %d: %s\n", errcode, ccn_charbuf_as_string(msg));
        ccn_charbuf_destroy(&msg);
    }
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

int
ccndc_do_prefix_action(struct ccndc_data *self,
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
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
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
    d = ccn_buf_decoder_start(&decoder, ptr, length);
    if (ccn_buf_match_dtag(d, CCN_DTAG_StatusResponse)) {
        unsigned errcode;
        struct ccn_charbuf *msg = ccn_charbuf_create();
        ccn_buf_advance(d);
        errcode = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_StatusCode);
        res = ccn_parse_tagged_string(d, CCN_DTAG_StatusText, msg);
        ccndc_warn(__LINE__, "Status %d: %s\n", errcode, ccn_charbuf_as_string(msg));
        ccn_charbuf_destroy(&msg);
    }
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

struct ccn_strategy_selection *
ccndc_do_strategy_action(struct ccndc_data *self,
                     const char *action,
                     struct ccn_strategy_selection *strategy_selection)
{
    struct ccn_charbuf *strategy = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    struct ccn_strategy_selection *new_strategy_selection = NULL;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    const unsigned char *ptr = NULL;
    size_t length = 0;
    int res = 0;
    
    strategy_selection->action = action;
    
    /* Encode the given face instance */
    strategy = ccn_charbuf_create();
    ON_NULL_CLEANUP(strategy);
    ON_ERROR_CLEANUP(ccnb_append_strategy_selection(strategy, strategy_selection));
    
    temp = ccn_charbuf_create();
    ON_NULL_CLEANUP(temp);
    res = ccn_sign_content(self->ccn_handle, temp, self->no_name, NULL, strategy->buf, strategy->length);
    ON_ERROR_CLEANUP(res);
    resultbuf = ccn_charbuf_create();
    ON_NULL_CLEANUP(resultbuf);
    
    /* Construct the Interest name that will create the face */
    name = ccn_charbuf_create();
    ON_NULL_CLEANUP(name);
    ON_ERROR_CLEANUP(ccn_name_init(name));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, "ccnx"));
    ON_ERROR_CLEANUP(ccn_name_append(name, strategy_selection->ccnd_id, strategy_selection->ccnd_id_size));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, strategy_selection->action));
    ON_ERROR_CLEANUP(ccn_name_append(name, temp->buf, temp->length));
    
    res = ccn_get(self->ccn_handle, name, self->local_scope_template, 1000, resultbuf, &pcobuf, NULL, 0);
    ON_ERROR_CLEANUP(res);
    
    ON_ERROR_CLEANUP(ccn_content_get_value(resultbuf->buf, resultbuf->length, &pcobuf, &ptr, &length));
    d = ccn_buf_decoder_start(&decoder, ptr, length);
    if (ccn_buf_match_dtag(d, CCN_DTAG_StatusResponse)) {
        unsigned errcode;
        struct ccn_charbuf *msg = ccn_charbuf_create();
        ccn_buf_advance(d);
        errcode = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_StatusCode);
        res = ccn_parse_tagged_string(d, CCN_DTAG_StatusText, msg);
        ccndc_warn(__LINE__, "Status %d: %s\n", errcode, ccn_charbuf_as_string(msg));
        ccn_charbuf_destroy(&msg);
    }
    new_strategy_selection = ccn_strategy_selection_parse(ptr, length);
    ON_NULL_CLEANUP(new_strategy_selection);
    ccn_charbuf_destroy(&strategy);
    ccn_charbuf_destroy(&signed_info);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&name);
    return (new_strategy_selection);
    
Cleanup:
    ccn_charbuf_destroy(&strategy);
    ccn_charbuf_destroy(&signed_info);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&name);
    ccn_strategy_selection_destroy(&new_strategy_selection);
    return (NULL);
}
