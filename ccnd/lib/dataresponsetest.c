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
        unsigned char *contents;
        size_t	size;
        struct ccn_parsed_ContentObject x;
        struct ccn_indexbuf *components;
    } *d;
};

int actionhandler(struct ccn_closure *selfp,
                  enum ccn_upcall_kind,
                  struct ccn *h,
                  const unsigned char *ccnb,    /* binary-format Interest or ContentObject */
                  size_t ccnb_size,
                  struct ccn_indexbuf *components,
                  int matched_components);

struct handlerstate *create_handler_state(char *dirname);

int
main (int argc, char *argv[]) {
    struct ccn *ccn = NULL;
    char *datadirname = ".";
    struct ccn_closure *action;
    struct ccn_charbuf *namebuf;
    char c;
    int result;

    while ((c = getopt(argc, argv, "d:")) != -1) {
        switch (c) {
        default:
            fprintf(stderr, "%s -d datadirectory\n", argv[0]);
            exit(1);
        case 'd':
            datadirname = optarg;
            break;
        }
    }

    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("ccn_connect");
        exit(1);
    }
    
    action = calloc(1, sizeof(struct ccn_closure));
    action->p = actionhandler;
    action->data = create_handler_state(datadirname);

    namebuf = ccn_charbuf_create();
    if (namebuf == NULL) {
        fprintf(stderr, "ccn_charbuf_create\n");
        exit(1);
    }        

    if (ccn_name_init(namebuf) == -1) {
        fprintf(stderr, "ccn_name_init\n");
        exit(1);
    }

    result = ccn_set_interest_filter(ccn, namebuf, action);
    result = ccn_run(ccn, -1);
    ccn_disconnect(ccn);
    ccn_destroy(&ccn);
    exit(0);
}

int
selectreg(const struct dirent *d) {
    if (strstr(d->d_name, ".ccnb") != NULL) return (1);
    return (0);
}

int
read_file(char *dir, char *name, unsigned char **contents, size_t *sizep) {
    int res;
    int fd;
    struct stat s;
    unsigned char *buf;
    char *path;
    
    path = malloc(strlen(dir) + strlen(name) + 1);
    strcpy(path, dir);
    strcat(path, "/");
    strcat(path, name);

    fd = open(path, O_RDONLY);
    free(path);
    if (fd == -1) {
        return (-1);
    }
    res = fstat(fd, &s);
    if (res == -1) {
        close(fd);
        return (res);
    }

    buf = malloc(s.st_size);
    if (buf == NULL) {
        close(fd);
        return (-1);
    }

    res = read(fd, buf, s.st_size);
    if (res != s.st_size) {
        free(buf);
        close(fd);
        return (-1);
    }
    close(fd);
    *contents = buf;
    *sizep = s.st_size;
    return (0);
}

struct handlerstate *
create_handler_state(char *dirname) {
    struct handlerstate *state;
    int nfiles;
    int i;
    int res;
    int n = 0;
    struct dirent **files;

    nfiles = scandir(dirname, &files, selectreg, NULL);
    state = calloc(sizeof(struct handlerstate), 1);
    state->next = 0;
    state->d = calloc(sizeof(*(state->d)), 1);

    for (i = 0; i < nfiles; i++) {
        res = read_file(dirname, files[n]->d_name, &(state->d[n].contents), &(state->d[n].size));
        if (res == -1) continue;
        state->d[i].components = ccn_indexbuf_create();
        res = ccn_parse_ContentObject(state->d[n].contents, state->d[n].size,
                                      &(state->d[n].x), state->d[n].components);
        if (res < 0) continue;
        fprintf(stderr, "Read content %s\n", files[i]->d_name);
        n++;
        state->d = realloc(state->d, sizeof(*(state->d)) * (n + 1));
    }
    state->count = n;
    return (state);
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
actionhandler(struct ccn_closure *selfp,
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
            fprintf(stderr, "Matched %d components, item %d\n", mc, i);
            if (mc == (components->n - 1)) {
                ccn_put(h, state->d[i].contents, state->d[i].size);
                fprintf(stderr, "Put item %d", i);
                if (i < c - 1) {
                    item = state->d[i];
                    memmove(&(state->d[i]), &(state->d[i+1]), sizeof(item) * ((c - 1) - i));
                    state->d[c - 1] = item;
                    fprintf(stderr, " moved to item %d", c - 1);
                }
                fprintf(stderr, "\n");
                return (1);
            }
        }
        return(0);
    }
    return (-1);
}
