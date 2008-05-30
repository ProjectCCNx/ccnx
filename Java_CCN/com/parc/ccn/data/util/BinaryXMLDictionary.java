package com.parc.ccn.data.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;

import com.parc.ccn.Library;

public class BinaryXMLDictionary {
	
	// Should not necessarily tie this to CCN...
	protected static String DEFAULT_DICTIONARY_RESNAME = "tagname.csvdict";
	
	protected String _dictionaryFileName;
	protected HashMap<String,Long> _encodingDictionary = new HashMap<String,Long>();
	protected HashMap<Long,String> _decodingDictionary = new HashMap<Long,String>();
	
	protected static BinaryXMLDictionary DEFAULT_DICTIONARY = null;
	
	static {
		DEFAULT_DICTIONARY = new BinaryXMLDictionary();
	}
	
	public static BinaryXMLDictionary getDefaultDictionary() {
		return DEFAULT_DICTIONARY;
	}
	
	public BinaryXMLDictionary(String dictionaryFile) throws IOException {
		loadDictionaryFile(dictionaryFile);
	}

	public BinaryXMLDictionary() {
		try {
			loadDictionaryFile(DEFAULT_DICTIONARY_RESNAME);
		} catch (IOException fe) {
			Library.logger().warning("Cannot parse default CCN encoding dictionary: " + DEFAULT_DICTIONARY_RESNAME + ":" + 
					fe.getMessage());
			
		}
	}
	
	public long encodeTag(String tag) {
		Long value = _encodingDictionary.get(tag);
		if (null == value)
			return -1;
		return value.longValue();
	}
	
	public String decodeTag(long tagVal) {
		String tag = _decodingDictionary.get(Long.valueOf(tagVal));
		return tag;
	}
	
	// DKS TODO -- do attributes use the same dictionary entries?
	public long encodeAttr(String attr) {
		Long value = _encodingDictionary.get(attr);
		if (null == value)
			return -1;
		return value.longValue();
	}
	
	public String decodeAttr(long tagVal) {
		String tag = _decodingDictionary.get(Long.valueOf(tagVal));
		return tag;
	}

	protected void loadDictionaryFile(String dictionaryFile) throws IOException {
		
		InputStream in = getClass().getResourceAsStream(dictionaryFile);
		BufferedReader reader = 
			new BufferedReader(new InputStreamReader(in));
		
		String line = null;
		
		while (reader.ready()) {
			line = reader.readLine();
			String [] parts = line.split(",");
			
			if (parts.length != 2) {
				if (parts.length != 0) // if 0, just empty line
					Library.logger().info("Dictionary: " + dictionaryFile + ":  Cannot parse dictionary line: " + line);
				continue;
			} 
			
			Long value = Long.valueOf(parts[0]);
			String tag = parts[1];
			
			_encodingDictionary.put(tag, value);
			_decodingDictionary.put(value, tag);
		}
		
	}
}
