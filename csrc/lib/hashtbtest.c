/**
 * @file hashtbtest.c
 * Try out some hash table calls (ccn/hashtb).
 *
 * A CCNx program.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <ccn/hashtb.h>

static void
Dump(struct hashtb *h)
{
    struct hashtb_enumerator eee;
    struct hashtb_enumerator *e = &eee;
    printf("------- %d ------\n", hashtb_n(h));
    for (hashtb_start(h, e); e->key != NULL; hashtb_next(e)) {
        if (e->extsize != 1 || strlen(e->key) != e->keysize)
            abort();
        printf("%u: %s\n", ((unsigned *)e->data)[0], (const char *)e->key);
    }
    hashtb_end(e);
}

static void
finally(struct hashtb_enumerator *e)
{
    char *who = hashtb_get_param(e->ht, NULL);
    fprintf(stderr, "%s deleting %s\n", who, (const char *)e->key);
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
            buf[--i] = 0;
        if (buf[0] == '?') {
            Dump(h);
        }
        else if (buf[0] == '-') {
            int res;
            unsigned *v;
            v = hashtb_lookup(h, buf+1, i-1);
            if (v != NULL)
                printf("(%u)", *v);
            res = hashtb_seek(e, buf+1, i-1, 1);
            hashtb_delete(e);
            printf("%d\n", res == HT_OLD_ENTRY);
            }
        else {
            hashtb_seek(e, buf, i, 1);
            ((unsigned *)(e->data))[0] += 1;
        }
    }
    hashtb_end(e);
    hashtb_destroy(&h);
    return(0);
}
