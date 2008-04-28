/* $Id$ */

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include <ccn/hashtb.h>

struct node;
struct node {
    struct node* link;
    size_t hash;
    size_t keysize;
    size_t extrasize;
    /* user data follows immediately, followed by key */
};
#define DATA(ht, p) ((void *)((p) + 1))
#define KEY(ht, p) ((unsigned char *)((p) + 1) + ht->item_size)

struct hashtb {
    struct node **bucket;
    size_t item_size;           /* Size of client's per-entry data */
    unsigned n_buckets;
    int n;                      /* Number of entries */
    int refcount;               /* Number of open enumerators */
    struct hashtb_param param;
};

size_t
hashtb_hash(const unsigned char *key, size_t key_size)
{
    size_t h;
    size_t i;
    for (h = key_size + 23, i = 0; i < key_size; i++)
        h = ((h << 6) ^ (h >> 27)) + key[i];
    return(h);
}

struct hashtb *
hashtb_create(size_t item_size, const struct hashtb_param *param)
{
    struct hashtb *ht;
    ht = calloc(1, sizeof(*ht));
    if (ht != NULL) {
        ht->item_size = item_size;
        ht->n = 0;
        ht->n_buckets = 7;
        ht->bucket = calloc(ht->n_buckets, sizeof(ht->bucket[0]));
        if (param != NULL)
            ht->param = *param;
    }
    return(ht);
}

void *
hashtb_get_param(struct hashtb *ht, struct hashtb_param *param)
{
    if (param != NULL)
        *param = ht->param;
    return(ht->param.finalize_data);
}

void
hashtb_destroy(struct hashtb **htp)
{
    if (*htp != NULL) {
        struct hashtb_enumerator tmp;
        struct hashtb_enumerator *e = hashtb_start(*htp, &tmp);
        while (e->key != NULL)
            hashtb_delete(e);
        hashtb_end(&tmp);
        if ((*htp)->refcount == 0) {
            free((*htp)->bucket);
            free(*htp);
            *htp = NULL;
        }
        else
            abort(); /* perhaps a bit brutal... */
    }
}

int
hashtb_n(struct hashtb *ht)
{
    return(ht->n);
}

void *
hashtb_lookup(struct hashtb *ht, const void *key, size_t keysize)
{
    struct node *p;
    size_t h;
    if (key == NULL)
        return(NULL);
    h = hashtb_hash(key, keysize);
    for (p = ht->bucket[h % ht->n_buckets]; p != NULL; p = p->link) {
        if (p->hash < h)
            continue;
        if (p->hash > h)
            break;
        if (keysize == p->keysize && 0 == memcmp(key, KEY(ht, p), keysize)) {
            return(DATA(ht, p));
        }
    }
    return(NULL);
}

static void
setpos(struct hashtb_enumerator *hte, struct node **pp)
{
    struct hashtb *ht = hte->ht;
    struct node *p = NULL;
    hte->priv[0] = pp;
    if (pp != NULL)
        p = *pp;
    if (p == NULL) {
        hte->key = NULL;
        hte->keysize = 0;
        hte->data = NULL;
    }
    else {
        hte->key = KEY(ht, p);
        hte->keysize = p->keysize;
        hte->data = DATA(ht, p);
    }
}

static struct node **
scan_buckets(struct hashtb *ht, unsigned b)
{
    for (; b < ht->n_buckets; b++)
        if (ht->bucket[b] != NULL)
            return &(ht->bucket[b]);
    return(NULL);
}

static char hashtb_magic[] = "HTB";

#define MAX_ENUMERATORS 30
struct hashtb_enumerator *
hashtb_start(struct hashtb *ht, struct hashtb_enumerator *hte)
{
    hte->priv[1] = hashtb_magic;
    hte->datasize = ht->item_size;
    hte->ht = ht;
    ht->refcount++;
    if (ht->refcount > MAX_ENUMERATORS)
        abort(); /* probably somebody is missing a call to hashtb_end() */
    setpos(hte, scan_buckets(ht, 0));
    return(hte);
}

