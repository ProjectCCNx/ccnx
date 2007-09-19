package com.parc.ccn.crypto.certificates;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.X509Name;


/**
 * @author D.K. Smetters
 *
 * Replacement for BouncyCastle's GeneralName class, adding constructor
 * helper functions.
 * <pre>
 * GeneralName ::= CHOICE {
 *      otherName                       [0]     OtherName,
 *      rfc822Name                      [1]     IA5String,
 *      dNSName                         [2]     IA5String,
 *      x400Address                     [3]     ORAddress,
 *      directoryName                   [4]     Name,
 *      ediPartyName                    [5]     EDIPartyName,
 *      uniformResourceIdentifier       [6]     IA5String,
 *      ipAddress                       [7]     OCTET STRING,
 *      registeredID                    [8]     OBJECT IDENTIFIER}
 *
 * OtherName ::= SEQUENCE {
 *      type-id    OBJECT IDENTIFIER,
 *      value      [0] EXPLICIT ANY DEFINED BY type-id }
 *
 * EDIPartyName ::= SEQUENCE {
 *      nameAssigner            [0]     DirectoryString OPTIONAL,
 *      partyName               [1]     DirectoryString }
 * </pre>
 * 
 */
public class ASN1GeneralName extends GeneralName implements DEREncodable {
	
	public static final int tag_OtherName 		= 0; // OtherName
	public static final int tag_rfc822Name 		= 1; // IA5String
	public static final int tag_dnsName 			= 2; // IA5String
	public static final int tag_x400Name			= 3; // ORAddress
	public static final int tag_directoryName	= 4; // Name
	public static final int tag_ediPartyName		= 5; // EDIPartyName
	public static final int tag_uniformResourceIdentifier = 6;  // IA5String
	public static final int tag_ipAddress			= 7; // OCTET STRING
	public static final int tag_registeredID		= 8; // OBJECT IDENTIFIER

    /**
     * When the subjectAltName extension contains an Internet mail address,
     * the address MUST be included as an rfc822Name. The format of an
     * rfc822Name is an "addr-spec" as defined in RFC 822 [RFC 822].
     *
     * When the subjectAltName extension contains a domain name service
     * label, the domain name MUST be stored in the dNSName (an IA5String).
     * The name MUST be in the "preferred name syntax," as specified by RFC
     * 1034 [RFC 1034].
     *
     * When the subjectAltName extension contains a URI, the name MUST be
     * stored in the uniformResourceIdentifier (an IA5String). The name MUST
     * be a non-relative URL, and MUST follow the URL syntax and encoding
     * rules specified in [RFC 1738].  The name must include both a scheme
     * (e.g., "http" or "ftp") and a scheme-specific-part.  The scheme-
     * specific-part must include a fully qualified domain name or IP
     * address as the host.
     *
     * When the subjectAltName extension contains a iPAddress, the address
     * MUST be stored in the octet string in "network byte order," as
     * specified in RFC 791 [RFC 791]. The least significant bit (LSB) of
     * each octet is the LSB of the corresponding byte in the network
     * address. For IP Version 4, as specified in RFC 791, the octet string
     * MUST contain exactly four octets.  For IP Version 6, as specified in
     * RFC 1883, the octet string MUST contain exactly sixteen octets [RFC
     * 1883].
     */
    public ASN1GeneralName(DERObject name, int tag) {
    	super(tag, name);
    }
    
	/**
	 * Helper factory functions.
	 **/
	public static ASN1GeneralName fromEmailAddress(String rfc822Name) {
		return new ASN1GeneralName(new DERIA5String(rfc822Name), tag_rfc822Name);
	}
    
	public static ASN1GeneralName fromDNSName(String dnsName) {
		return new ASN1GeneralName(new DERIA5String(dnsName), tag_dnsName);
	}	

	public static ASN1GeneralName fromURI(String uri) {
		return new ASN1GeneralName(new DERIA5String(uri), tag_uniformResourceIdentifier);
	}	

	/**
	 * Should be 4 or 8 octets, but will let you do anything...
	 **/
	public static ASN1GeneralName fromIPAddress(byte [] addr) {
		return new ASN1GeneralName(new DEROctetString(addr), tag_ipAddress);
	}	

