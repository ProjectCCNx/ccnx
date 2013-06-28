/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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

/**
 * This class is now only used for the _globalDictionaries static
 * member and the UnknownTag methods.  It does not actually performany any dictionary function 
 * (as it does not extend XMLDictionary).
 * 
 * If you want a user-defined dictionary, create a class that extends XMLDictionary.  See
 * CCNProtocolDictionary (for an code-defined example) or FileBinaryXmlDictionary (for an
 * external file defined example).
 * 
 * If you want to use a text dictionary directly, use FileBinaryXMLDictionary.
 * 
 * TODO:
 * - We should encapsulate the dictionary lookup in this class, rather than have other classes
 *   deal with how to iterate through the dictionaries.
 *
 * @see BinaryXMLCodec XMLDictionary
 */
public class XMLDictionaryStack {
	
	// Have a static dictionary stack shared by all code running in the same JVM.
	// This can be added to by programs. Can also add local dictionaries to decoders 
	// and encoders using methods in GenericXMLHandler.
	protected static Stack<XMLDictionary> _globalDictionaries = new Stack<XMLDictionary>();
	
	public static final String UNKNOWN_TAG_MARKER = "UNKNOWN TAG: ";
	
	static {
		_globalDictionaries.push(getDefaultDictionary());
	}
	
	public static XMLDictionary getDefaultDictionary() {
		return CCNProtocolDictionary.getDefaultInstance();
	}

	/**
	 * Don't call new on this.  Use the static methods.
	 */
	private XMLDictionaryStack () {
		
	}
	
	public static Long decodeUnknownTag(String tagStr) {
		if (!isUnknownTag(tagStr)) {
			return null;
		}
		String tag = tagStr.substring(UNKNOWN_TAG_MARKER.length());
		return Long.valueOf(tag);
	}

	public static boolean isUnknownTag(String tagStr) {
		return ((null == tagStr) ? false : tagStr.startsWith(UNKNOWN_TAG_MARKER));
	}

	/**
	 * Encoding for unknown binary tags. Reversible.
	 */
	public static String unknownTagMarker(long tag) {
		return UNKNOWN_TAG_MARKER + tag;
	}

	/**
	 * Push an XML dictionary onto stack used by all applications in this JVM.
	 * This stack is pre-loaded with the default dictionary.
	 * For local dictionaries, see GenericXMLHandler.pushXMLDictionary.
	 * @return
	 */
	public static void pushGlobalXMLDictionary(XMLDictionary dictionary) {
		_globalDictionaries.push(dictionary);
	}

	/**
	 * Pop an XML dictionary onto stack used by all applications in this JVM.
	 * Will not pop the default dictionary off the bottom of the stack.
	 * For local dictionaries, see GenericXMLHandler.popXMLDictionary.
	 * @return the dictionary it popped if it popped one, otherwise null.
	 */
	public static XMLDictionary popGlobalXMLDictionary() {
		if (_globalDictionaries.size() > 1) {
			return _globalDictionaries.pop();
		}
		return null;
	}

	public static Stack<XMLDictionary> getGlobalDictionaries() { return _globalDictionaries; }

}
