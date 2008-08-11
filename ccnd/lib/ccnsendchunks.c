/*
 * Injects chunks of data from stdin into ccn
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>
#include <ccn/keystore.h>

struct mydata {
    unsigned char *firstseen;
    size_t firstseensize;
    int nseen;
};

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind != CCN_UPCALL_CONTENT || md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    if (md->firstseen == NULL) {
        md->firstseen = calloc(1, ccnb_size);
        memcpy(md->firstseen, info->content_ccnb, ccnb_size);
        md->firstseensize = ccnb_size;
    }
    else if (md->firstseensize == ccnb_size &&
             0 == memcmp(md->firstseen, ccnb, ccnb_size)) {
        selfp->data = NULL;
        return(CCN_UPCALL_RESULT_ERR);
    }
    md->nseen++;
    fwrite(ccnb, ccnb_size, 1, stdout);
    return(CCN_UPCALL_RESULT_REEXPRESS);
}

enum ccn_upcall_res
incoming_interest(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind != CCN_UPCALL_CONTENT || md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    if (md->firstseen == NULL) {
        md->firstseen = calloc(1, ccnb_size);
        memcpy(md->firstseen, info->content_ccnb, ccnb_size);
        md->firstseensize = ccnb_size;
    }
    else if (md->firstseensize == ccnb_size &&
             0 == memcmp(md->firstseen, ccnb, ccnb_size)) {
        selfp->data = NULL;
        return(CCN_UPCALL_RESULT_ERR);
    }
    md->nseen++;
    fwrite(ccnb, ccnb_size, 1, stdout);
    return(CCN_UPCALL_RESULT_REEXPRESS);
}

ssize_t
read_full(int fd, void *buf, size_t size)
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

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *root = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *authenticator = NULL;
    struct ccn_keystore *keystore = NULL;
    int i;
    int status = 0;
    int res;
    ssize_t read_res;
    unsigned char *buf = NULL;
    
    if (argv[1] == NULL) {
        fprintf(stderr,
                "%s: Chops stdin into 1K blocks and sends them "
                "as consecutively numbered ContentObjects "
                "under the given uri\n", argv[0]);
        exit(1);
    }
    else {
        name = ccn_charbuf_create();
        res = ccn_name_from_uri(name, argv[1]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[1]);
            exit(1);
        }
        if (argv[2] != NULL)
            fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    }

    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    
    
    buf = calloc(1, 1024);
    root = name;
    name = ccn_charbuf_create();
    temp = ccn_charbuf_create();
    authenticator = ccn_charbuf_create();
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
    // XXX - fix this - should check to see whether stuff is already published.
    // XXX - fix this - should flow-balance our output
    for (i = 0;; i++) {
        read_res = read_full(0, buf, 1024);
        if (read_res < 0) {
            perror("read");
            read_res = 0;
            status = 1;
        }
        authenticator->length = 0;
        res = ccn_auth_create(authenticator,
                              /*pubkeyid*/NULL, /*publisher_key_id_size*/0,
                              /*datetime*/NULL,
                              /*type*/CCN_CONTENT_LEAF,
                              /*keylocator*/NULL);
        if (res < 0) {
            fprintf(stderr, "Failed to create authenticator (res == %d)\n", res);
            exit(1);
        }
        name->length = 0;
        ccn_charbuf_append(name, root->buf, root->length);
        temp->length = 0;
        ccn_charbuf_putf(temp, "%d", i);
        ccn_name_append(name, temp->buf, temp->length);
        temp->length = 0;
        ccn_charbuf_append(temp, buf, read_res);
        temp->length = 0;
        res = ccn_encode_ContentObject(temp,
                                       name,
                                       authenticator,
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
        ccn_run(ccn, res * 100);
        if (read_res < 1024)
            break;
    }
    
    free(buf);
    buf = NULL;
    ccn_charbuf_destroy(&root);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&authenticator);
    ccn_keystore_destroy(&keystore);
    ccn_destroy(&ccn);
    exit(status);
}
