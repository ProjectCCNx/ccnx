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

package org.ccnx.ccn.io.content;

import java.security.InvalidParameterException;
import java.util.Map;

import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.protocol.ContentName;

public class KeyValuePair extends GenericXMLEncodable implements XMLEncodable, Comparable<KeyValuePair>, Map.Entry<String, Object> {
	protected static final String ENTRY = "Entry";
	protected static final String KEY_ELEMENT = "Key";
	protected static final String INTEGER_ELEMENT = "IntegerValue";
	protected static final String DECIMAL_ELEMENT = "DecimalValue";
	protected static final String STRING_ELEMENT = "StringValue";
	protected static final String BINARY_ELEMENT = "BinaryValue";
	protected static final String NAME_ELEMENT = "NameValue";	// ccnx name


	protected String _key;
	protected Object _value;
	
	/**
	 * Encoder/Decoder for arbitrary key value pairs of type Integer, Float, String, or byte[]
	 * 
	 * @param key
	 * @param value
	 * @throws InvalidTypeException
	 */
	public KeyValuePair(String key, Object value) {
		_key = key;
		_value = value;
		if (!validate())
			throw new InvalidParameterException("Value has invalid type: " + value.getClass());
	}
	
	public String getKey() {
		return _key;
	}
	
	public Object getValue() {
		return _value;
	}
	
	public KeyValuePair() {} // For decoders

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		_key = decoder.readUTF8Element(KEY_ELEMENT);
		if (decoder.peekStartElement(INTEGER_ELEMENT)) {
			_value = decoder.readIntegerElement(INTEGER_ELEMENT);
		} else if (decoder.peekStartElement(DECIMAL_ELEMENT)) {
			try {
				_value = new Float(decoder.readUTF8Element(DECIMAL_ELEMENT)); 
			} catch (NumberFormatException nfe) {
				throw new ContentDecodingException(nfe.getMessage());
			}
		} else if (decoder.peekStartElement(STRING_ELEMENT)) {
			_value = decoder.readUTF8Element(STRING_ELEMENT);
		} else if (decoder.peekStartElement(BINARY_ELEMENT)) {
			_value = decoder.readBinaryElement(BINARY_ELEMENT);
		} else if (decoder.peekStartElement(NAME_ELEMENT)) {
			decoder.readStartElement(NAME_ELEMENT);
			_value = new ContentName();
			((ContentName)_value).decode(decoder);
			decoder.readEndElement();
		}

		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		encoder.writeElement(KEY_ELEMENT, _key);
		if (_value instanceof Integer) {
			encoder.writeIntegerElement(INTEGER_ELEMENT, (Integer)_value);
		} else if (_value instanceof Float) {
			encoder.writeElement(DECIMAL_ELEMENT, ((Float)_value).toString());
		} else if (_value instanceof String) {
			encoder.writeElement(STRING_ELEMENT, (String)_value);
		} else if (_value instanceof byte[]) {
			encoder.writeElement(BINARY_ELEMENT, (byte[])_value);
		} else if (_value instanceof ContentName) {
			encoder.writeStartElement(NAME_ELEMENT);
			((ContentName)_value).encode(encoder);
			encoder.writeEndElement();
		}
		encoder.writeEndElement();
	}

	@Override
	public String getElementLabel() {return ENTRY;}

	@Override
	public boolean validate() {
		if (null == _key)
			return false;
		return ((_value instanceof Integer) || (_value instanceof Float) || (_value instanceof String) || (_value instanceof byte[])
				|| (_value instanceof ContentName));
	}

	public int compareTo(KeyValuePair o) {
		return _key.compareTo(o._key);
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		KeyValuePair other = (KeyValuePair) obj;
		if (_value.getClass() != other._value.getClass())
			return false;
		if (_value instanceof byte[]) {
			if (!DataUtils.arrayEquals((byte[])_value, (byte[])other._value))
				return false;
		} else {
			if (!_value.equals(other._value))
				return false;
		}
		return true;
	}

	public Object setValue(Object value) {
		return null;
	}
}
