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
#define DATA(ht, p) ((void *)((p) + 1))
#define KEY(ht, p) ((unsigned char *)((p) + 1) + ht->item_size)

struct hashtb {
    struct node *onebucket;
    size_t item_size;
    int n;
    int refcount;
    struct hashtb_param param;
};

struct hashtb *
hashtb_create(size_t item_size, const struct hashtb_param *param)
{
    struct hashtb *ht;
    ht = calloc(1, sizeof(*ht));
    if (ht != NULL) {
        ht->item_size = item_size;
        ht->n = 0;
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
    if (key == NULL)
        return(NULL);
    for (p = ht->onebucket; p != NULL; p = p->link) {
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

static char hashtb_magic[] = "HTB";

#define MAX_ENUMERATORS 30
struct hashtb_enumerator *
hashtb_start(struct hashtb *ht, struct hashtb_enumerator *hte)
{
    hte->priv[1] = hashtb_magic;
    hte->datasize = ht->item_size;
    hte->ht = ht;
    setpos(hte, &(ht->onebucket));
    ht->refcount++;
    if (ht->refcount > MAX_ENUMERATORS)
        abort(); /* probably somebody is missing a call to hashtb_end() */
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
    if (ht->refcount == 0)
        /* XXX - do deferred deallocation */;
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