	public static ASN1GeneralName fromIPAddress(ASN1OctetString addr) {
		return new ASN1GeneralName(addr, tag_ipAddress);
	}	

	public static ASN1GeneralName fromIPAddress(String address) {
		byte [] addressBytes = ipAddressToByteArray(address);
		return new ASN1GeneralName(new DEROctetString(addressBytes), tag_ipAddress);
	}	

	public static ASN1GeneralName fromX400Name(DERObject x400Name) {
		return new ASN1GeneralName(x400Name, tag_x400Name);
	}

	public static ASN1GeneralName fromEDIPartyName(DERObject name) {
		return new ASN1GeneralName(name, tag_ediPartyName);
	}

	/**
	 * OtherName ::= SEQUENCE {
	 *      type-id    OBJECT IDENTIFIER,
	 *      value      [0] EXPLICIT ANY DEFINED BY type-id }
	 **/
	public static ASN1GeneralName fromOtherName(DERObjectIdentifier oid, DEREncodable any) {
		
		ASN1EncodableVector seq = new ASN1EncodableVector();
		seq.add(oid);
		seq.add(new DERTaggedObject(true,0,any));
		return new ASN1GeneralName(new DERSequence(seq), tag_OtherName);
	}

	public static ASN1GeneralName fromRegisteredID(DERObjectIdentifier oid) {
		return new ASN1GeneralName(oid, tag_registeredID);
	}	
	
	public static ASN1GeneralName fromDirectoryName(X509Name name) {
		return new ASN1GeneralName(name.getDERObject(), tag_directoryName);
	}	
	
	/**
	 * To get a GeneralName from CN=...
	 **/
	public static ASN1GeneralName fromDirectoryName(String name) {
		X509Name xname = new X509Name(name);
		return new ASN1GeneralName(xname.getDERObject(), tag_directoryName);
	}	

	/**
	 * Get the value of the GeneralName
	 **/
	public DEREncodable getGeneralName() {
		return getGeneralName(this);
	}
	
	public int getTag() {
		return getTag(this);
	}
	
	/**
	 * Helper functions to use with standard GeneralNames
	 **/
	static public int getTag(GeneralName name) {
		DERTaggedObject taggedData = (DERTaggedObject)name.getDERObject();
		return taggedData.getTagNo();
	}
	
	static public DEREncodable getGeneralName(GeneralName name) {
		DERTaggedObject taggedData = (DERTaggedObject)name.getDERObject();
		return taggedData.getObject();
	}		

    /**
     * Convert a string representation of an ip address to a byte array.
     * @param ipAddress Either an IPv4 address, "n.n.n.n", or an IPv6
     * address, "n.n.n.n.n.n.n.n", where in the former case, the n's are
     * decimal representations of 8-bit numbers, and in the latter case,
     * they are hexadecimal representations of 16-bit numbers.
     * */
    public static byte [] ipAddressToByteArray(String ipAddress) {

		String [] components = ipAddress.split("\\.");
		int base = 10;
		byte [] bytes = null;
		
		if (4 == components.length) {
			base = 10;
			bytes = new byte[components.length];
			for (int i=0; i < components.length; ++i) {
				int o = Integer.parseInt(components[i], base);
				if ((o < 0) || (o >= 256)) {
					throw new IllegalArgumentException("Not a valid IPv4 address.");
				}
				bytes[i] = (byte)o;
			} 
		} else if (8 == components.length) {
			base = 16;
			bytes = new byte[components.length * 2]; // do it byte-wise
			String start, end;
			for (int i=0; i < components.length; ++i) {
				start = components[i].substring(0,2);
				end = components[i].substring(2,4);
				int s = Integer.parseInt(start, base);
				int e = Integer.parseInt(end, base);
				if (((s < 0) || (s >= 256)) || (e < 0) || (e >= 256)) {
					throw new IllegalArgumentException("Not a valid IPv6 address.");
				}
				bytes[2*i] = (byte)s;
				bytes[2*i + 1] = (byte)e;
			}
				
		} else {
			// error, not a valid ip address
			bytes = null;
		}
		return bytes;
    }

}
