/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.profiles.namespace;

import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;

public class ParameterizedName extends GenericXMLEncodable {
	
	public static final String PARAMETERIZED_NAME_ELEMENT = "ParameterizedName";
	public static final String LABEL_ELEMENT = "Label"; // overlaps with Link, WrappedKey.LABEL_ELEMENT,
														// shared dictionary entry	
	public static final String PREFIX_ELEMENT = "Prefix";
	public static final String SUFFIX_ELEMENT = "Suffix";
	
	public static class PrefixName extends ContentName {
		
		public PrefixName(ContentName other) {
			super(other);
		}
		
		public PrefixName() {}
		
		@Override
		public String getElementLabel() {
			return PREFIX_ELEMENT;
		}
	}

	public static class SuffixName extends ContentName {
		
		public SuffixName(ContentName other) {
			super(other);
		}
		
		public SuffixName() {}
		
		@Override
		public String getElementLabel() {
			return SUFFIX_ELEMENT;
		}
	}

	protected String _label;
	protected PrefixName _prefix;
	protected SuffixName _suffix;
	
	public ParameterizedName(String label, ContentName prefix, ContentName suffix) {
		_label = label;
		_prefix = new PrefixName(prefix);
		_suffix = (null != suffix) ? new SuffixName(suffix) : null;
	}
	
	public ParameterizedName() {
	}
	
	public String label() { return _label; }
	
	public ContentName prefix() { return _prefix; }
	
	public ContentName suffix() { return _suffix; }
	
	public boolean emptyLabel() { return (null == label()); }
	
	public boolean emptySuffix() { return (null == suffix()); }

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		
		if (decoder.peekStartElement(LABEL_ELEMENT)) {
			_label = decoder.readUTF8Element(LABEL_ELEMENT);
		}
		
		_prefix = new PrefixName();
		_prefix.decode(decoder);

		if (decoder.peekStartElement(SUFFIX_ELEMENT)) {
			_suffix = new SuffixName();
			_suffix.decode(decoder);
		}
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		
		if (!emptyLabel()) {
			encoder.writeElement(LABEL_ELEMENT, label());
		}
		
		prefix().encode(encoder);

		if (!emptySuffix()) {
			suffix().encode(encoder);
		}

		encoder.writeEndElement();   		
	}

	@Override
	public String getElementLabel() {
		return PARAMETERIZED_NAME_ELEMENT;
	}

	@Override
	public boolean validate() {
		return (null != _prefix);
	}

}
