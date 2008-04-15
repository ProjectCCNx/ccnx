#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>

#include <ccn/ccn.h>

struct handlerstate {
    int next;
    int count;
    struct handlerstateitem {
        char *filename;
        unsigned char *contents;
        size_t	size;
        struct ccn_parsed_ContentObject x;
        struct ccn_indexbuf *components;
    } *d;
};

int interest_handler(struct ccn_closure *selfp,
                  enum ccn_upcall_kind,
                  struct ccn *h,
                  const unsigned char *ccnb,    /* binary-format Interest or ContentObject */
                  size_t ccnb_size,
                  struct ccn_indexbuf *components,
                  int matched_components);

int
main (int argc, char *argv[]) {
    struct ccn *ccn = NULL;
    struct ccn_closure *action;
    struct ccn_charbuf *namebuf;
    struct handlerstate *state;
    char *filename;
    char rawbuf[1024 * 1024];
    ssize_t rawlen;
    int i, n, res;

    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("ccn_connect");
        exit(1);
    }
    
    state = calloc(1, sizeof(struct handlerstate));
    action = calloc(1, sizeof(struct ccn_closure));
    action->p = interest_handler;

    n = 0;
    for (i = 1; i < argc; i++) {
        int fd = -1;
        if (fd != -1) close(fd);
        filename = argv[i];
        fprintf(stderr, "Processing %s ", filename);
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
        state->d = realloc(state->d, (n + 1) * sizeof(*(state->d)));
        if (state->d == NULL) {
            perror("realloc failed");
            exit(1);
        }
        if (state->d[n].components == NULL) {
            state->d[n].components = ccn_indexbuf_create();
        }
        res = ccn_parse_ContentObject((unsigned char *)rawbuf, rawlen, &(state->d[n].x), state->d[n].components);
        if (res < 0) {
            fprintf(stderr, "- skipping: Not a ContentObject\n");
            continue;
        }
        fprintf(stderr, "- ok\n");
        state->d[n].filename = filename;
        state->d[n].contents = malloc(rawlen);
        state->d[n].size = rawlen;
        memcpy(state->d[n].contents, rawbuf, rawlen);
        n++;
    }
    state->count = n;
    action->data = state;

    namebuf = ccn_charbuf_create();
    if (namebuf == NULL) {
        fprintf(stderr, "ccn_charbuf_create\n");
        exit(1);
    }        

    if (ccn_name_init(namebuf) == -1) {
        fprintf(stderr, "ccn_name_init\n");
        exit(1);
    }

    res = ccn_set_interest_filter(ccn, namebuf, action);
    res = ccn_run(ccn, -1);
    ccn_disconnect(ccn);
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

int
interest_handler(struct ccn_closure *selfp,
                 enum ccn_upcall_kind upcall_kind,
                 struct ccn *h,
                 const unsigned char *ccnb,    /* binary-format Interest or ContentObject */
                 size_t ccnb_size,
                 struct ccn_indexbuf *components,
                 int matched_components) {

    int i, c, mc;
    struct handlerstateitem item;
    struct handlerstate *state;

    state = selfp->data;
    switch(upcall_kind) {
    case CCN_UPCALL_FINAL:
        fprintf(stderr, "Upcall final\n");
        return (0);
    case CCN_UPCALL_CONTENT:
        fprintf(stderr, "Upcall content\n");
        return (0);
    case CCN_UPCALL_CONSUMED_INTEREST:
        fprintf(stderr, "Upcall consumed interest\n");
        return (-1); /* no data */
    case CCN_UPCALL_INTEREST:
        c = state->count;
        for (i = 0; i < c; i++) {
            mc = match_components((unsigned char *)ccnb, components,
                                  state->d[i].contents, state->d[i].components);
            if (mc == (components->n - 1)) {
                ccn_put(h, state->d[i].contents, state->d[i].size);
                fprintf(stderr, "Sending %s, matched %d components\n", state->d[i].filename, mc);
                if (i < c - 1) {
                    item = state->d[i];
                    memmove(&(state->d[i]), &(state->d[i+1]), sizeof(item) * ((c - 1) - i));
                    state->d[c - 1] = item;
                }
                return (1);
            }
        }
        return(0);
    }
    return (-1);
}
