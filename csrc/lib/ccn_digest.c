/**
 * @file ccn_digest.c
 * @brief Support for creating digests.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
#include <stdlib.h>
#include <openssl/sha.h>
#include <ccn/digest.h>

struct ccn_digest {
    enum ccn_digest_id id;
    unsigned short sz;
    short ready;
    SHA256_CTX sha256_ctx;
};

struct ccn_digest *
ccn_digest_create(enum ccn_digest_id id)
{
    unsigned sz = 0;
    struct ccn_digest *ans;
    switch (id) {
        case CCN_DIGEST_DEFAULT:
        case CCN_DIGEST_SHA256:
            id = CCN_DIGEST_SHA256;
            sz = 32;
            break;
        default:
            return(NULL);
    }
    ans = calloc(1, sizeof(*ans));
    if (ans != NULL) {
        ans->id = id;
        ans->sz = sz;
    }
    return(ans);
}

void
ccn_digest_destroy(struct ccn_digest **pd)
{
    if (*pd != NULL) {
        free(*pd);
        *pd = NULL;
    }
}

enum ccn_digest_id
ccn_digest_getid(struct ccn_digest *d)
{
    return(d->id);
}

size_t
ccn_digest_size(struct ccn_digest *d)
{
    return(d->sz);
}

void
ccn_digest_init(struct ccn_digest *d)
{
    SHA256_Init(&d->sha256_ctx);
    d->ready = 1;
}

int
ccn_digest_update(struct ccn_digest *d, const void *data, size_t size)
{
    int res;
    if (d->ready != 1)
        return(-1);
    res = SHA256_Update(&d->sha256_ctx, data, size);
    return((res == 1) ? 0 : -1);
}

int
ccn_digest_final(struct ccn_digest *d, unsigned char *result, size_t digest_size)
{
    int res;
    if (digest_size != d->sz) return(-1);
    if (d->ready != 1)
        return(-1);
    res = SHA256_Final(result, &d->sha256_ctx);
    d->ready = 0;
    return((res == 1) ? 0 : -1);
}