void
hashtb_end(struct hashtb_enumerator *hte)
{
    struct hashtb *ht = hte->ht;
    if (hte->priv[1] != hashtb_magic || ht->refcount <= 0) abort();
    hte->priv[0] = 0;
    hte->priv[1] = 0;
    ht->refcount--;
    if (ht->refcount == 0) {
        /* XXX - do deferred deallocation */;
        if (ht->n > ht->n_buckets)
            hashtb_rehash(ht, ht->n + (ht->n >> 4)); // XXX - beat on rehash for now
    }
}

void
hashtb_next(struct hashtb_enumerator *hte)
{
    struct node **pp = hte->priv[0];
    struct node **ppp;
    if (pp != NULL) {
        ppp = pp;
        pp = &((*pp)->link);
        if (*pp == NULL)
           pp = scan_buckets(hte->ht, ((*ppp)->hash % hte->ht->n_buckets) + 1);
    }
    setpos(hte, pp);
}

int
hashtb_seek(struct hashtb_enumerator *hte, const void *key, size_t keysize)
{
    struct node *p = NULL;
    struct hashtb *ht = hte->ht;
    struct node **pp;
    size_t h;
    if (key == NULL) {
        setpos(hte, NULL);
        return(-1);
    }
    if (ht->refcount == 1 && ht->n > ht->n_buckets * 3) {
        ht->refcount--;
        hashtb_rehash(ht, 2 * ht->n + 1);
        ht->refcount++;
    }
    h = hashtb_hash(key, keysize);
    pp = &(ht->bucket[h % ht->n_buckets]);
    for (p = *pp; p != NULL; pp = &(p->link), p = p->link) {
        if (p->hash < h)
            continue;
        if (p->hash > h)
            break;
        if (keysize == p->keysize && 0 == memcmp(key, KEY(ht, p), keysize)) {
            setpos(hte, pp);
            return(HT_OLD_ENTRY);
        }
    }
    p = calloc(1, sizeof(*p) + ht->item_size + keysize + 1);
    if (p == NULL) {
        setpos(hte, NULL);
        return(-1);
    }
    memcpy(KEY(ht, p), key, keysize);
    p->hash = h;
    p->keysize = keysize;
    p->link = *pp;
    *pp = p;
    hte->ht->n += 1;
    setpos(hte, pp);
    return(HT_NEW_ENTRY);
}

void
hashtb_delete(struct hashtb_enumerator *hte)
{
    struct hashtb *ht = hte->ht;
    struct node **pp = hte->priv[0];
    struct node *p = *pp;
    if ((p != NULL) && (hte->priv[1] == hashtb_magic) && KEY(ht, p) == hte->key) {
        *pp = p->link;
        hte->ht->n -= 1;
        if (ht->refcount == 1) {
            hashtb_finalize_proc f = ht->param.finalize;
            if (f != NULL)
                (*f)(hte);
            free(p);
        }
        /* XXX - fix leak! */
        setpos(hte, pp);
    }
}

void
hashtb_rehash(struct hashtb *ht, unsigned n_buckets)
{
    struct node **bucket = NULL;
    struct node **pp;
    struct node *p;
    struct node *q;
    size_t h;
    unsigned i;
    unsigned b;
    if (ht->refcount != 0 || n_buckets < 1 || n_buckets == ht->n_buckets)
        return;
    bucket = calloc(n_buckets, sizeof(bucket[0]));
    if (bucket == NULL) return; /* ENOMEM */
    for (i = 0; i < ht->n_buckets; i++) {
        for (p = ht->bucket[i]; p != NULL; p = q) {
            q = p->link;
            h = p->hash;
            b = h % n_buckets;
            for (pp = &bucket[b]; *pp != NULL && ((*pp)->hash < h); pp = &((*pp)->link))
                continue;
            p->link = *pp;
            *pp = p;
        }
    }
    free(ht->bucket);
    ht->bucket = bucket;
    ht->n_buckets = n_buckets;
}

