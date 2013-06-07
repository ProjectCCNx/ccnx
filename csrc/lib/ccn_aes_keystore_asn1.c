/**
 * @file ccn_aes_keystore_asn1.c
 * @brief Framework to generate implementation of ASN.1 functions to deal with AES keystores
 * 
 * Part of the CCNx C Library.
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

#include <ccn/aeskeystoreasn1.h>

ASN1_SEQUENCE(AESKeystore_info) = {
    ASN1_SIMPLE(AESKeystore_info, version, ASN1_INTEGER),
    ASN1_SIMPLE(AESKeystore_info, algorithm_oid, ASN1_OBJECT),
    ASN1_SIMPLE(AESKeystore_info, encrypted_key, ASN1_OCTET_STRING)
} ASN1_SEQUENCE_END(AESKeystore_info)

IMPLEMENT_ASN1_FUNCTIONS(AESKeystore_info)

int i2d_AESKeystore_fp(FILE *fp, AESKeystore_info *aki) 
{
    return ASN1_item_i2d_fp(ASN1_ITEM_rptr(AESKeystore_info), fp, aki);
}

AESKeystore_info *d2i_AESKeystore_fp(FILE *fp, AESKeystore_info *aki)
{
    return ASN1_item_d2i_fp(ASN1_ITEM_rptr(AESKeystore_info), fp, aki);
}
