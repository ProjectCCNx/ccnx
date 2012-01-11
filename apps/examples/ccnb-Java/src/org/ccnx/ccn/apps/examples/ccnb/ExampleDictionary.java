/*
 * A CCNx example of extending ccnb encoding/decoding.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */


package org.ccnx.ccn.apps.examples.ccnb;

import org.ccnx.ccn.impl.encoding.BinaryXMLDictionary;
import org.ccnx.ccn.impl.encoding.XMLDictionary;

/**
 * This class is used to insure that the example XML dictionary stuff get's loaded.
 */
public class ExampleDictionary implements XMLDictionary {
	private static ExampleDictionary _defaultInstance = new ExampleDictionary();
	
	public static ExampleDictionary getDefaultInstance() { return _defaultInstance; }
	
	private ExampleDictionary() {}
	
	/**
	 * Load the example dictionary globally.  This code should  be in the class
	 * that makes use of the example types.
	 */
	static {
		BinaryXMLDictionary.pushGlobalXMLDictionary(ExampleDictionary.getDefaultInstance());
	}
	

	@Override
	public Long stringToTag(String tag) {
		Long tagVal = null;
		try {
			tagVal = ExampleDTags.stringToTag(tag);
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
	@Override
	public String tagToString(long tagVal) {
		return ExampleDTags.tagToString(tagVal);

	}
	

}
