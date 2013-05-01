/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;

public class ParameterizedName extends GenericXMLEncodable {
	
	
	public static class PrefixName extends ContentName {
		
		private static final long serialVersionUID = 5093471560639087706L;

		public PrefixName(ContentName other) {
			super(other);
		}
		
		public PrefixName() {}
		
		@Override
		public long getElementLabel() {
			return CCNProtocolDTags.Prefix;
		}
	}

	public static class SuffixName extends ContentName {
		
		private static final long serialVersionUID = 3252505381608872546L;

		public SuffixName(ContentName other) {
			super(other);
		}
		
		public SuffixName() {}
		
		@Override
		public long getElementLabel() {
			return CCNProtocolDTags.Suffix;
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
	
	public ContentName prefix() { return new ContentName(_prefix); }
	
	public ContentName suffix() { return (null != _suffix) ? new ContentName(_suffix) : null; }
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		
		if (decoder.peekStartElement(CCNProtocolDTags.Label)) {
			_label = decoder.readUTF8Element(CCNProtocolDTags.Label);
		}
		
		_prefix = new PrefixName();
		_prefix.decode(decoder);

		if (decoder.peekStartElement(CCNProtocolDTags.Suffix)) {
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
		
		if (_label != null) {
			encoder.writeElement(CCNProtocolDTags.Label, _label);
		}
		
		_prefix.encode(encoder);

		if (_suffix != null) {
			_suffix.encode(encoder);
		}

		encoder.writeEndElement();   		
	}

	@Override
	public long getElementLabel() {
		return CCNProtocolDTags.ParameterizedName;
	}

	@Override
	public boolean validate() {
		return (null != _prefix);
	}
	
	@Override
	public String toString() {
		return _label + ": prefix: " + _prefix + ", suffix: " + _suffix;
	}

}
