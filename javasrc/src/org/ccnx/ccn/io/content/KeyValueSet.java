/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011, 2013 Palo Alto Research Center, Inc.
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

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

public class KeyValueSet extends GenericXMLEncodable implements XMLEncodable, Map<String, Object> {
	
	protected TreeMap<String, KeyValuePair> _set = new TreeMap<String, KeyValuePair>();
	
	/**
	 * A CCNNetworkObject wrapper around KeyValueSet, used for easily saving and retrieving
	 * versioned KeyValueSets to CCN. A typical pattern for using network objects to save
	 * objects that happen to be encodable or serializable is to incorporate such a static
	 * member wrapper class subclassing CCNEncodableObject, CCNSerializableObject, or
	 * CCNNetworkObject itself inside the main class definition.
	 */
	public static class KeyValueSetObject extends CCNEncodableObject<KeyValueSet> {

		public KeyValueSetObject(ContentName name, KeyValueSet data, SaveType saveType, CCNHandle handle) throws IOException {
			super(KeyValueSet.class, true, name, data, saveType, handle);
		}

		public KeyValueSetObject(ContentName name, KeyValueSet data, SaveType saveType,
				PublisherPublicKeyDigest publisher, 
				KeyLocator locator, CCNHandle handle) throws IOException {
			super(KeyValueSet.class, true, name, data, saveType, publisher, locator, handle);
		}

		public KeyValueSetObject(ContentName name, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(KeyValueSet.class, true, name, (PublisherPublicKeyDigest)null, handle);
		}

		public KeyValueSetObject(ContentName name, PublisherPublicKeyDigest publisher,
								CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(KeyValueSet.class, true, name, publisher, handle);
		}
		
		public KeyValueSetObject(ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(KeyValueSet.class, true, firstBlock, handle);
		}
		
		public KeyValueSet contents() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
	}
	
	public KeyValueSet() {}
	
	/**
	 * Create a KeyValueSet and initialize its contents to match that of a Java Properties
	 * collection.
	 * @param propertySet
	 */
	public KeyValueSet(Properties propertySet) {
		if (null == propertySet)
			return;
		for (Object property : propertySet.keySet()) {
			if (!(property instanceof String)) {
				throw new IllegalArgumentException("Not a valid Properties -- key not a String!");
			}
			put((String) property, propertySet.getProperty((String)property));
		}
	}
	
	/**
	 * Create a KeyValueSet and initialize its contents to that of a
	 * collection.
	 * @param propertySet
	 */
	public KeyValueSet(Map<String, Object> values) {
		if (null == values)
			return;
		putAll(values);
	}
	
	/**
	 * Add a new key value pair to the set.  Key must be unique.
	 * @param key key for the value
	 * @param value the value - may be Integer, Float, String, byte[], or ContentName
	 */
	public Object put(String key, Object value) {
		KeyValuePair kvp = _set.get(key);
		if (kvp != null) {
			throw new InvalidParameterException("Duplicate keys not allowed");
		}
		kvp = new KeyValuePair(key, value);
		_set.put(key, kvp);
		return value;
	}
	
	/**
	 * @param key key of the Object to get
	 * @return the value
	 */
	public Object get(String key) {
		KeyValuePair kvp = _set.get(key);
		return kvp == null ? null : _set.get(key).getValue();
	}
	
	public int size() {
		return _set.size();
	}
	
	public void clear() {
		_set.clear();
	}

	public boolean containsKey(Object key) {
		return _set.containsKey(key);
	}

	public boolean containsValue(Object value) {
		for (KeyValuePair kvp : _set.values()) {
			if (value.equals(kvp.getValue()))
				return true;
		}
		return false;
	}

	public Set<java.util.Map.Entry<String, Object>> entrySet() {
		HashSet<Map.Entry<String, Object>> hs = new HashSet<Map.Entry<String, Object>>();
		for (KeyValuePair kvp : _set.values()) {
			hs.add(kvp);
		}
		return hs;
	}

	public Object get(Object key) {
		return _set.get(key).getValue();
	}

	public boolean isEmpty() {
		return _set.isEmpty();
	}

	public Set<String> keySet() {
		return _set.keySet();
	}

	public void putAll(Map<? extends String, ? extends Object> t) {
		for (String k : t.keySet()) {
			put(k, t.get(k));
		}
	}

	public Object remove(Object key) {
		KeyValuePair kvp = _set.remove(key);
		return kvp.getValue();
	}

	public Collection<Object> values() {
		ArrayList<Object> al = new ArrayList<Object>();
		for (String k : _set.keySet()) {
			al.add(_set.get(k).getValue());
		}
		return al;
	}
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		
		decoder.readStartElement(getElementLabel());
		
		synchronized (_set) {
			while (decoder.peekStartElement(CCNProtocolDTags.Entry)) {
				KeyValuePair kvp = new KeyValuePair();
				kvp.decode(decoder);
				_set.put(kvp.getKey(), kvp);
			}
		}
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		for (KeyValuePair kvp : _set.values()) {
			kvp.encode(encoder);
		}
		encoder.writeEndElement();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final KeyValueSet other = (KeyValueSet) obj;
		if (!other.keySet().equals(keySet()))
			return false;
		for (String key : keySet()) {
			if (! _set.get(key).equals(other._set.get(key)))
				return false;
		}
		return true;
	}
	
	@Override
	public int hashCode() {
		return keySet().hashCode();
	}

	@Override
	public long getElementLabel() {return CCNProtocolDTags.KeyValueSet;}

	@Override
	public boolean validate() {
		return true;
	}
}
