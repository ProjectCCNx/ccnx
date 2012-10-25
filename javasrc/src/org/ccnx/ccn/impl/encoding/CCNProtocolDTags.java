/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.impl.encoding;


/**
 * In Java, you can't control the value an enum is assigned to, so we map
 * it into an interior value. Use the XML name as the name of the enum, so
 * can map easily to strings.
 *
 * Move from enum to final ints, in order to reduce overhead.
 * 
 * This is essentially an XMLDictionary, but it was created as a static class
 * so it cannot implement XMLDictionary.  Therefore, it is wrapped by CCNProtocolDictionary.
 */
public class CCNProtocolDTags {

	/**
	 * Note if you add one of these, add it to the reverse string map as well.
	 * Emphasize getting the work done at compile time over trying to make something
	 * flexible and developer error-proof.
	 */
	public static final int Any = 13;
	public static final int Name = 14;
	public static final int Component = 15;
	public static final int Certificate = 16;
	public static final int Collection = 17;
	public static final int CompleteName = 18;
	public static final int Content = 19;
	public static final int SignedInfo = 20;
	public static final int ContentDigest = 21;
	public static final int ContentHash = 22;
	public static final int Count = 24;
	public static final int Header = 25;
	public static final int Interest = 26;	/* 20090915 */
	public static final int Key = 27;
	public static final int KeyLocator = 28;
	public static final int KeyName = 29;
	public static final int Length = 30;
	public static final int Link = 31;
	public static final int LinkAuthenticator = 32;
	public static final int NameComponentCount = 33;	/* DeprecatedInInterest */
	public static final int ExtOpt = 34;
	public static final int RootDigest = 36;
	public static final int Signature = 37;
	public static final int Start = 38;
	public static final int Timestamp = 39;
	public static final int Type = 40;
	public static final int Nonce = 41;
	public static final int Scope = 42;
	public static final int Exclude = 43;
	public static final int Bloom = 44;
	public static final int BloomSeed = 45;
	public static final int AnswerOriginKind = 47;
	public static final int InterestLifetime = 48;
	public static final int Witness = 53;
	public static final int SignatureBits = 54;
	public static final int DigestAlgorithm = 55;
	public static final int BlockSize = 56;
	public static final int FreshnessSeconds = 58;
	public static final int FinalBlockID = 59;
	public static final int PublisherPublicKeyDigest = 60;
	public static final int PublisherCertificateDigest = 61;
	public static final int PublisherIssuerKeyDigest = 62;
	public static final int PublisherIssuerCertificateDigest = 63;
	public static final int ContentObject = 64;	/* 20090915 */
	public static final int WrappedKey = 65;
	public static final int WrappingKeyIdentifier = 66;
	public static final int WrapAlgorithm = 67;
	public static final int KeyAlgorithm = 68;
	public static final int Label = 69;
	public static final int EncryptedKey = 70;
	public static final int EncryptedNonceKey = 71;
	public static final int WrappingKeyName = 72;
	public static final int Action = 73;
	public static final int FaceID = 74;
	public static final int IPProto = 75;
	public static final int Host = 76;
	public static final int Port = 77;
	public static final int MulticastInterface = 78;
	public static final int ForwardingFlags = 79;
	public static final int FaceInstance = 80;
	public static final int ForwardingEntry = 81;
	public static final int MulticastTTL = 82;
	public static final int MinSuffixComponents = 83;
	public static final int MaxSuffixComponents = 84;
	public static final int ChildSelector = 85;
	public static final int RepositoryInfo = 86;
	public static final int Version = 87;
	public static final int RepositoryVersion = 88;
	public static final int GlobalPrefix = 89;
	public static final int LocalName = 90;
	public static final int Policy = 91;
	public static final int Namespace = 92;
	public static final int GlobalPrefixName = 93;
	public static final int PolicyVersion = 94;
	public static final int KeyValueSet = 95;
	public static final int KeyValuePair = 96;
	public static final int IntegerValue = 97;
	public static final int DecimalValue = 98;
	public static final int StringValue = 99;
	public static final int BinaryValue = 100;
	public static final int NameValue = 101;
	public static final int Entry = 102;
	public static final int ACL = 103;
	public static final int ParameterizedName = 104;
	public static final int Prefix = 105;
	public static final int Suffix = 106;
	public static final int Root = 107;
	public static final int ProfileName = 108;
	public static final int Parameters = 109;
	public static final int InfoString = 110;
	// 111 unallocated
	public static final int StatusResponse = 112;
	public static final int StatusCode = 113;
	public static final int StatusText = 114;

