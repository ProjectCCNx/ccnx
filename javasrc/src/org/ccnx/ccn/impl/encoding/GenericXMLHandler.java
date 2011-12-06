/*
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

	protected Stack<BinaryXMLDictionary> _dictionaryStack = null;

	public GenericXMLHandler() {
		this(null);
	}

	public GenericXMLHandler(BinaryXMLDictionary dictionary) {
		if (null != dictionary)
			pushXMLDictionary(dictionary);
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
		if (null != _dictionaryStack) {
			for (BinaryXMLDictionary dictionary : _dictionaryStack) {
				tagVal = dictionary.stringToTag(tagName);
				if (null != tagVal) {
					return tagVal;
				}
			}
		}
		
		for (BinaryXMLDictionary dictionary : BinaryXMLDictionary.getGlobalDictionaries()) {
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
		
		if (null != _dictionaryStack) {
			for (BinaryXMLDictionary dictionary : _dictionaryStack) {
				tagName = dictionary.tagToString(tagVal);
				if (null != tagName) {
					return tagName;
				}
			}
		}

		for (BinaryXMLDictionary dictionary : BinaryXMLDictionary.getGlobalDictionaries()) {
			tagName = dictionary.tagToString(tagVal);
			if (null != tagName) {
				return tagName;
			}
		}


		// safe to always map to a string; only need to return null in other direction so
		// that raw string can be encoded
		return BinaryXMLDictionary.unknownTagMarker(tagVal);
	}
	
	/**
	 * Push an XML dictionary for use by this encoder or decoder instance only. This 
	 * dictionary takes priority over any global dictionaries loaded using 
	 * BinaryXMLDictionary.pushGlobalXMLDictionary and shadows any matching entries.
	 * Pushes even if dictionary is on the stack, to make it easier to keep track of order.
	 * @param dictionary
	 */
	public void pushXMLDictionary(BinaryXMLDictionary dictionary) {
		if (null == _dictionaryStack) {
			_dictionaryStack = new Stack<BinaryXMLDictionary>();
		}
		_dictionaryStack.push(dictionary);
	}
	
	/**
	 * Pop top XML dictionary from the stack used by this encoder or decoder instance only. 
	 * @return the dictionary it popped if it popped one, otherwise null.
	 */
	public BinaryXMLDictionary popXMLDictionary() {
		if (null == _dictionaryStack) {
			return null;
		}
		return _dictionaryStack.pop();
	}
}
