/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.math.BigInteger;
import java.security.PublicKey;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.DataUtils;


/**
 * Helper wrapper class for publisher IDs.
 * @author smetters
 *
 */
public class PublisherPublicKeyDigest extends GenericXMLEncodable implements XMLEncodable, Comparable<PublisherPublicKeyDigest> {
    
    public static final String PUBLISHER_PUBLIC_KEY_DIGEST_ELEMENT = "PublisherPublicKeyDigest";

    protected byte [] _publisherPublicKeyDigest;
    
    public PublisherPublicKeyDigest(PublicKey key) {
    	_publisherPublicKeyDigest = PublisherID.generatePublicKeyDigest(key);
    }
    	
	public PublisherPublicKeyDigest(byte [] publisherPublicKeyDigest) {
		// Alas, Arrays.copyOf doesn't exist in 1.5, and we'd like
		// to be mostly 1.5 compatible for the macs...
		// _publisherPublicKeyDigest = Arrays.copyOf(publisherID, PUBLISHER_ID_LEN);
		_publisherPublicKeyDigest = new byte[PublisherID.PUBLISHER_ID_LEN];
		System.arraycopy(publisherPublicKeyDigest, 0, _publisherPublicKeyDigest, 0, PublisherID.PUBLISHER_ID_LEN);
	}	
	
	/**
	 * Expects the equivalent of publisherKeyID.toString
	 * @param publisherPublicKeyDigest
	 */
	public PublisherPublicKeyDigest(String publisherPublicKeyDigest) {
		this(CCNDigestHelper.scanBytes(publisherPublicKeyDigest, 32));
	}
	
    public PublisherPublicKeyDigest() {} // for use by decoders
	
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
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		
		// The format of a publisher ID is an octet string.

		_publisherPublicKeyDigest = decoder.readBinaryElement(getElementLabel());
		if (null == _publisherPublicKeyDigest) {
			throw new XMLStreamException("Cannot parse publisher key digest.");
		}
	}

	@Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		// The format of a publisher ID is:
		// <PublisherID type=<type> id_content />
		encoder.writeElement(getElementLabel(), digest());
	}
	
	@Override
	public String getElementLabel() { return PUBLISHER_PUBLIC_KEY_DIGEST_ELEMENT; }

	@Override
	public boolean validate() {
		return (null != digest());
	}

	public int compareTo(PublisherPublicKeyDigest o) {
		int result = DataUtils.compare(this.digest(), o.digest());
		return result;
	}

	@Override
	public String toString() {
		// 	16 would be the most familiar option, but 32 is shorter
		return CCNDigestHelper.printBytes(digest(), 32);
	}
	
	/**
	 * A short string representation of the key. Really want PGP fingerprints.
	 * @return
	 */
	public String shortFingerprint() {
		long lf = new BigInteger(1, _publisherPublicKeyDigest).longValue();
		return Long.toHexString(lf);
	}
}
