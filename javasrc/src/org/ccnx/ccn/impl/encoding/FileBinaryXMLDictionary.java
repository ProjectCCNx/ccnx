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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;

import org.ccnx.ccn.impl.support.Log;

/**
 * Encapsulates the mapping from textual XML element and attribute names to the ccnb binary encoding
 * of those elements and attributes.
 * 
 * This type encapsulates a dictionary loaded from a file.
 * @see BinaryXMLCodec
 */
public class FileBinaryXMLDictionary implements XMLDictionary {
	
	// Should not necessarily tie this to CCN...
	protected static String DEFAULT_DICTIONARY_RESNAME = "tagname.csvdict";
	
	protected String _dictionaryFileName;
	protected HashMap<String,Long> _encodingDictionary = new HashMap<String,Long>();
	protected HashMap<Long,String> _decodingDictionary = new HashMap<Long,String>();
		
	public FileBinaryXMLDictionary(String dictionaryFile) throws IOException {
		loadDictionaryFile(dictionaryFile);
	}

	public FileBinaryXMLDictionary() {
		try {
			loadDictionaryFile(DEFAULT_DICTIONARY_RESNAME);
		} catch (IOException fe) {
			Log.warning(Log.FAC_ENCODING, "Cannot parse default CCN encoding dictionary: " + DEFAULT_DICTIONARY_RESNAME + ":" + 
					fe.getMessage());
		}
	}
	
	public FileBinaryXMLDictionary(InputStream dictionaryStream) throws IOException {
		loadDictionary(dictionaryStream);
	}
	
	public Long stringToTag(String tag) {
		return _encodingDictionary.get(tag); // caller handles null
	}
	
	public String tagToString(long tagVal) {
		return _decodingDictionary.get(Long.valueOf(tagVal)); // caller handles null
	}
	
	protected void loadDictionaryFile(String dictionaryFile) throws IOException {
		
		if (null == dictionaryFile) 
			throw new IOException("BinaryXMLDictionary: dictionary file name cannot be null!");
		
		InputStream in = getClass().getResourceAsStream(dictionaryFile);
		
		if (null == in) {
			throw new IOException("BinaryXMLDictionary: getResourceAsStream cannot open resource file: " + dictionaryFile + ".");
		}
		loadDictionary(in);
		
		in.close();
	}
	
	protected void loadDictionary(InputStream in) throws IOException {
		if (null == in) {
			throw new IOException("BinaryXMLDictionary: loadDictionary - stream cannot be null.");
		}
		BufferedReader reader = 
			new BufferedReader(new InputStreamReader(in), 8196);
		
		String line = null;
		final int NULLCOUNT_MAX = 20;
		int nullcount = 0; // deal with platforms where reader.ready doesn't work. allow some number of blank
						   // lines, then decide we've had a problem
		
		while (reader.ready() && (nullcount < NULLCOUNT_MAX)) {
			line = reader.readLine();
			if (null == line) {
				nullcount++;
				continue;
			}
			nullcount = 0;
			String [] parts = line.split(",");
			
			// Format: <num>,<name>[,<modifier>]  where <modifier> is one of Deprecated or Obsolete
			if (parts.length > 3) {
				if (parts.length != 0) // if 0, just empty line
					Log.info("Cannot parse dictionary line: " + line);
				continue;
			} 
			
			if ((parts.length == 3) && ((parts[2].equals("Deprecated") || (parts[2].equals("Obsolete"))))) {
				continue; // skip old stuff
			}
			Long value = Long.valueOf(parts[0]);
			String tag = parts[1];
			
			_encodingDictionary.put(tag, value);
			_decodingDictionary.put(value, tag);
		}
		if (nullcount >= NULLCOUNT_MAX) {
			Log.info("Finished reading dictionary file because we either read too many blank lines, or our reader couldn't decide it was done. Validate reading on this platform.");
		}
	}
}
