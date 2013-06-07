/**
 * @file ccnsendchunks.c
 * Injects chunks of data from stdin into ccn
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008-2010, 2013 Palo Alto Research Center, Inc.
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
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>
#include <ccn/keystore.h>
#include <ccn/signing.h>

struct mydata {
    int content_received;
    int content_sent;
    int outstanding;
};

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_OK);
    if ((kind != CCN_UPCALL_CONTENT && kind != CCN_UPCALL_CONTENT_UNVERIFIED) || md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    md->content_received++;
    ccn_set_run_timeout(info->h, 0);
    return(CCN_UPCALL_RESULT_OK);
}

enum ccn_upcall_res
incoming_interest(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind != CCN_UPCALL_INTEREST || md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    if ((info->pi->answerfrom & CCN_AOK_NEW) != 0) {
        if (md->outstanding < 10)
            md->outstanding = 10;
        ccn_set_run_timeout(info->h, 0);
    }
    return(CCN_UPCALL_RESULT_OK);
}

ssize_t
read_full(int fd, unsigned char *buf, size_t size)
{
    size_t i;
    ssize_t res = 0;
    for (i = 0; i < size; i += res) {
        res = read(fd, buf + i, size - i);
        if (res == -1) {
            if (errno == EAGAIN || errno == EINTR)
                res = 0;
            else
                return(res);
        }
        else if (res == 0)
            break;
    }
    return(i);
}

static void
usage(const char *progname)
{
        fprintf(stderr,
                "%s [-h] [-x freshness_seconds] [-b blocksize] [-o keydir] [-d digest] [-p password] URI\n"
                " Chops stdin into blocks (1K by default) and sends them "
                "as consecutively numbered ContentObjects "
                "under the given uri\n", progname);
        exit(1);
}

int
main(int argc, char **argv)
{
    const char *progname = argv[0];
    struct ccn *ccn = NULL;
    struct ccn_charbuf *root = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    long expire = -1;
    long blocksize = 1024;
    int i;
    int status = 0;
    int res;
    ssize_t read_res;
    unsigned char *buf = NULL;
    char *symmetric_suffix = NULL;
    char *dir = NULL;
    const char *password = NULL;
    struct mydata mydata = { 0 };
    struct ccn_closure in_content = {.p=&incoming_content, .data=&mydata};
    struct ccn_closure in_interest = {.p=&incoming_interest, .data=&mydata};
    while ((res = getopt(argc, argv, "hx:b:d:p:o:")) != -1) {
        switch (res) {
            case 'x':
                expire = atol(optarg);
                if (expire <= 0)
                    usage(progname);
                break;
            case 'b':
                blocksize = atol(optarg);
                break;
            case 'd':
                symmetric_suffix = optarg;
                break;
            case 'p':
                password = optarg;
                break;
            case 'o':
                dir = optarg;
                break;
            default:
            case 'h':
                usage(progname);
                break;
        }
    }
    argc -= optind;
    argv += optind;
    if (argc != 1)
        usage(progname);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, argv[0]);
    if (res < 0) {
        fprintf(stderr, "%s: bad CCN URI: %s\n", progname, argv[0]);
        exit(1);
    }
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }

    if (symmetric_suffix != NULL) {
        int len = sizeof(sp.pubid);
        struct ccn_charbuf *key_digest = ccn_charbuf_create();

        if (ccn_get_key_digest_from_suffix(ccn, dir, symmetric_suffix, password, key_digest)) {
            perror("Can't access keystore");
            exit(1);
        }
        if (key_digest->length < len)
            len = key_digest->length;
	memcpy(sp.pubid, key_digest->buf, len);
    }
    
    buf = calloc(1, blocksize);
    root = name;
    name = ccn_charbuf_create();
    temp = ccn_charbuf_create();
    templ = ccn_charbuf_create();

    /* Set up a handler for interests */
    ccn_charbuf_append(name, root->buf, root->length);
    ccn_set_interest_filter(ccn, name, &in_interest);
    
    /* Initiate check to see whether there is already something there. */
    ccn_charbuf_reset(temp);
    ccn_charbuf_putf(temp, "%d", 0);
    ccn_name_append(name, temp->buf, temp->length);
    ccn_charbuf_reset(templ);
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    ccnb_tagged_putf(templ, CCN_DTAG_MaxSuffixComponents, "%d", 1);
    // XXX - use pubid
    ccnb_element_end(templ); /* </Interest> */
    res = ccn_express_interest(ccn, name, &in_content, templ);
    if (res < 0) abort();
    
    sp.freshness = expire;
    for (i = 0;; i++) {
        read_res = read_full(0, buf, blocksize);
        if (read_res < 0) {
            perror("read");
            read_res = 0;
            status = 1;
        }
        if (read_res < blocksize) {
            sp.sp_flags |= CCN_SP_FINAL_BLOCK;
        }
        ccn_charbuf_reset(name);
        ccn_charbuf_append(name, root->buf, root->length);
        ccn_charbuf_reset(temp);
        ccn_charbuf_putf(temp, "%d", i);
        ccn_name_append(name, temp->buf, temp->length);
        ccn_charbuf_reset(temp);
        ccn_charbuf_append(temp, buf, read_res);
        ccn_charbuf_reset(temp);
        res = ccn_sign_content(ccn, temp, name, &sp, buf, read_res);
        if (res != 0) {
            fprintf(stderr, "Failed to sign ContentObject (res == %d)\n", res);
            exit(1);
        }
        /* Put the keylocator in the first block only. */
        sp.sp_flags |= CCN_SP_OMIT_KEY_LOCATOR;
        if (i == 0) {
            /* Finish check for old content */
            if (mydata.content_received == 0)
                ccn_run(ccn, 100);
            if (mydata.content_received > 0) {
                fprintf(stderr, "%s: name is in use: %s\n", progname, argv[0]);
                exit(1);
            }
            mydata.outstanding++; /* the first one is free... */
        }
        res = ccn_put(ccn, temp->buf, temp->length);
        if (res < 0) {
            fprintf(stderr, "ccn_put failed (res == %d)\n", res);
            exit(1);
        }
        if (read_res < blocksize)
            break;
        if (mydata.outstanding > 0)
            mydata.outstanding--;
        else
            res = 10;
        res = ccn_run(ccn, res * 100);
        if (res < 0) {
            status = 1;
            break;
        }
    }
    
    free(buf);
    buf = NULL;
    ccn_charbuf_destroy(&root);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&temp);
    ccn_destroy(&ccn);
    exit(status);
}
