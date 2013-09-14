/**
 * @file ccnpoke.c
 * Injects one chunk of data from stdin into ccn.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>
#include <ccn/keystore.h>
#include <ccn/signing.h>

static ssize_t
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
            "%s [-hflv] [-k key_uri] [-t type] [-V seg] [-w timeout] [-x freshness_seconds]"
            " ccnx:/some/place\n"
            " Reads data from stdin and sends it to the local ccnd"
            " as a single ContentObject under the given URI\n"
            "  -h - print this message and exit\n"
            "  -e file - extopt from supplied file\n"
            "  -f - force - send content even if no interest received\n"
            "  -l - set FinalBlockId from last segment of URI\n"
            "  -v - verbose\n"
            "  -k key_uri - use this name for key locator\n"
            "  -p n - limit registration to n (>=0) components of the given URI in the interest filter.\n"
            "  -t ( DATA | ENCR | GONE | KEY | LINK | NACK ) - set type\n"
            "  -V seg - generate version, use seg as name suffix\n"
            "  -w seconds - fail after this long if no interest arrives\n"
            "  -x seconds - set FreshnessSeconds\n"
            , progname);
    exit(1);
}

enum ccn_upcall_res
incoming_interest(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct ccn_charbuf *cob = selfp->data;
    int res;
    
    switch (kind) {
        case CCN_UPCALL_FINAL:
            break;
        case CCN_UPCALL_INTEREST:
            if (ccn_content_matches_interest(cob->buf, cob->length,
                    1, NULL,
                    info->interest_ccnb, info->pi->offset[CCN_PI_E],
                    info->pi)) {
                res = ccn_put(info->h, cob->buf, cob->length);
                if (res >= 0) {
                    selfp->intdata = 1;
                    ccn_set_run_timeout(info->h, 0);
                    return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
                }
            }
            break;
        default:
            break;
    }
    return(CCN_UPCALL_RESULT_OK);
}

int
main(int argc, char **argv)
{
    const char *progname = argv[0];
    struct ccn *ccn = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *pname = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *extopt = NULL;
    long expire = -1;
    int versioned = 0;
    size_t blocksize = 8*1024;
    int status = 0;
    int res;
    ssize_t read_res;
    unsigned char *buf = NULL;
    enum ccn_content_type content_type = CCN_CONTENT_DATA;
    struct ccn_closure in_interest = {.p=&incoming_interest};
    const char *postver = NULL;
    const char *key_uri = NULL;
    int force = 0;
    int verbose = 0;
    int timeout = -1;
    int setfinal = 0;
    int prefixcomps = -1;
    int fd;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    
    while ((res = getopt(argc, argv, "e:fhk:lvV:p:t:w:x:")) != -1) {
        switch (res) {
            case 'e':
                if (extopt == NULL)
                    extopt = ccn_charbuf_create();
                fd = open(optarg, O_RDONLY);
                if (fd < 0) {
                    perror(optarg);
                    exit(1);
                }
                for (;;) {
                    read_res = read(fd, ccn_charbuf_reserve(extopt, 64), 64);
                    if (read_res <= 0)
                        break;
                    extopt->length += read_res;
                }
                if (read_res < 0)
                    perror(optarg);
                close(fd);
                break;
            case 'f':
                force = 1;
                break;
            case 'l':
                setfinal = 1; // set FinalBlockID to last comp of name
                break;
            case 'k':
                key_uri = optarg;
                break;
            case 'p':
                prefixcomps = atoi(optarg);
                if (prefixcomps < 0)
                    usage(progname);
                break;
            case 'x':
                expire = atol(optarg);
                if (expire <= 0)
                    usage(progname);
                break;
            case 'v':
                verbose = 1;
                break;
            case 'V':
                versioned = 1;
                postver = optarg;
                if (0 == memcmp(postver, "%00", 3))
                    setfinal = 1;
                break;
            case 'w':
                timeout = atol(optarg);
                if (timeout <= 0)
                    usage(progname);
                timeout *= 1000;
                break;
            case 't':
                if (0 == strcasecmp(optarg, "DATA")) {
                    content_type = CCN_CONTENT_DATA;
                    break;
                }
                if (0 == strcasecmp(optarg, "ENCR")) {
                    content_type = CCN_CONTENT_ENCR;
                    break;
                }
                if (0 == strcasecmp(optarg, "GONE")) {
                    content_type = CCN_CONTENT_GONE;
                    break;
                }
                if (0 == strcasecmp(optarg, "KEY")) {
                    content_type = CCN_CONTENT_KEY;
                    break;
                }
                if (0 == strcasecmp(optarg, "LINK")) {
                    content_type = CCN_CONTENT_LINK;
                    break;
                }
                if (0 == strcasecmp(optarg, "NACK")) {
                    content_type = CCN_CONTENT_NACK;
                    break;
                }
                content_type = atoi(optarg);
                if (content_type > 0 && content_type <= 0xffffff)
                    break;
                fprintf(stderr, "Unknown content type %s\n", optarg);
                /* FALLTHRU */
            default:
            case 'h':
                usage(progname);
                break;
        }
    }
    argc -= optind;
    argv += optind;
    if (argv[0] == NULL)
        usage(progname);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, argv[0]);
    if (res < 0) {
        fprintf(stderr, "%s: bad ccn URI: %s\n", progname, argv[0]);
        exit(1);
    }
    if (argv[1] != NULL)
        fprintf(stderr, "%s warning: extra arguments ignored\n", progname);
    
    /* Preserve the original prefix, in case we add versioning,
     * but trim it down if requested for the interest filter registration
     */
    pname = ccn_charbuf_create();
    ccn_charbuf_append(pname, name->buf, name->length);
    if (prefixcomps >= 0) {
        res = ccn_name_chop(pname, NULL, prefixcomps);
        if (res < 0) {
            fprintf(stderr, "%s: unable to trim name to %d component%s.\n",
                    progname, prefixcomps, prefixcomps == 1 ? "" : "s");
            exit(1);
        }
    }
    /* Connect to ccnd */
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }

    /* Read the actual user data from standard input */
    buf = calloc(1, blocksize);
    read_res = read_full(0, buf, blocksize);
    if (read_res < 0) {
        perror("read");
        read_res = 0;
        status = 1;
    }
        
    /* Tack on the version component if requested */
    if (versioned) {
        res = ccn_create_version(ccn, name, CCN_V_REPLACE | CCN_V_NOW | CCN_V_HIGH, 0, 0);
        if (res < 0) {
            fprintf(stderr, "%s: ccn_create_version() failed\n", progname);
            exit(1);
        }
        if (postver != NULL) {
            res = ccn_name_from_uri(name, postver);
            if (res < 0) {
                fprintf(stderr, "-V %s: invalid name suffix\n", postver);
                exit(0);
            }
        }
    }
    temp = ccn_charbuf_create();
    
    /* Ask for a FinalBlockID if appropriate. */
    if (setfinal)
        sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    
    if (res < 0) {
        fprintf(stderr, "Failed to create signed_info (res == %d)\n", res);
        exit(1);
    }
    
    /* Set content type */
    sp.type = content_type;
    
    /* Set freshness */
    if (expire >= 0) {
        if (sp.template_ccnb == NULL) {
            sp.template_ccnb = ccn_charbuf_create();
            ccnb_element_begin(sp.template_ccnb, CCN_DTAG_SignedInfo);
        }
        else if (sp.template_ccnb->length > 0) {
            sp.template_ccnb->length--;
        }
        ccnb_tagged_putf(sp.template_ccnb, CCN_DTAG_FreshnessSeconds, "%ld", expire);
        sp.sp_flags |= CCN_SP_TEMPL_FRESHNESS;
        ccnb_element_end(sp.template_ccnb);
    }
    
    /* Set key locator, if supplied */
    if (key_uri != NULL) {
        struct ccn_charbuf *c = ccn_charbuf_create();
        res = ccn_name_from_uri(c, key_uri);
        if (res < 0) {
            fprintf(stderr, "%s is not a valid ccnx URI\n", key_uri);
            exit(1);
        }
        if (sp.template_ccnb == NULL) {
            sp.template_ccnb = ccn_charbuf_create();
            ccnb_element_begin(sp.template_ccnb, CCN_DTAG_SignedInfo);
        }
        else if (sp.template_ccnb->length > 0) {
            sp.template_ccnb->length--;
        }
        ccnb_element_begin(sp.template_ccnb, CCN_DTAG_KeyLocator);
        ccnb_element_begin(sp.template_ccnb, CCN_DTAG_KeyName);
        ccn_charbuf_append(sp.template_ccnb, c->buf, c->length);
        ccnb_element_end(sp.template_ccnb);
        ccnb_element_end(sp.template_ccnb);
        sp.sp_flags |= CCN_SP_TEMPL_KEY_LOCATOR;
        ccnb_element_end(sp.template_ccnb);
        ccn_charbuf_destroy(&c);
    }

    if (extopt != NULL && extopt->length > 0) {
        if (sp.template_ccnb == NULL) {
            sp.template_ccnb = ccn_charbuf_create();
            ccnb_element_begin(sp.template_ccnb, CCN_DTAG_SignedInfo);
        }
        else if (sp.template_ccnb->length > 0) {
            sp.template_ccnb->length--;
        }
        ccnb_append_tagged_blob(sp.template_ccnb, CCN_DTAG_ExtOpt,
                                extopt->buf, extopt->length);
        sp.sp_flags |= CCN_SP_TEMPL_EXT_OPT;
        ccnb_element_end(sp.template_ccnb);
    }
    
    /* Create the signed content object, ready to go */
    temp->length = 0;
    res = ccn_sign_content(ccn, temp, name, &sp, buf, read_res);
    if (res != 0) {
        fprintf(stderr, "Failed to encode ContentObject (res == %d)\n", res);
        exit(1);
    }
    if (read_res == blocksize) {
        read_res = read_full(0, buf, 1);
        if (read_res == 1) {
            fprintf(stderr, "%s: warning - truncated data\n", argv[0]);
            status = 1;
        }
    }
    free(buf);
    buf = NULL;
    if (force) {
        /* At user request, send without waiting to see an interest */
        res = ccn_put(ccn, temp->buf, temp->length);
        if (res < 0) {
            fprintf(stderr, "ccn_put failed (res == %d)\n", res);
            exit(1);
        }
    }
    else {
        in_interest.data = temp;
        /* Set up a handler for interests */
        res = ccn_set_interest_filter(ccn, pname, &in_interest);
        if (res < 0) {
            fprintf(stderr, "Failed to register interest (res == %d)\n", res);
            exit(1);
        }
        res = ccn_run(ccn, timeout);
        if (in_interest.intdata == 0) {
            if (verbose)
                fprintf(stderr, "Nobody's interested\n");
            exit(1);
        }
    }
    
    if (verbose) {
        struct ccn_charbuf *uri = ccn_charbuf_create();
        uri->length = 0;
        ccn_uri_append(uri, name->buf, name->length, 1);
        printf("wrote %s\n", ccn_charbuf_as_string(uri));
        ccn_charbuf_destroy(&uri);
    }
    ccn_destroy(&ccn);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&pname);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&sp.template_ccnb);
    ccn_charbuf_destroy(&extopt);
    exit(status);
}
