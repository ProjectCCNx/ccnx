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
package org.ccnx.ccn.impl.encoding;

import java.util.Stack;

public abstract class GenericXMLHandler {

	protected Stack<BinaryXMLDictionary> _dictionary = new Stack<BinaryXMLDictionary>();

	public GenericXMLHandler() {
		this(null);
	}

	public GenericXMLHandler(BinaryXMLDictionary dictionary) {
		if (null == dictionary)
			_dictionary.push(BinaryXMLDictionary.getDefaultDictionary());
		else
			_dictionary.push(dictionary);
	}
		
	/**
	 * Pull data from our dictionary stack in order. Return the first non-null value,
	 * or null if nobody knows this name. (Option: handle unknown tags here.) 
	 * @param tag
	 * @return
	 */
	public Long stringToTag(String tagName) {
		if (null == tagName) {
			return null;
		}
		Long tagVal = null;
		for (BinaryXMLDictionary dictionary : _dictionary) {
			tagVal = dictionary.stringToTag(tagName);
			if (null != tagVal) {
				return tagVal;
			}
		}
		if (BinaryXMLDictionary.isUnknownTag(tagName)) {
			return BinaryXMLDictionary.decodeUnknownTag(tagName);
		}
		return null;
	}
	
	/**
	 * Pull data form our dictionary stack in order. Return the first non-null value,
	 * or null if nobody knows this name. (Option: handle unknown tags here.) 
	 * @param tagVal
	 * @return
	 */
	public String tagToString(long tagVal) {
		String tagName = null;
		for (BinaryXMLDictionary dictionary : _dictionary) {
			tagName = dictionary.tagToString(tagVal);
			if (null != tagName) {
				return tagName;
			}
		}
		// safe to always map to a string; only need to return null in other direction so
		// that raw string can be encoded
		return BinaryXMLDictionary.unknownTagMarker(tagVal);
	}
	
	public BinaryXMLDictionary popXMLDictionary() {
		return _dictionary.pop();
	}

	public void pushXMLDictionary(BinaryXMLDictionary dictionary) {
		_dictionary.push(dictionary);
	}
}
