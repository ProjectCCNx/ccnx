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

typedef struct MP_info_st {
    ASN1_INTEGER *node;
    STACK_OF(ASN1_OCTET_STRING) *hashes;
} MP_info;

DECLARE_ASN1_FUNCTIONS(MP_info)

#endif
