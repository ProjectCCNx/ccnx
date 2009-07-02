/*
 * ccn/merklepathasn1.h
 * 
 * ASN.1 support routines for dealing with the Merkle paths
 * encapsulated in the digest info
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

