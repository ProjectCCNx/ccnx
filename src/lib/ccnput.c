/*
 * Injects one chunk of data from stdin into ccn
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
                "%s [-h] [-v] [-x freshness_seconds] [-t type] ccn:/some/place\n"
                " Reads data from stdin and sends it to the local ccnd "
                "as a single ContentObject "
                "under the given URI\n", progname);
        exit(1);
}


enum ccn_upcall_res
incoming_interest(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    /*
     * We only have one ContentObject to send, so we'll just send whether or
     * not we see an interest.  We still should set up the handler, though,
     * or the local ccnd would be perfectly justified in
     * dropping our precious bits on the floor.
     */
    return(CCN_UPCALL_RESULT_OK);
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
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *keylocator = NULL;
    struct ccn_keystore *keystore = NULL;
    long expire = -1;
    int versioned = 0;
    size_t blocksize = 8*1024;
    int status = 0;
    int res;
    ssize_t read_res;
    unsigned char *buf = NULL;
    enum ccn_content_type content_type = CCN_CONTENT_DATA;
    struct ccn_closure in_interest = {.p=&incoming_interest};
    
    while ((res = getopt(argc, argv, "hlvt:x:")) != -1) {
        switch (res) {
            case 'l':
                // NYI - set FinalBlockID to last comp of name
                break;
            case 'x':
                expire = atol(optarg);
                if (expire <= 0)
                    usage(progname);
                break;
	    case 'v':
                versioned = 1;
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

    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    
    /* Tack on the version component if requested */
    if (versioned) {
        res = ccn_create_version(ccn, name, CCN_V_REPLACE | CCN_V_NOW | CCN_V_HIGH, 0, 0);
        if (res < 0) {
            fprintf(stderr, "%s: ccn_create_version() failed\n", progname);
            exit(1);
        }
        // XXX - might want to print the new URI here.
    }
    buf = calloc(1, blocksize);
    root = name;
    name = ccn_charbuf_create();
    temp = ccn_charbuf_create();
    templ = ccn_charbuf_create();
    signed_info = ccn_charbuf_create();
    keystore = ccn_keystore_create();
    temp->length = 0;
    ccn_charbuf_putf(temp, "%s/.ccn/.ccn_keystore", getenv("HOME"));
    res = ccn_keystore_init(keystore,
                            ccn_charbuf_as_string(temp),
                            "Th1s1sn0t8g00dp8ssw0rd.");
    if (res != 0) {
        printf("Failed to initialize keystore\n");
        exit(1);
    }
    
    name->length = 0;
    ccn_charbuf_append(name, root->buf, root->length);

    /* Set up a handler for interests */
    ccn_set_interest_filter(ccn, name, &in_interest);

    /* Construct a key locator containing the key itself */
    keylocator = ccn_charbuf_create();
    ccn_charbuf_append_tt(keylocator, CCN_DTAG_KeyLocator, CCN_DTAG);
    ccn_charbuf_append_tt(keylocator, CCN_DTAG_Key, CCN_DTAG);
    res = ccn_append_pubkey_blob(keylocator, ccn_keystore_public_key(keystore));
    if (res < 0)
        ccn_charbuf_destroy(&keylocator);
    else {
        ccn_charbuf_append_closer(keylocator); /* </Key> */
        ccn_charbuf_append_closer(keylocator); /* </KeyLocator> */
    }

    read_res = read_full(0, buf, blocksize);
    if (read_res < 0) {
        perror("read");
        read_res = 0;
        status = 1;
    }
    signed_info->length = 0;
    res = ccn_signed_info_create(signed_info,
                                 /*pubkeyid*/ccn_keystore_public_key_digest(keystore),
                                 /*publisher_key_id_size*/ccn_keystore_public_key_digest_length(keystore),
                                 /*datetime*/NULL,
                                 /*type*/content_type,
                                 /*freshness*/ expire,
                                 /*finalblockid*/NULL,
                                 keylocator);
    /* Put the keylocator in the first block only. */
    ccn_charbuf_destroy(&keylocator);
    if (res < 0) {
        fprintf(stderr, "Failed to create signed_info (res == %d)\n", res);
        exit(1);
    }
    name->length = 0;
    ccn_charbuf_append(name, root->buf, root->length);
    temp->length = 0;
    res = ccn_encode_ContentObject(temp,
                                   name,
                                   signed_info,
                                   buf,
                                   read_res,
                                   NULL,
                                   ccn_keystore_private_key(keystore));
    if (res != 0) {
        fprintf(stderr, "Failed to encode ContentObject (res == %d)\n", res);
        exit(1);
    }
    res = ccn_put(ccn, temp->buf, temp->length);
    if (res < 0) {
        fprintf(stderr, "ccn_put failed (res == %d)\n", res);
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
    ccn_charbuf_destroy(&root);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&signed_info);
    ccn_keystore_destroy(&keystore);
    ccn_destroy(&ccn);
    exit(status);
}

