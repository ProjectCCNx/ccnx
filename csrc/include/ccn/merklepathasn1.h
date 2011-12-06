/**
 * @file ccn/merklepathasn1.h
 * 
 * ASN.1 support routines for dealing with the Merkle paths
 * encapsulated in the digest info.
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

#ifndef CCN_MERKLEPATHASN1_DEFINED
#define CCN_MERKLEPATHASN1_DEFINED

#include <openssl/asn1.h>
#include <openssl/asn1t.h>
#include <openssl/safestack.h>

DECLARE_STACK_OF(ASN1_OCTET_STRING)
/* 
 * we have to do this ourselves, since the OPENSSL code doesn't
 * use any stacks of ASN1_OCTET_STRING, so it is not predfined in safestack.h
 */
#define sk_ASN1_OCTET_STRING_new(cmp) SKM_sk_new(ASN1_OCTET_STRING, (cmp))
#define sk_ASN1_OCTET_STRING_new_null() SKM_sk_new_null(ASN1_OCTET_STRING)
#define sk_ASN1_OCTET_STRING_free(st) SKM_sk_free(ASN1_OCTET_STRING, (st))
#define sk_ASN1_OCTET_STRING_num(st) SKM_sk_num(ASN1_OCTET_STRING, (st))
#define sk_ASN1_OCTET_STRING_value(st, i) SKM_sk_value(ASN1_OCTET_STRING, (st), (i))
#define sk_ASN1_OCTET_STRING_set(st, i, val) SKM_sk_set(ASN1_OCTET_STRING, (st), (i), (val))
#define sk_ASN1_OCTET_STRING_zero(st) SKM_sk_zero(ASN1_OCTET_STRING, (st))
#define sk_ASN1_OCTET_STRING_push(st, val) SKM_sk_push(ASN1_OCTET_STRING, (st), (val))
#define sk_ASN1_OCTET_STRING_unshift(st, val) SKM_sk_unshift(ASN1_OCTET_STRING, (st), (val))
#define sk_ASN1_OCTET_STRING_find(st, val) SKM_sk_find(ASN1_OCTET_STRING, (st), (val))
#define sk_ASN1_OCTET_STRING_find_ex(st, val) SKM_sk_find_ex(ASN1_OCTET_STRING, (st), (val))
#define sk_ASN1_OCTET_STRING_delete(st, i) SKM_sk_delete(ASN1_OCTET_STRING, (st), (i))
#define sk_ASN1_OCTET_STRING_delete_ptr(st, ptr) SKM_sk_delete_ptr(ASN1_OCTET_STRING, (st), (ptr))
#define sk_ASN1_OCTET_STRING_insert(st, val, i) SKM_sk_insert(ASN1_OCTET_STRING, (st), (val), (i))
#define sk_ASN1_OCTET_STRING_set_cmp_func(st, cmp) SKM_sk_set_cmp_func(ASN1_OCTET_STRING, (st), (cmp))
#define sk_ASN1_OCTET_STRING_dup(st) SKM_sk_dup(ASN1_OCTET_STRING, st)
#define sk_ASN1_OCTET_STRING_pop_free(st, free_func) SKM_sk_pop_free(ASN1_OCTET_STRING, (st), (free_func))
#define sk_ASN1_OCTET_STRING_shift(st) SKM_sk_shift(ASN1_OCTET_STRING, (st))
#define sk_ASN1_OCTET_STRING_pop(st) SKM_sk_pop(ASN1_OCTET_STRING, (st))
#define sk_ASN1_OCTET_STRING_sort(st) SKM_sk_sort(ASN1_OCTET_STRING, (st))
#define sk_ASN1_OCTET_STRING_is_sorted(st) SKM_sk_is_sorted(ASN1_OCTET_STRING, (st))

typedef struct MP_info_st {
    ASN1_INTEGER *node;
    STACK_OF(ASN1_OCTET_STRING) *hashes;
} MP_info;

DECLARE_ASN1_FUNCTIONS(MP_info)

#endif
