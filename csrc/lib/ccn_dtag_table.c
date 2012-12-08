/**
 * @file ccn_dtag_table.c
 * @brief DTAG table.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
#include <ccn/coding.h>

#define ARRAY_N(arr) (sizeof(arr)/sizeof(arr[0]))
/**
 * See the gen_dtag_table script for help updating these.
 */
static const struct ccn_dict_entry ccn_tagdict[] = {
    {CCN_DTAG_Any, "Any"},
    {CCN_DTAG_Name, "Name"},
    {CCN_DTAG_Component, "Component"},
    {CCN_DTAG_Certificate, "Certificate"},
    {CCN_DTAG_Collection, "Collection"},
    {CCN_DTAG_CompleteName, "CompleteName"},
    {CCN_DTAG_Content, "Content"},
    {CCN_DTAG_SignedInfo, "SignedInfo"},
    {CCN_DTAG_ContentDigest, "ContentDigest"},
    {CCN_DTAG_ContentHash, "ContentHash"},
    {CCN_DTAG_Count, "Count"},
    {CCN_DTAG_Header, "Header"},
    {CCN_DTAG_Interest, "Interest"},
    {CCN_DTAG_Key, "Key"},
    {CCN_DTAG_KeyLocator, "KeyLocator"},
    {CCN_DTAG_KeyName, "KeyName"},
    {CCN_DTAG_Length, "Length"},
    {CCN_DTAG_Link, "Link"},
    {CCN_DTAG_LinkAuthenticator, "LinkAuthenticator"},
    {CCN_DTAG_NameComponentCount, "NameComponentCount"},
    {CCN_DTAG_ExtOpt, "ExtOpt"},
    {CCN_DTAG_RootDigest, "RootDigest"},
    {CCN_DTAG_Signature, "Signature"},
    {CCN_DTAG_Start, "Start"},
    {CCN_DTAG_Timestamp, "Timestamp"},
    {CCN_DTAG_Type, "Type"},
    {CCN_DTAG_Nonce, "Nonce"},
    {CCN_DTAG_Scope, "Scope"},
    {CCN_DTAG_Exclude, "Exclude"},
    {CCN_DTAG_Bloom, "Bloom"},
    {CCN_DTAG_BloomSeed, "BloomSeed"},
    {CCN_DTAG_AnswerOriginKind, "AnswerOriginKind"},
    {CCN_DTAG_InterestLifetime, "InterestLifetime"},
    {CCN_DTAG_Witness, "Witness"},
    {CCN_DTAG_SignatureBits, "SignatureBits"},
    {CCN_DTAG_DigestAlgorithm, "DigestAlgorithm"},
    {CCN_DTAG_BlockSize, "BlockSize"},
    {CCN_DTAG_FreshnessSeconds, "FreshnessSeconds"},
    {CCN_DTAG_FinalBlockID, "FinalBlockID"},
    {CCN_DTAG_PublisherPublicKeyDigest, "PublisherPublicKeyDigest"},
    {CCN_DTAG_PublisherCertificateDigest, "PublisherCertificateDigest"},
    {CCN_DTAG_PublisherIssuerKeyDigest, "PublisherIssuerKeyDigest"},
    {CCN_DTAG_PublisherIssuerCertificateDigest, "PublisherIssuerCertificateDigest"},
    {CCN_DTAG_ContentObject, "ContentObject"},
    {CCN_DTAG_WrappedKey, "WrappedKey"},
    {CCN_DTAG_WrappingKeyIdentifier, "WrappingKeyIdentifier"},
    {CCN_DTAG_WrapAlgorithm, "WrapAlgorithm"},
    {CCN_DTAG_KeyAlgorithm, "KeyAlgorithm"},
    {CCN_DTAG_Label, "Label"},
    {CCN_DTAG_EncryptedKey, "EncryptedKey"},
    {CCN_DTAG_EncryptedNonceKey, "EncryptedNonceKey"},
    {CCN_DTAG_WrappingKeyName, "WrappingKeyName"},
    {CCN_DTAG_Action, "Action"},
    {CCN_DTAG_FaceID, "FaceID"},
    {CCN_DTAG_IPProto, "IPProto"},
    {CCN_DTAG_Host, "Host"},
    {CCN_DTAG_Port, "Port"},
    {CCN_DTAG_MulticastInterface, "MulticastInterface"},
    {CCN_DTAG_ForwardingFlags, "ForwardingFlags"},
    {CCN_DTAG_FaceInstance, "FaceInstance"},
    {CCN_DTAG_ForwardingEntry, "ForwardingEntry"},
    {CCN_DTAG_MulticastTTL, "MulticastTTL"},
    {CCN_DTAG_MinSuffixComponents, "MinSuffixComponents"},
    {CCN_DTAG_MaxSuffixComponents, "MaxSuffixComponents"},
    {CCN_DTAG_ChildSelector, "ChildSelector"},
    {CCN_DTAG_RepositoryInfo, "RepositoryInfo"},
    {CCN_DTAG_Version, "Version"},
    {CCN_DTAG_RepositoryVersion, "RepositoryVersion"},
    {CCN_DTAG_GlobalPrefix, "GlobalPrefix"},
    {CCN_DTAG_LocalName, "LocalName"},
    {CCN_DTAG_Policy, "Policy"},
    {CCN_DTAG_Namespace, "Namespace"},
    {CCN_DTAG_GlobalPrefixName, "GlobalPrefixName"},
    {CCN_DTAG_PolicyVersion, "PolicyVersion"},
    {CCN_DTAG_KeyValueSet, "KeyValueSet"},
    {CCN_DTAG_KeyValuePair, "KeyValuePair"},
    {CCN_DTAG_IntegerValue, "IntegerValue"},
    {CCN_DTAG_DecimalValue, "DecimalValue"},
    {CCN_DTAG_StringValue, "StringValue"},
    {CCN_DTAG_BinaryValue, "BinaryValue"},
    {CCN_DTAG_NameValue, "NameValue"},
    {CCN_DTAG_Entry, "Entry"},
    {CCN_DTAG_ACL, "ACL"},
    {CCN_DTAG_ParameterizedName, "ParameterizedName"},
    {CCN_DTAG_Prefix, "Prefix"},
    {CCN_DTAG_Suffix, "Suffix"},
    {CCN_DTAG_Root, "Root"},
    {CCN_DTAG_ProfileName, "ProfileName"},
    {CCN_DTAG_Parameters, "Parameters"},
    {CCN_DTAG_InfoString, "InfoString"},
    {CCN_DTAG_StatusResponse, "StatusResponse"},
    {CCN_DTAG_StatusCode, "StatusCode"},
    {CCN_DTAG_StatusText, "StatusText"},
    {CCN_DTAG_SyncNode, "SyncNode"},
    {CCN_DTAG_SyncNodeKind, "SyncNodeKind"},
    {CCN_DTAG_SyncNodeElement, "SyncNodeElement"},
    {CCN_DTAG_SyncVersion, "SyncVersion"},
    {CCN_DTAG_SyncNodeElements, "SyncNodeElements"},
    {CCN_DTAG_SyncContentHash, "SyncContentHash"},
    {CCN_DTAG_SyncLeafCount, "SyncLeafCount"},
    {CCN_DTAG_SyncTreeDepth, "SyncTreeDepth"},
    {CCN_DTAG_SyncByteCount, "SyncByteCount"},
    {CCN_DTAG_SyncConfigSlice, "SyncConfigSlice"},
    {CCN_DTAG_SyncConfigSliceList, "SyncConfigSliceList"},
    {CCN_DTAG_SyncConfigSliceOp, "SyncConfigSliceOp"},
    {CCN_DTAG_SyncNodeDeltas, "SyncNodeDeltas"},
    {CCN_DTAG_SequenceNumber, "SequenceNumber"},
    {CCN_DTAG_CCNProtocolDataUnit, "CCNProtocolDataUnit"},
    {0, 0}
};

const struct ccn_dict ccn_dtag_dict = {ARRAY_N(ccn_tagdict) - 1, ccn_tagdict};
