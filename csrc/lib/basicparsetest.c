/**
 * @file basicparsetest.c
 * 
 * A CCNx test program.
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

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/face_mgmt.h>
#include <ccn/sockcreate.h>
#include <ccn/strategy_mgmt.h>
#include <ccn/reg_mgmt.h>
#include <ccn/header.h>

/**
 * This is for testing.
 *
 * Reads ccnb-encoded data from stdin and 
 * tries parsing with various parsers, and when successful turns
 * the result back into ccnb and tests for goodness.
 *
 */
int
main (int argc, char **argv)
{
    unsigned char buf[CCN_MAX_MESSAGE_BYTES];
    ssize_t size;
    struct ccn_face_instance *face_instance;
    struct ccn_forwarding_entry *forwarding_entry;
    struct ccn_strategy_selection *strategy_selection;
    struct ccn_header *header;
    int res = 1;
    struct ccn_charbuf *c = ccn_charbuf_create();
    int i;
    struct ccn_parsed_interest parsed_interest = {0};
    struct ccn_parsed_interest *pi = &parsed_interest;
    struct ccn_parsed_Link parsed_link = {0};
    struct ccn_parsed_Link *pl = &parsed_link;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;


    size = read(0, buf, sizeof(buf));
    if (size < 0)
        exit(0);
    
    face_instance = ccn_face_instance_parse(buf, size);
    if (face_instance != NULL) {
        printf("face_instance OK\n");
        c->length = 0;
        res = ccnb_append_face_instance(c, face_instance);
        if (res != 0)
            printf("face_instance append failed\n");
        if (memcmp(buf, c->buf, c->length) != 0)
            printf("face_instance mismatch\n");
        ccn_face_instance_destroy(&face_instance);
        face_instance = ccn_face_instance_parse(c->buf, c->length);
        if (face_instance == NULL) {
            printf("face_instance reparse failed\n");
            res = 1;
        }
    }
    ccn_face_instance_destroy(&face_instance);
    
    forwarding_entry = ccn_forwarding_entry_parse(buf, size);
    if (forwarding_entry != NULL) {
        printf("forwarding_entry OK\n");
        c->length = 0;
        res = ccnb_append_forwarding_entry(c, forwarding_entry);
        if (res != 0)
            printf("forwarding_entry append failed\n");
        if (memcmp(buf, c->buf, c->length) != 0)
            printf("forwarding_entry mismatch\n");
        ccn_forwarding_entry_destroy(&forwarding_entry);
        forwarding_entry = ccn_forwarding_entry_parse(c->buf, c->length);
        if (forwarding_entry == NULL) {
            printf("forwarding_entry reparse failed\n");
            res = 1;
        }
    }
    ccn_forwarding_entry_destroy(&forwarding_entry);
    
    strategy_selection = ccn_strategy_selection_parse(buf, size);
    if (strategy_selection != NULL) {
        printf("strategy_selection OK\n");
        c->length = 0;
        res = ccnb_append_strategy_selection(c, strategy_selection);
        if (res != 0)
            printf("strategy_selection append failed\n");
        if (memcmp(buf, c->buf, c->length) != 0)
            printf("strategy_selection mismatch\n");
        ccn_strategy_selection_destroy(&strategy_selection);
        strategy_selection = ccn_strategy_selection_parse(c->buf, c->length);
        if (strategy_selection == NULL) {
            printf("strategy_selection reparse failed\n");
            res = 1;
        }
    }
    ccn_strategy_selection_destroy(&strategy_selection);
        
    header = ccn_header_parse(buf, size);
    if (header != NULL) {
        printf("header OK\n");
        c->length = 0;
        res = ccnb_append_header(c, header);
        if (res != 0)
            printf("header append failed\n");
        if (memcmp(buf, c->buf, c->length) != 0)
            printf("header mismatch\n");
        ccn_header_destroy(&header);
        header = ccn_header_parse(c->buf, c->length);
        if (header == NULL) {
            printf("header reparse failed\n");
            res = 1;
        }
    }
    ccn_header_destroy(&header);

    i = ccn_parse_interest(buf, size, pi, NULL);
    if (i >= 0) {
        res = 0;
        printf("interest OK lifetime %jd (%d seconds)\n",
               ccn_interest_lifetime(buf, pi),
               ccn_interest_lifetime_seconds(buf, pi));
    }

    d = ccn_buf_decoder_start(&decoder, buf, size);
    i = ccn_parse_Link(d, pl, NULL);
    if (i >= 0) {
        res = 0;
        printf("link OK\n");
    }

    d = ccn_buf_decoder_start(&decoder, buf, size);
    i = ccn_parse_Collection_start(d);
    if (i >= 0) {
        while ((i = ccn_parse_Collection_next(d, pl, NULL)) > 0) {
	  printf("collection link OK\n");
        }
        if (i == 0) {
            res = 0;
            printf("collection OK\n");
        }
    }
    if (res != 0) {
        printf("URP\n");
    }
    exit(res);
}
