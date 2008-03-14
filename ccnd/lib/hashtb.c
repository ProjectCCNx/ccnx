/* $Id$ */

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include <ccn/hashtb.h>

/* Initial implementation should be fully functional, but is not fast (0-bit hash!) */

struct node;
struct node {
    struct node* link;
    size_t keysize;
    /* user data follows immediately, followed by key */
};
#define DATA(p) ((void *)((p) + 1))
/* evil reference to ht here */
#define KEY(p) ((unsigned char *)((p) + 1) + ht->item_size)

struct hashtb {
    struct node *onebucket;
    size_t item_size;
    int n;
};

struct hashtb *
hashtb_create(size_t item_size)
{
    struct hashtb *ht;
    ht = calloc(1, sizeof(*ht));
    if (ht != NULL) {
        ht->item_size = item_size;
        ht->n = 0;
    }
    return(ht);
}

void
hashtb_destroy(struct hashtb **htp)
{
    if (*htp != NULL) {
        struct hashtb_enumerator tmp;
        struct hashtb_enumerator *e = hashtb_start(*htp, &tmp);
        while (e->key != NULL)
            hashtb_delete(e);
        free(*htp);
        *htp = NULL;
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
    if (key == NULL) {
        return(NULL);
    }
    for (p = ht->onebucket; p != NULL; p = p->link) {
        if (keysize == p->keysize && 0 == memcmp(key, KEY(p), keysize)) {
            return(DATA(p));
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
        hte->key = KEY(p);
        hte->keysize = p->keysize;
        hte->data = DATA(p);
    }
}

static char hashtb_magic[] = "HTB";

struct hashtb_enumerator *
hashtb_start(struct hashtb *ht, struct hashtb_enumerator *hte)
{
    hte->priv[1] = hashtb_magic;
    hte->datasize = ht->item_size;
    hte->ht = ht;
    setpos(hte, &(ht->onebucket));
    return(hte);
}

void
hashtb_next(struct hashtb_enumerator *hte)
{
    struct node **pp = hte->priv[0];
    if (pp != NULL)
        pp = &((*pp)->link);
    setpos(hte, pp);
}

int
hashtb_seek(struct hashtb_enumerator *hte, const void *key, size_t keysize)
{
    struct node *p = NULL;
    struct hashtb *ht = hte->ht;
    struct node **pp;
    if (key == NULL) {
        setpos(hte, NULL);
        return(-1);
    }
    pp = &(ht->onebucket);
    for (p = *pp; p != NULL; pp = &(p->link), p = p->link) {
        if (keysize == p->keysize && 0 == memcmp(key, KEY(p), keysize)) {
            setpos(hte, pp);
            return(HT_OLD_ENTRY);
        }
    }
    p = calloc(1, sizeof(*p) + ht->item_size + keysize + 1);
    if (p == NULL) {
        setpos(hte, NULL);
        return(-1);
    }
    memcpy(KEY(p), key, keysize);
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
    if ((p != NULL) && (hte->priv[1] == hashtb_magic) && KEY(p) == hte->key) {
        *pp = p->link;
        free(p);
        hte->ht->n -= 1;
    }
    setpos(hte, pp);
}


