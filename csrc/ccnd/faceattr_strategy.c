/*
 * @file ccnd/faceattr_strategy.c
 *
 * Part of ccnd - the CCNx Daemon
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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

#include <ctype.h>
#include <strings.h>
#include <ccn/charbuf.h>
#include "ccnd_strategy.h"

/**
 * A non-strategy for testing purposes
 *
 * This hack provides a way of setting
 * face attributes from the outside.
 * It should go away when an actual protocol
 * for changing face attributes is defined
 * and implemented.
 *
 * Use a parameter string of the form
 *  faceid/attrname=val
 * to set a face attribute value.
 * The value may be a non-negative number or true or false.
 *
 * A parameter string with only a faceid prints all of the non-zero attributes.
 */
void
ccnd_faceattr_strategy_impl(
    struct ccnd_handle *h,
    struct strategy_instance *instance,
    struct ccn_strategy *strategy,
    enum ccn_strategy_op op,
    unsigned faceid)
{
    const char *s = NULL;
    const char *dlm = NULL;
    struct ccn_charbuf *c = NULL;
    struct face *face = NULL;
    int is_bool_attr;
    int i;
    int j;
    int ndx;
    unsigned f;
    unsigned v;
    
    if (op == CCNST_INIT) {
        s = instance->parameters;
        if (s == NULL)
            s = "";
        i = 0;
        f = 0;
        while ('0' <= s[i] && s[i] <= '9')
            f = f * 10 + (s[i++] - '0');
        if (s[i] == 0)
            goto Show;
        if (s[i] != '/')
            goto Fail;
        i++;
        j = i;
        while (s[i] != 0 && s[i] != '=') {
            if (!isalpha(s[i]))
                goto Fail;
            i++;
        }
        if (i == j || s[i] == 0)
            goto Fail;
        c = ccn_charbuf_create();
        ccn_charbuf_append(c, &(s[j]), i - j);
        i++;
        if (s[i] == 0)
            goto Fail;
        v = 0;
        is_bool_attr = 0;
        if (strcasecmp(s + i, "true") == 0)
            v = is_bool_attr = 1;
        else if (strcasecmp(s + i, "false") == 0)
            is_bool_attr = 1;
        else {
            v = 0;
            while ('0' <= s[i] && s[i] <= '9')
                v = v * 10 + (s[i++] - '0');
            if (s[i] != 0)
                goto Fail;
        }
        face = ccnd_face_from_faceid(h, f);
        if (face == NULL)
            goto Fail;
        if (is_bool_attr)
            ndx = faceattr_bool_index_from_name(h, ccn_charbuf_as_string(c));
        else
            ndx = faceattr_index_from_name(h, ccn_charbuf_as_string(c));
        if (faceattr_set(h, face, ndx, v) < 0)
            goto Fail;
        ccn_charbuf_destroy(&c);
        strategy_init_error(h, instance, s);
        return;
    Show:
        face = ccnd_face_from_faceid(h, f);
        if (face == NULL)
            goto Fail;
        c = ccn_charbuf_create();
        ccn_charbuf_putf(c, "%u", f);
        dlm = "/";
        for (s = faceattr_next_name(h, NULL); s != NULL;
             s = faceattr_next_name(h, s)) {
            ndx = faceattr_index_from_name(h, s);
            v = faceattr_get(h, face, ndx);
            if (v != 0) {
                ccn_charbuf_putf(c, "%s%s=%u", dlm, s, v);
                dlm = "&";
            }
        }
        strategy_init_error(h, instance, ccn_charbuf_as_string(c));
        ccn_charbuf_destroy(&c);
        return;
    Fail:
        ccn_charbuf_destroy(&c);
        strategy_init_error(h, instance, "Sorry, Charlie");
    }
    else if (op == CCNST_FINALIZE) {
        ccnd_msg(h, "ccnd_faceattr_strategy_impl CCNST_INIT");
    }
}
