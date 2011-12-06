/**
 * @file dataresponsetest.c
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>

#include <ccn/ccn.h>

static struct options {
    int logging;
    int nointerest;
    int reconnect;
} options = {0, 0, 0};

struct handlerstate {
    int next;
    int count;
    struct handlerstateitem {
        char *filename;
        unsigned char *contents;
        size_t	size;
        struct ccn_parsed_ContentObject x;
        struct ccn_indexbuf *components;
    } *items;
};

enum ccn_upcall_res interest_handler(struct ccn_closure *selfp,
                                     enum ccn_upcall_kind,
                                     struct ccn_upcall_info *info);

int
main (int argc, char *argv[]) {
    struct ccn *ccn = NULL;
    struct ccn_closure *action;
    struct ccn_charbuf *namebuf = NULL;
    struct ccn_charbuf *interestnamebuf = NULL;
    struct ccn_charbuf *interesttemplatebuf = NULL;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    struct handlerstate *state;
    char *filename;
    char rawbuf[1024 * 1024];
    ssize_t rawlen;
    int i, n, res;
    int fd = -1;

    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("ccn_connect");
        exit(1);
    }
    
    state = calloc(1, sizeof(struct handlerstate));
    action = calloc(1, sizeof(struct ccn_closure));
    action->p = interest_handler;

    namebuf = ccn_charbuf_create();
    if (namebuf == NULL) {
        fprintf(stderr, "ccn_charbuf_create\n");
        exit(1);
    }
    res = ccn_name_init(namebuf);
    if (res < 0) {
        fprintf(stderr, "ccn_name_init\n");
        exit(1);
    }

    interestnamebuf = ccn_charbuf_create();
    interesttemplatebuf = ccn_charbuf_create();
    if (interestnamebuf == NULL || interesttemplatebuf == NULL) {
        fprintf(stderr, "ccn_charbuf_create\n");
        exit(1);
    }
    res = ccn_name_init(interestnamebuf);
    if (res < 0) {
        fprintf(stderr, "ccn_name_init\n");
        exit(1);
    }

    n = 0;
    for (i = 1; i < argc; i++) {
        if (fd != -1) close(fd);
        filename = argv[i];
        if (0 == strcmp(filename, "-d")) {
            options.logging++;
            continue;
        }
        if (0 == strcmp(filename, "-nointerest")) {
            options.nointerest = 1;
            continue;
        }
        if (0 == strcmp(filename, "-reconnect")) {
            options.reconnect = 1;
            continue;
        }
        
        if (options.logging > 0) fprintf(stderr, "Processing %s ", filename);
        fd = open(filename, O_RDONLY);
        if (fd == -1) {
            perror("- open");
            continue;
        }

        rawlen = read(fd, rawbuf, sizeof(rawbuf));
        if (rawlen <= 0) {
            perror("- read");
            continue;
        }
        
        d = ccn_buf_decoder_start(&decoder, (unsigned char *)rawbuf, rawlen);

        if (ccn_buf_match_dtag(d, CCN_DTAG_ContentObject)) {
            state->items = realloc(state->items, (n + 1) * sizeof(*(state->items)));
            if (state->items == NULL) {
                perror(" - realloc failed");
                exit(1);
            }
            memset(&(state->items[n]), 0, sizeof(*(state->items)));
            state->items[n].components = ccn_indexbuf_create();
            res = ccn_parse_ContentObject((unsigned char *)rawbuf, rawlen, &(state->items[n].x), state->items[n].components);
            if (res < 0) {
                if (options.logging > 0) fprintf(stderr, "Processing %s ", filename);
                fprintf(stderr, "- skipping: ContentObject error %d\n", res);
                ccn_indexbuf_destroy(&state->items[n].components);
                continue;
            }
            if (options.logging > 0) fprintf(stderr, "- ok\n");
            state->items[n].filename = filename;
            state->items[n].contents = malloc(rawlen);
            state->items[n].size = rawlen;
            memcpy(state->items[n].contents, rawbuf, rawlen);
            n++;
        } else if (ccn_buf_match_dtag(d, CCN_DTAG_Interest)) {
            struct ccn_parsed_interest interest = {0};
            if (options.nointerest == 0) {
                size_t name_start;
                size_t name_size;
                interestnamebuf->length = 0;
                interesttemplatebuf->length = 0;
                res = ccn_parse_interest((unsigned char *)rawbuf, rawlen, &interest, NULL);
                name_start = interest.offset[CCN_PI_B_Name];
                name_size = interest.offset[CCN_PI_E_Name] - name_start;
                ccn_charbuf_append(interestnamebuf, rawbuf + name_start, name_size);
                ccn_charbuf_append(interesttemplatebuf, rawbuf, rawlen);
                res = ccn_express_interest(ccn, interestnamebuf, action, interesttemplatebuf);
            }
        } else {
            if (options.logging == 0) fprintf(stderr, "Processing %s ", filename);
            fprintf(stderr, "- skipping: unknown type\n");
        }
    }
    state->count = n;
    action->data = state;

    if (ccn_name_init(namebuf) == -1) {
        fprintf(stderr, "ccn_name_init\n");
        exit(1);
    }

    res = ccn_set_interest_filter(ccn, namebuf, action);
    for (;;) {
        res = ccn_run(ccn, -1);
        ccn_disconnect(ccn);
        if (!options.reconnect)
            break;
        sleep(2);
        ccn_connect(ccn, NULL);
    }
    ccn_destroy(&ccn);
    exit(0);
}

int
match_components(unsigned char *msg1, struct ccn_indexbuf *comp1,
                 unsigned char *msg2, struct ccn_indexbuf *comp2) {
    int matched;
    int lc1, lc2;
    unsigned char *c1p, *c2p;

    for (matched = 0; (matched < comp1->n - 1) && (matched < comp2->n - 1); matched++) {
        lc1 = comp1->buf[matched + 1] - comp1->buf[matched];
        lc2 = comp2->buf[matched + 1] - comp2->buf[matched];
        if (lc1 != lc2) return (matched);

        c1p = msg1 + comp1->buf[matched];
        c2p = msg2 + comp2->buf[matched];
        if (memcmp(c1p, c2p, lc1) != 0) return (matched);
    }
    return (matched);
}

enum ccn_upcall_res
interest_handler(struct ccn_closure *selfp,
                 enum ccn_upcall_kind upcall_kind,
                 struct ccn_upcall_info *info)
{
    int i, c, mc, match, res;
    struct handlerstateitem item;
    struct handlerstate *state;
    size_t ccnb_size = 0;

    state = selfp->data;
    switch(upcall_kind) {
    case CCN_UPCALL_FINAL:
        fprintf(stderr, "Upcall final\n");
        return (0);

    case CCN_UPCALL_INTEREST_TIMED_OUT:
        fprintf(stderr, "refresh\n");
        return (CCN_UPCALL_RESULT_REEXPRESS);
        
    case CCN_UPCALL_CONTENT:
    case CCN_UPCALL_CONTENT_UNVERIFIED:
        ccnb_size = info->pco->offset[CCN_PCO_E];
        c = state->count;
        for (i = 0; i < c; i++) {
            if (info->content_comps->n == state->items[i].components->n) {
                mc = match_components((unsigned char *)info->content_ccnb, info->content_comps,
                                  state->items[i].contents, state->items[i].components);
                if (mc == (info->content_comps->n - 1)) {
                    fprintf(stderr, "Duplicate content\n");
                    return (0);
                }
            }
        }
        fprintf(stderr, "Storing content item %d ", c);
        state->items = realloc(state->items, (c + 1) * sizeof(*(state->items)));
        if (state->items == NULL) {
            perror("realloc failed");
            exit(1);
        }
        memset(&(state->items[c]), 0, sizeof(*(state->items)));
        state->items[c].components = ccn_indexbuf_create();
        /* XXX: probably should not have to do this re-parse of the content object */
        res = ccn_parse_ContentObject(info->content_ccnb, ccnb_size, &(state->items[c].x), state->items[c].components);
        if (res < 0) {
            fprintf(stderr, "- skipping: Not a ContentObject\n");
            ccn_indexbuf_destroy(&state->items[c].components);
            return (-1);
        }
        fprintf(stderr, "- ok\n");
        state->items[c].filename = "ephemeral";
        state->items[c].contents = malloc(ccnb_size);
        state->items[c].size = ccnb_size;
        memcpy(state->items[c].contents, info->content_ccnb, ccnb_size);
        state->count = c + 1;
        return (0);

    case CCN_UPCALL_CONTENT_BAD:
	fprintf(stderr, "Content signature verification failed! Discarding.\n");
	return (-1);

    case CCN_UPCALL_CONSUMED_INTEREST:
        fprintf(stderr, "Upcall consumed interest\n");
        return (-1); /* no data */

    case CCN_UPCALL_INTEREST:
        c = state->count;
        for (i = 0; i < c; i++) {
            match = ccn_content_matches_interest(state->items[i].contents,
                                                 state->items[i].size,
                                                 1,
                                                 NULL,
                                                 info->interest_ccnb,
                                                 info->pi->offset[CCN_PI_E],
                                                 info->pi);
            if (match) {
                ccn_put(info->h, state->items[i].contents, state->items[i].size);
                fprintf(stderr, "Sending %s\n", state->items[i].filename);
                if (i < c - 1) {
                    item = state->items[i];
                    memmove(&(state->items[i]), &(state->items[i+1]), sizeof(item) * ((c - 1) - i));
                    state->items[c - 1] = item;
                }
                return (1);
            }
        }
        return(0);
    case CCN_UPCALL_CONTENT_KEYMISSING:
    case CCN_UPCALL_CONTENT_RAW:
        /* should not happen */
        return (-1);
    }
    return (-1);
}
