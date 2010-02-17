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
package org.ccnx.ccn.impl.encoding;

/**
 * Encapsulates the mapping from textual XML element and attribute names to the ccnb binary encoding
 * of those elements and attributes.
 * 
 * Remove auto-loading of text dictionary, as it was making encode/decode too slow.
 * Instead, to make a new dictionary, subclass this class and load it with your
 * constant tag/label data. If you want to use a text dictionary directly,
 * use FileBinaryXMLDictionary.
 *
 * @see BinaryXMLCodec
 */
public abstract class BinaryXMLDictionary {
	
	public static final String UNKNOWN_TAG_MARKER = "UNKNOWN TAG: ";

	public abstract Long stringToTag(String tag);
	
	public abstract String tagToString(long tagVal);
	
	public static BinaryXMLDictionary getDefaultDictionary() {
		return CCNProtocolDictionary.getDefaultDictionary();
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

}
