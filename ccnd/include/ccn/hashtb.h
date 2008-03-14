/* $Id$ */
#include <stddef.h>

struct hashtb;

struct hashtb *
hashtb_create(size_t item_size);

void
hashtb_destroy(struct hashtb **);

int
hashtb_n(struct hashtb *ht);

/*
 * hashtb_lookup: Find an item
 * Keys are arbitrary data of specified length.
 * Returns NULL if not found, or a pointer to the item's data.
 */
void *
hashtb_lookup(struct hashtb *ht, const void *key, size_t keysize);

/* The client owns the memory for an enumerator, normally in a local. */ 
struct hashtb_enumerator {
    struct hashtb *ht;
    const void *key;
    size_t keysize;
    void *data;
    size_t datasize;
    void *priv[2];
};

/*
 * hashtb_start: initializes enumerator to first table entry
 * Order of enumeration is arbitrary.
 * Must do this before using the enumerator for anything else.
 * Returns second argument.
 */
struct hashtb_enumerator *
hashtb_start(struct hashtb *, struct hashtb_enumerator *);

void
hashtb_next(struct hashtb_enumerator *);

/*
 * hashtb_seek: Find or add an item
 * returns 0 if entry existed before, 1 if newly added,
 *        -1 for a fatal error (ENOMEM or key==NULL).
 */
int
hashtb_seek(struct hashtb_enumerator *hte, const void *key, size_t keysize);
#define HT_OLD_ENTRY 0
#define HT_NEW_ENTRY 1

void
hashtb_delete(struct hashtb_enumerator *);
