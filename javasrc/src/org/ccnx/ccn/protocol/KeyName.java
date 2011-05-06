/
 * Part of the CCNx Java Library
 
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc
 
 * This library is free software; you can redistribute it and/or modify i
 * under the terms of the GNU Lesser General Public License version 2.
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful
 * but WITHOUT ANY WARRANTY; without even the implied warranty o
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GN
 * Lesser General Public License for more details. You should have receive
 * a copy of the GNU Lesser General Public License along with this library
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street
 * Fifth Floor, Boston, MA 02110-1301 USA
 *

package org.ccnx.ccn.protocol;

import java.io.Serializable

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.content.ContentDecodingException
import org.ccnx.ccn.io.content.ContentEncodingException



/**
 * We wish to securely refer to a key. We do that by specifying a ContentNam
 * where it can be retrieved, and (optionally) information about who mus
 * have signed it for us to consider it valid. This can be a direct specificatio
 * of who must have signed it -- effectively saying that a specific key, correspondin
 * to an individual, most have signed the target for us to find it acceptable
 * More powerfully, this can incorporate a level of indirection -- we can say tha
 * an acceptable key is one signed by anyone whose own key was signed by th
 * specified publisher, effectively allowing for a form of certification.
 *
 * For now we allow a variety of specification of publisher, including some usin
 * digital certificates. These may be unnecessary and elided in the future.
 */
public class KeyName extends GenericXMLEncodable implements XMLEncodable, Serializable {
	
	private static final long serialVersionUID = -4486998061731593809L

	protected ContentName _name;
	protected PublisherID _publisher;

	/*
	 * Build a KeyNam
	 * @param name the name at which we can find the ke
	 * @param publisher the publisher we require to have signed the key, or to have signe
	 * 	the key that signed the ke
	 */
	public KeyName(ContentName name, 
				   PublisherID publisher) {
		_name = name;
		_publisher = publisher;
	

	public KeyName(ContentName name, PublisherPublicKeyDigest publisher) 
		_name = name
		_publisher = new PublisherID(publisher)
	}

	/*
	 * Build a KeyNam
	 * @param name the name at which we can find the ke
	 */
	public KeyName(ContentName name) {
		this(name, (PublisherID)null);
	}

	/*
	 * For use by decoder
	 */
	public KeyName() {}

	/*
	 * Get the nam
	 * @return the nam
	 */
	public ContentName name() { return _name; }

	/*
	 * Get the required publisher information, if specified
	 * @return the publisher specificatio
	 */
	public PublisherID publisher() { return _publisher; }
	
	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());

		_name = new ContentName();
		_name.decode(decoder);
		
		if (PublisherID.peek(decoder)) {
			_publisher = new PublisherID();
			_publisher.decode(decoder);
		}
		
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		
		name().encode(encoder);
		if (null != publisher())
			publisher().encode(encoder);

		encoder.writeEndElement();   		
	}
	
	@Override
	public long getElementLabel() { return CCNProtocolDTags.KeyName; }

	@Override
	public boolean validate() {
		// DKS -- do we do recursive validation?
		// null signedInfo ok
		return (null != name());
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_publisher == null) ? 0 : _publisher.hashCode());
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final KeyName other = (KeyName) obj;
		if (_publisher == null) {
			if (other._publisher != null)
				return false;
		} else if (!_publisher.equals(other._publisher))
			return false;
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
			return false;
		return true;
	

	@Override
	public String toString() 
		return ((null != name()) ? name() : "") + ((null != publisher()) ? " " + publisher() : "")
	}
}
