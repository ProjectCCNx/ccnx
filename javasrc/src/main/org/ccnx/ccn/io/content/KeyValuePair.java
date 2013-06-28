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

package org.ccnx.ccn.io.content;

import java.security.InvalidParameterException;
import java.util.Comparator;
import java.util.Map;

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.protocol.ContentName;

public class KeyValuePair extends GenericXMLEncodable implements XMLEncodable, Comparable<KeyValuePair>, Map.Entry<String, Object> {

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
		_key = decoder.readUTF8Element(CCNProtocolDTags.Key);
		
		Long valueTag = decoder.peekStartElementAsLong();
		if (null == valueTag) {
			throw new ContentDecodingException("Cannot decode key value pair for key " + _key + ": no value given");
		} 
		
		long valueTagVal = valueTag.longValue();
		
		if (valueTagVal == CCNProtocolDTags.IntegerValue) {
			_value = decoder.readIntegerElement(CCNProtocolDTags.IntegerValue);
		} else if (valueTagVal == CCNProtocolDTags.DecimalValue) {
			try {
				_value = new Float(decoder.readUTF8Element(CCNProtocolDTags.DecimalValue)); 
			} catch (NumberFormatException nfe) {
				throw new ContentDecodingException(nfe.getMessage());
			}
		} else if (valueTagVal == CCNProtocolDTags.StringValue) {
			_value = decoder.readUTF8Element(CCNProtocolDTags.StringValue);
		} else if (valueTagVal == CCNProtocolDTags.BinaryValue) {
			_value = decoder.readBinaryElement(CCNProtocolDTags.BinaryValue);
		} else if (valueTagVal == CCNProtocolDTags.NameValue) {
			decoder.readStartElement(CCNProtocolDTags.NameValue);
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
		encoder.writeElement(CCNProtocolDTags.Key, _key);
		if (_value instanceof Long) {
			encoder.writeElement(CCNProtocolDTags.IntegerValue, (Long)_value);
		}  else if (_value instanceof Integer) {
			encoder.writeElement(CCNProtocolDTags.IntegerValue, (Integer)_value);
		} else if (_value instanceof Float) {
			encoder.writeElement(CCNProtocolDTags.DecimalValue, ((Float)_value).toString());
		} else if (_value instanceof String) {
			encoder.writeElement(CCNProtocolDTags.StringValue, (String)_value);
		} else if (_value instanceof byte[]) {
			encoder.writeElement(CCNProtocolDTags.BinaryValue, (byte[])_value);
		} else if (_value instanceof ContentName) {
			encoder.writeStartElement(CCNProtocolDTags.NameValue);
			((ContentName)_value).encode(encoder);
			encoder.writeEndElement();
		}
		encoder.writeEndElement();
	}

	@Override
	public long getElementLabel() {return CCNProtocolDTags.Entry;}

	@Override
	public boolean validate() {
		if (null == _key)
			return false;
		return ((_value instanceof Integer) || (_value instanceof Long) || 
				(_value instanceof Float) || (_value instanceof String) || (_value instanceof byte[])
				|| (_value instanceof ContentName));
	}

	/**
	 * Compares based on _key first.  If keys equal, then
	 * compares based on _value.  Nulls are treated as equals, otherwise 
	 * Null < non-Null.
	 * 
	 * The Comparison on _value requires the class of _value to be the same.  If
	 * you try to compare keys with different classes for _value, compareTo will return
	 * a consistent ordering based on class, not on the value of _value (i.e. 
	 * ordering by canonical class name).  If _value is of type byte[], then
	 * the ordering is a shortlex.
	 */
	public int compareTo(KeyValuePair o) {
		if( o == null )
			throw new NullPointerException("compareTo called with null");
		
		int c;
		
		if( _key == null && o._key == null )
			c = 0;
		else if( _key == null && o._key != null )
			c = -1;
		else if( _key != null && o._key == null )
			c = +1;
		else 
			c = _key.compareTo(o._key);
		
		if (c == 0) {	
			if( _value == null && o._value == null )
				c = 0;
			else if( _value == null && o._value != null )
				c = -1;
			else if( _value != null && o._value == null )
				c = +1;
			else {
				// both must be non-null
				Class<?> cls_mine = _value.getClass();
				Class<?> cls_his  = o._value.getClass();
				
				c = cls_mine.getCanonicalName().compareTo(cls_his.getCanonicalName());
				if( c == 0 ) {
				
					// Classes are the same, so now compare on the actual value
					if (_value instanceof Long) {
						c = ((Long) _value).compareTo((Long) o._value);
					}  else if (_value instanceof Integer) {
						c = ((Integer) _value).compareTo((Integer) o._value);
					} else if (_value instanceof Float) {
						c = ((Float) _value).compareTo((Float) o._value);
					} else if (_value instanceof String) {
						c = ((String) _value).compareTo((String) o._value);
					} else if (_value instanceof byte[]) {
						byte [] mine = (byte []) _value;
						byte [] his  = (byte []) o._value;
						c = DataUtils.compare(mine, his);
					} else if (_value instanceof ContentName) {
						c = ((ContentName) _value).compareTo((ContentName) o._value);
					} else {
						// XXX Should never get here!
						throw new RuntimeException("Unknown class type of _value");
					}
				}
			}
		}
		
		return c;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		KeyValuePair other = (KeyValuePair) obj;
		return (compareTo(other) == 0);
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + (_key != null ? _key.hashCode() : 0);
		result = 31 * result + (_value != null ? _value.hashCode() : 0);
		return result;
	}
	
	public Object setValue(Object value) {
		return null;
	}
	
	/**
	 * This can be used in a data structure to order KV pairs by their keys.
	 */
	public static class KeyOrderComparator implements Comparator<KeyValuePair> {
		public int compare(KeyValuePair arg0, KeyValuePair arg1) {
			return arg0._key.compareTo(arg1._key);
		}
	}
}
