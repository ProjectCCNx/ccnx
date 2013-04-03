/**
 * @file ccn/aeskeystoreasn1.h
 * 
 * ASN.1 support routines for dealing with AES (symmetric) keystore
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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

#ifndef CCN_AESKEYSTOREASN1_DEFINED
#define CCN_AESKEYSTOREASN1_DEFINED

#include <openssl/asn1.h>
#include <openssl/asn1t.h>
#include <openssl/safestack.h>

typedef struct AESKeystore_info_st {
    ASN1_INTEGER *version;
    ASN1_OBJECT *algorithm_oid;
    ASN1_OCTET_STRING *encrypted_key;
} AESKeystore_info;

DECLARE_ASN1_FUNCTIONS(AESKeystore_info)

int i2d_AESKeystore_fp(FILE *fp, AESKeystore_info *aki);
AESKeystore_info *d2i_AESKeystore_fp(FILE *fp, AESKeystore_info *aki);

#endif
