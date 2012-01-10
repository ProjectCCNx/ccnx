/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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
 * Uses the CCNProtocolDTags enum type to implement a dictionary.
 */
public class CCNProtocolDictionary implements  XMLDictionary {
	
	private static CCNProtocolDictionary _defaultInstance = new CCNProtocolDictionary();
	
	public static CCNProtocolDictionary getDefaultInstance() { return _defaultInstance; }
	
	/**
	 * Use getDefaultInstance()
	 */
	private CCNProtocolDictionary() {}

	public Long stringToTag(String tag) {
		Long tagVal = null;
		try {
			tagVal = CCNProtocolDTags.stringToTag(tag);
			if (null != tagVal) {
				return tagVal;
			}
		} catch (IllegalArgumentException e) {
			// do nothing
		} 
		return null; // no tag with that name
	}

	/**
	 * This is the slow way, but we should only have to do this if printing things
	 * out as text...
	 */
	public String tagToString(long tagVal) {
		return CCNProtocolDTags.tagToString(tagVal);
	}
}
