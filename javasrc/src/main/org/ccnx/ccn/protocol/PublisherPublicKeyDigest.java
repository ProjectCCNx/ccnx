/*
 * Part of the CCNx Java Library.
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

package org.ccnx.ccn.protocol;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.security.Key;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.logging.Level;

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.ContentName.ComponentProvider;


/**
 * Wrapper class around the digest of public keys used as a publisher identifier in ContentObjects.
 * The digest algorithm used to compute publisherKeyIDs is specified by the CCNx protocol, and changes
 * rarely with the CCNx protocol version (for example, now it is SHA-256, it will shift to SHA-3).
 * As this is a convenience filtering mechanism to help consumers retrieve content signed by the
 * specific publishers they want, failure in the digest algorithm used will only decrease the efficiency
 * of this filtering method, or potentially help an attacker prevent a user from finding legitimate
 * content. It will never cause the consumer to accept invalid content. We therefore opted to make
 * this one of the small number of fixed-algorithm components in CCNx, to vastly simplify what is
 * required of network routers that have to check and interpret this field.
 * 
 * To generate a PublisherPublicKeyDigest, we use the digest of the encoded PublicKey (the encoded SubjectPublicKeyInfo).
 */
public class PublisherPublicKeyDigest extends GenericXMLEncodable 
			implements XMLEncodable, Comparable<PublisherPublicKeyDigest>, Serializable, ComponentProvider {
    
 	private static final long serialVersionUID = -1636681985247106846L;

 	protected byte [] _publisherPublicKeyDigest;
    
    /**
     * Create a PublisherPublicKeyDigest from a PublicKey
     * @param key the key
     */
    public PublisherPublicKeyDigest(Key key) {
    	_publisherPublicKeyDigest = PublisherID.generatePublicKeyDigest(key);
    }
    	
    /**
     * Create a PublisherPublicKeyDigest from an existing digest
     * @param publisherPublicKeyDigest the key
     */
	public PublisherPublicKeyDigest(byte [] publisherPublicKeyDigest) {
		// Alas, Arrays.copyOf doesn't exist in 1.5, and we'd like
		// to be mostly 1.5 compatible for now...
		// _publisherPublicKeyDigest = Arrays.copyOf(publisherID, PUBLISHER_ID_LEN);
		if (null == publisherPublicKeyDigest) {
			throw new IllegalArgumentException("This is not a valid publisher public key digest!");
		}
		_publisherPublicKeyDigest = new byte[PublisherID.PUBLISHER_ID_LEN];
		int len = publisherPublicKeyDigest.length;
		if (len > PublisherID.PUBLISHER_ID_LEN) {
			len = PublisherID.PUBLISHER_ID_LEN;
			Log.warning("Truncating PublisherPublicKeyDigest to {0} bytes", len);
		}
		System.arraycopy(publisherPublicKeyDigest, 0, _publisherPublicKeyDigest,
						 (PublisherID.PUBLISHER_ID_LEN-len), len);
	}	
	
	/**
	 * Expects the equivalent of publisherKeyID.toString
	 * @param publisherPublicKeyDigest the string representation of the digest.
	 * @throws IOException 
	 */
	public PublisherPublicKeyDigest(String publisherPublicKeyDigest) throws IOException {
		_publisherPublicKeyDigest = DataUtils.base64Decode(publisherPublicKeyDigest.getBytes());
		if (_publisherPublicKeyDigest.length != PublisherID.PUBLISHER_ID_LEN) {
			throw new IOException("Not a valid base64Binary encoding of a PublisherPublicKeyDigest" + publisherPublicKeyDigest);
		}
	}
	
	/**
	 * Parses a URI-encoded key ID, with an optional keyid: command marker.
	 * @throws URISyntaxException 
	 * @throws Component.DotDot 
	 */
	public static PublisherPublicKeyDigest fromURIEncoded(String uriEncoded) throws Component.DotDot, URISyntaxException {
		byte [] encodedBytes = Component.parseURI(uriEncoded);
		
		if (KeyProfile.KEY_NAME_COMPONENT_MARKER.isMarker(encodedBytes)) {
			return new PublisherPublicKeyDigest(KeyProfile.getKeyIDFromNameComponent(encodedBytes));
		}
		// No marker
		return new PublisherPublicKeyDigest(encodedBytes);
	}
	
	/**
	 * Gets a PublisherPublicKeyDigest for the public key in an X509Certificate.
	 */
	public static PublisherPublicKeyDigest fromCertificate(X509Certificate certificate) {
		return new PublisherPublicKeyDigest(certificate.getPublicKey());
	}
	
	/**
	 * For use by decoders
	 */
    public PublisherPublicKeyDigest() {}

    /**
     * Return the digest
     * @return the digest itself
     */
	public byte [] digest() { return _publisherPublicKeyDigest; }
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_publisherPublicKeyDigest);
		return result;
	}
	
	public boolean equals(PublisherID publisher) {
		if (PublisherID.PublisherType.KEY != publisher.type()) 
			return false;
		if (!Arrays.equals(digest(), publisher.id()))
			return false;
		return true;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (PublisherID.class == obj.getClass())
			return obj.equals(this); // put complex implementation in one place
		if (getClass() != obj.getClass())
			return false;
		final PublisherPublicKeyDigest other = (PublisherPublicKeyDigest) obj;
		if (!Arrays.equals(_publisherPublicKeyDigest, other._publisherPublicKeyDigest))
			return false;
		return true;
	}
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {		
		// The format of a publisher ID is an octet string of the correct length.
		_publisherPublicKeyDigest = decoder.readBinaryElement(getElementLabel());
		if (null == _publisherPublicKeyDigest) {
			throw new ContentDecodingException("Cannot parse publisher key digest.");
		}
		if (_publisherPublicKeyDigest.length != PublisherID.PUBLISHER_ID_LEN) {
			if (Log.isLoggable(Level.WARNING)) {
				Log.warning("Wrong length for PublisherPublicKeyDigest: {0}", CCNDigestHelper.printBytes(_publisherPublicKeyDigest, 32));
			}
			_publisherPublicKeyDigest = new PublisherPublicKeyDigest(_publisherPublicKeyDigest).digest();
		}
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeElement(getElementLabel(), digest());
	}
	
	@Override
	public long getElementLabel() { return CCNProtocolDTags.PublisherPublicKeyDigest; }

	@Override
	public boolean validate() {
		return (null != digest());
	}

	/**
	 * Implement Comparable
	 * @param o the other thing to compare to
	 * @return -1, 0 or 1 depending on whether we are before, equal to or lexicographically after o
	 */
	public int compareTo(PublisherPublicKeyDigest o) {
		int result = DataUtils.compare(this.digest(), o.digest());
		return result;
	}

	@Override
	/**
	 * The string representation is base64Binary (RFC 2045, section 6.8).
	 */
	public String toString() {
		return DataUtils.base64Encode(digest(), PublisherID.PUBLISHER_ID_LEN*2);
	}
	
	/**
	 * A short string representation of the key. Really want PGP fingerprints.
	 * @return
	 */
	public String shortFingerprint() {
		long lf = new BigInteger(1, _publisherPublicKeyDigest).longValue();
		return Long.toHexString(lf);
	}

	/**
	 * Implements the {@link ComponentProvider} interface, allowing a {@link PublisherPublicKeyDigest}
	 * to be included in a ContentName constructor, yielding a PublisherPublicKey ID as a name component.
	 */
	public byte[] getComponent() {
		return KeyProfile.keyIDToNameComponent(this);
	}
}
