#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <ccn/hashtb.h>

void
Dump(struct hashtb *h)
{
    struct hashtb_enumerator eee;
    struct hashtb_enumerator *e = &eee;
    printf("------- %d ------\n", hashtb_n(h));
    for (hashtb_start(h, e); e->key != NULL; hashtb_next(e)) {
        printf("%u: %s\n", ((unsigned *)e->data)[0], (const char *)e->key);
    }
    hashtb_end(e);
}

void
finally(struct hashtb_enumerator *e)
{
    fprintf(stderr, "%s deleting %s\n", hashtb_get_param(e->ht, NULL), (const char *)e->key);
}

int
main(int argc, char **argv)
{
    char buf[1024] = {0};
    struct hashtb_param p = { &finally, argv[1]};
    struct hashtb *h = hashtb_create(sizeof(unsigned *), p.finalize_data ? &p : NULL);
    struct hashtb_enumerator eee;
    struct hashtb_enumerator *e = hashtb_start(h, &eee);
    while (fgets(buf, sizeof(buf), stdin)) {
        int i = strlen(buf);
        if (i > 0 && buf[i-1] == '\n')
            buf[i-1] = 0;
        if (buf[0] == '?') {
            Dump(h);
        }
        else if (buf[0] == '-') {
            int res;
            unsigned *v;
            v = hashtb_lookup(h, buf+1, i-1);
            if (v != NULL)
                printf("(%u)", *v);
            res = hashtb_seek(e, buf+1, i-1);
            hashtb_delete(e);
            printf("%d\n", res == HT_OLD_ENTRY);
            }
        else {
            hashtb_seek(e, buf, i);
            ((unsigned *)(e->data))[0] += 1;
        }
    }
    hashtb_end(e);
    hashtb_destroy(&h);
    return(0);
}
