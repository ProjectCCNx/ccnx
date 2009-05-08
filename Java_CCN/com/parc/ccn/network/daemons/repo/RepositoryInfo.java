package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.util.BinaryXMLDictionary;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * 
 * @author rasmusse
 *
 */

public class RepositoryInfo extends GenericXMLEncodable implements XMLEncodable{
	protected static String _version = "1.0";
	
	protected String _repoVersion = null;
	protected String _localName = null;
	protected String _globalPrefix = null;
	protected ArrayList<ContentName> _names = new ArrayList<ContentName>();
	protected ContentName _policyName;
	protected RepoInfoType _type = RepoInfoType.INFO;
	
	protected static String DEFAULT_DICTIONARY_RESNAME = "repotags.cvsdict";
	
	private static BinaryXMLDictionary _dictionary;
	
	private static final String REPO_VERSION_ELEMENT = "RepositoryVersion";
	private static final String REPO_INFO_VERSION_ELEMENT = "RepositoryInfoVersion";
	private static final String REPO_INFO_TYPE_ELEMENT = "RepositoryInfoType";
	private static final String LOCAL_NAME_ELEMENT = "RepositoryName";
	private static final String GLOBAL_PREFIX_ELEMENT = "RepositoryPrefix";
	
	public enum RepoInfoType {
		INFO ("INFO"),
		DATA ("DATA"),
		UNKNOWN();
		
		private String _stringValue = null;
		
		RepoInfoType() {}
		
		RepoInfoType(String stringValue) {
			this._stringValue = stringValue;
		}
		
		static RepoInfoType valueFromString(String value) {
			for (RepoInfoType pv : RepoInfoType.values()) {
				if (pv._stringValue != null) {
					if (pv._stringValue.equals(value.toUpperCase()))
						return pv;
				}
			}
			return UNKNOWN;
		}
	}
	
	protected static final HashMap<RepoInfoType, String> _InfoTypeNames = new HashMap<RepoInfoType, String>();
	
	static {
		try {
			_dictionary = new BinaryXMLDictionary(DEFAULT_DICTIONARY_RESNAME);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public RepositoryInfo(String localName, String globalPrefix, String version) throws MalformedContentNameStringException {
		_localName = localName;
		_repoVersion = version;
		_globalPrefix = globalPrefix;
		if (!_globalPrefix.startsWith("/"))
			_globalPrefix = "/" + _globalPrefix;
		_policyName = ContentName.fromNative(_globalPrefix + '/' + _localName 
				+ '/' + Repository.REPO_DATA + '/' + Repository.REPO_POLICY);
	}
	
	public RepositoryInfo(String localName, String globalPrefix, String version, ArrayList<ContentName> names) throws MalformedContentNameStringException {
		this(localName, globalPrefix, version);
		for (ContentName name : names) {
			_names.add(name.clone());
		}
		_type = RepoInfoType.DATA;
	}
	
	public RepositoryInfo() {}	// For decoding
	
	public String getLocalName() {
		return _localName;
	}
	
	public String getGlobalPrefix() {
		return _globalPrefix;
	}
	
	public ContentName getPolicyName() {
		return _policyName;
	}
	
	public ArrayList<ContentName> getNames() {
		return _names;
	}
	
	public RepoInfoType getType() {
		return _type;
	}
	
	public String getRepoVersion() {
		return _repoVersion;
	}
	
	public String getVersion() {
		return _version;
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.pushXMLDictionary(_dictionary);
		if (!decoder.peekStartElement(REPO_INFO_TYPE_ELEMENT)) {
			_type = RepoInfoType.UNKNOWN;
			return;
		}
		_type = RepoInfoType.valueFromString(new String(decoder.readBinaryElement(REPO_INFO_TYPE_ELEMENT)));
		_version = new String(decoder.readBinaryElement(REPO_INFO_VERSION_ELEMENT));
		_repoVersion = new String(decoder.readBinaryElement(REPO_VERSION_ELEMENT));
		_globalPrefix = new String(decoder.readBinaryElement(GLOBAL_PREFIX_ELEMENT));
		_localName = new String(decoder.readBinaryElement(LOCAL_NAME_ELEMENT));
		decoder.popXMLDictionary();
		while (decoder.peekStartElement(ContentName.CONTENT_NAME_ELEMENT)) {
			ContentName name = new ContentName();
			name.decode(decoder);
			_names.add(name);
		}
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		encoder.pushXMLDictionary(_dictionary);
		encoder.writeElement(REPO_INFO_TYPE_ELEMENT, getType()._stringValue.getBytes());
		encoder.writeElement(REPO_INFO_VERSION_ELEMENT, _version.getBytes());
		encoder.writeElement(REPO_VERSION_ELEMENT, _repoVersion.getBytes());
		encoder.writeElement(GLOBAL_PREFIX_ELEMENT, _globalPrefix.getBytes());
		encoder.writeElement(LOCAL_NAME_ELEMENT, _localName.getBytes());
		encoder.popXMLDictionary();
		if (_names.size() > 0) {
			for (ContentName name : _names)
				name.encode(encoder);
		}
	}

	public boolean validate() {
		return true;
	}
}
