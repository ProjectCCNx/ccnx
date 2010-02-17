package org.ccnx.ccn.impl.encoding;


/**
 * In Java, you can't control the value an enum is assigned to, so we map
 * it into an interior value. Use the XML name as the name of the enum, so
 * can map easily to strings.
 */
public enum CCNProtocolDTags {

	Any ( 13 ),
	Name ( 14 ),
	Component ( 15 ),
	Certificate ( 16 ),
	Collection ( 17 ),
	CompleteName ( 18 ),
	Content ( 19 ),
	SignedInfo ( 20 ),
	ContentDigest ( 21 ),
	ContentHash ( 22 ),
	Count ( 24 ),
	Header ( 25 ),
	Interest ( 26 ),	/* 20090915 */
	Key ( 27 ),
	KeyLocator ( 28 ),
	KeyName ( 29 ),
	Length ( 30 ),
	Link ( 31 ),
	LinkAuthenticator ( 32 ),
	NameComponentCount ( 33 ),	/* DeprecatedInInterest */
	RootDigest ( 36 ),
	Signature ( 37 ),
	Start ( 38 ),
	Timestamp ( 39 ),
	Type ( 40 ),
	Nonce ( 41 ),
	Scope ( 42 ),
	Exclude ( 43 ),
	Bloom ( 44 ),
	BloomSeed ( 45 ),
	AnswerOriginKind ( 47 ),
	Witness ( 53 ),
	SignatureBits ( 54 ),
	DigestAlgorithm ( 55 ),
	BlockSize ( 56 ),
	FreshnessSeconds ( 58 ),
	FinalBlockID ( 59 ),
	PublisherPublicKeyDigest ( 60 ),
	PublisherCertificateDigest ( 61 ),
	PublisherIssuerKeyDigest ( 62 ),
	PublisherIssuerCertificateDigest ( 63 ),
	ContentObject ( 64 ),	/* 20090915 */
	WrappedKey ( 65 ),
	WrappingKeyIdentifier ( 66 ),
	WrapAlgorithm ( 67 ),
	KeyAlgorithm ( 68 ),
	Label ( 69 ),
	EncryptedKey ( 70 ),
	EncryptedNonceKey ( 71 ),
	WrappingKeyName ( 72 ),
	Action ( 73 ),
	FaceID ( 74 ),
	IPProto ( 75 ),
	Host ( 76 ),
	Port ( 77 ),
	MulticastInterface ( 78 ),
	ForwardingFlags ( 79 ),
	FaceInstance ( 80 ),
	ForwardingEntry ( 81 ),
	MulticastTTL ( 82 ),
	MinSuffixComponents ( 83 ),
	MaxSuffixComponents ( 84 ),
	ChildSelector ( 85 ),
	RepositoryInfo ( 86 ),
	Version ( 87 ),
	RepositoryVersion ( 88 ),
	GlobalPrefix ( 89 ),
	LocalName ( 90 ),
	Policy ( 91 ),
	Namespace ( 92 ),
	GlobalPrefixName ( 93 ),
	PolicyVersion ( 94 ),
	KeyValueSet ( 95 ),
	KeyValuePair ( 96 ),
	IntegerValue ( 97 ),
	DecimalValue ( 98 ),
	StringValue ( 99 ),
	BinaryValue ( 100 ),
	NameValue ( 101 ),
	Entry ( 102 ),
	CCNProtocolDataUnit ( 17702112 );

	int _tag;

	CCNProtocolDTags(int tag) {
		this._tag = tag;
	}
	
	int getTag() { return _tag; }
}
