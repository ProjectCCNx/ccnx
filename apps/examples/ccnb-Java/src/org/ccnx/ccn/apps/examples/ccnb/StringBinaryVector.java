/*
 * A CCNx example of extending ccnb encoding/decoding.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */


/*
 * A CCNx example of extending ccnb encoding/decoding.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */


package org.ccnx.ccn.apps.examples.ccnb;

import java.util.Vector;

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;

/**
 *
 */
public class StringBinaryVector extends GenericXMLEncodable {
	
	/**
	 * 
	 */
	public StringBinaryVector() {
	}

	/**
	 * @param string
	 * @param names
	 * @param interests
	 * @param binary
	 */
	public StringBinaryVector(String string, Vector<ContentName> names,
			Vector<Interest> interests, byte[] binary) {
		this.string = string;
		this.names = names;
		this.interests = interests;
		this.binary = binary;
	}

	/**
	 * @param string
	 * @param binary
	 */
	public StringBinaryVector(String string, byte[] binary) {
		this.string = string;
		this.binary = binary;
		this.names = new Vector<ContentName>();
		this.interests = new Vector<Interest>();
	}
	
	public void addName(ContentName name) {
		names.add(name);
	}
	
	public void addInterest(Interest interest) {
		interests.add(interest);
	}

	/**
	 * @return the string
	 */
	public String getString() {
		return string;
	}

	/**
	 * @param string the string to set
	 */
	public void setString(String string) {
		this.string = string;
	}

	/**
	 * @return the binary
	 */
	public byte[] getBinary() {
		return binary;
	}

	/**
	 * @param binary the binary to set
	 */
	public void setBinary(byte[] binary) {
		this.binary = binary;
	}

	/**
	 * @return the names
	 */
	public Vector<ContentName> getNames() {
		return names;
	}

	/**
	 * @return the interests
	 */
	public Vector<Interest> getInterests() {
		return interests;
	}

	protected String string;
	protected Vector<ContentName> names = new Vector<ContentName>();
	protected Vector<Interest> interests = new Vector<Interest>();
	protected byte[] binary;

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		names.clear();
		interests.clear();
		decoder.readStartElement(getElementLabel());
		string = decoder.readUTF8Element(ExampleDTags.String); 

		while (decoder.peekStartElement(CCNProtocolDTags.Interest) || decoder.peekStartElement(CCNProtocolDTags.Name)) {
			if (decoder.peekStartElement(CCNProtocolDTags.Interest)) {
				Interest interest = new Interest();
				interest.decode(decoder);
				interests.add(interest);
			} else if (decoder.peekStartElement(CCNProtocolDTags.Name)) {
				ContentName cn = new ContentName();
				cn.decode(decoder);
				names.add(cn);
			}
		}
		binary = decoder.readBinaryElement(ExampleDTags.Binary);
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate())
			throw new ContentEncodingException("StringBinaryVector failed to validate!");
		encoder.writeStartElement(getElementLabel());
		encoder.writeElement(ExampleDTags.String, string);
		for (int i = 0; i < names.size(); i++) {
			ContentName name = names.elementAt(i);
			name.encode(encoder);
		}
		for (int i = 0; i < interests.size(); i++) {
			Interest interest = interests.elementAt(i);
			interest.encode(encoder);
		}
		encoder.writeElement(ExampleDTags.Binary, binary);
		encoder.writeEndElement();   		
	}

	@Override
	public long getElementLabel() {
		return ExampleDTags.StringBinaryVector;
	}

	@Override
	public boolean validate() {
		return true;
	}

}
