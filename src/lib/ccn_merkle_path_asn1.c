/*
 * Generate implementation of ASN.1 functions to deal with Merkle paths
 */

#include <ccn/merklepathasn1.h>

ASN1_SEQUENCE(MP_info) = {
    ASN1_SIMPLE(MP_info, node, ASN1_INTEGER),
    ASN1_SEQUENCE_OF(MP_info, hashes, ASN1_OCTET_STRING)
} ASN1_SEQUENCE_END(MP_info)

IMPLEMENT_ASN1_FUNCTIONS(MP_info)