	// Sync protocol
	public static final int SyncNode = 115;
	public static final int SyncNodeKind = 116;
	public static final int SyncNodeElement = 117;
	public static final int SyncVersion = 118;
	public static final int SyncNodeElements = 119;
	public static final int SyncContentHash = 120;
	public static final int SyncLeafCount = 121;
	public static final int SyncTreeDepth = 122;
	public static final int SyncByteCount = 123;
	public static final int ConfigSlice = 124;
	public static final int ConfigSliceList = 125;
	public static final int ConfigSliceOp = 126;

	// Remember to keep in sync with schema/tagnames.csvsdict
	public static final int CCNProtocolDataUnit = 17702112;
	public static final String CCNPROTOCOL_DATA_UNIT = "CCNProtocolDataUnit";
	
	protected static final String [] _tagToStringMap = new String[]{
		null, null, null, null, null, null, null, null, null, null, null,
		null, null,
		"Any", "Name", "Component", "Certificate", "Collection", "CompleteName",
		"Content", "SignedInfo", "ContentDigest", "ContentHash", null, "Count", "Header",
		"Interest", "Key", "KeyLocator", "KeyName", "Length", "Link", "LinkAuthenticator",
		"NameComponentCount", "ExtOpt", null, "RootDigest", "Signature", "Start", "Timestamp", "Type",
		"Nonce", "Scope", "Exclude", "Bloom", "BloomSeed", null, "AnswerOriginKind",
		"InterestLifetime", null, null, null, null, "Witness", "SignatureBits", "DigestAlgorithm", "BlockSize",
		null, "FreshnessSeconds", "FinalBlockID", "PublisherPublicKeyDigest", "PublisherCertificateDigest",
		"PublisherIssuerKeyDigest", "PublisherIssuerCertificateDigest", "ContentObject",
		"WrappedKey", "WrappingKeyIdentifier", "WrapAlgorithm", "KeyAlgorithm", "Label",
		"EncryptedKey", "EncryptedNonceKey", "WrappingKeyName", "Action", "FaceID", "IPProto",
		"Host", "Port", "MulticastInterface", "ForwardingFlags", "FaceInstance",
		"ForwardingEntry", "MulticastTTL", "MinSuffixComponents", "MaxSuffixComponents", "ChildSelector",
		"RepositoryInfo", "Version", "RepositoryVersion", "GlobalPrefix", "LocalName",
		"Policy", "Namespace", "GlobalPrefixName", "PolicyVersion", "KeyValueSet", "KeyValuePair",
		"IntegerValue", "DecimalValue", "StringValue", "BinaryValue", "NameValue", "Entry",
		"ACL", "ParameterizedName", "Prefix", "Suffix", "Root", "ProfileName", "Parameters",
		"InfoString", null,
        "StatusResponse", "StatusCode", "StatusText", "SyncNode", "SyncNodeKind", "SyncNodeElement",
        "SyncVersion", "SyncNodeElements", "SyncContentHash", "SyncLeafCount", "SyncTreeDepth", "SyncByteCount",
        "ConfigSlice", "ConfigSliceList", "ConfigSliceOp" };
	protected static final int TAG_MAP_LENGTH = _tagToStringMap.length;

	
	/**
	 * This is the slow search -- find a tag based on an index. Only
	 * used in cases where we need to print based on a binary tag value;
	 * this is only used in text encoding of usually binary objects... For
	 * now, as it's rare, do a scan, rather than taking the up front hit
	 * to build a hash table.
	 * @param tagVal
	 * @return
	 */
	
	public static String tagToString(long tagVal) {
		if ((tagVal >= 0) && (tagVal < TAG_MAP_LENGTH)) {
			return _tagToStringMap[(int)tagVal];
		} else if (tagVal == CCNProtocolDataUnit) {
			return CCNPROTOCOL_DATA_UNIT;
		}
		return null;
	}
	
	public static Long stringToTag(String tagName) {
		// the slow way, but right now we don't care.... want a static lookup for the forward direction
		for (int i=0; i < TAG_MAP_LENGTH; ++i) {
			if ((null != _tagToStringMap[i]) && (_tagToStringMap[i].equals(tagName))) {
				return (long)i;
			}
		}
		if (CCNPROTOCOL_DATA_UNIT.equals(tagName)) {
			return (long)CCNProtocolDataUnit;
		}
		return null;
	}
}
