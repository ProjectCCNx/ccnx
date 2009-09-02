package org.ccnx.ccn.impl.repo;

import java.util.ArrayList;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

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
	
	private static final String REPOSITORY_INFO_ELEMENT = "RepositoryInfo";
	private static final String REPOSITORY_INFO_TYPE_ELEMENT = "Type";
	private static final String REPOSITORY_INFO_VERSION_ELEMENT = "Version";
	private static final String REPOSITORY_VERSION_ELEMENT = "RepositoryVersion";
	private static final String GLOBAL_PREFIX_ELEMENT = "GlobalPrefix";
	private static final String LOCAL_NAME_ELEMENT = "LocalName";
	
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
	
	public RepositoryInfo(String version, String globalPrefix, String localName) {
		_localName = localName;
		_repoVersion = version;
		_globalPrefix = globalPrefix;
		if (!_globalPrefix.startsWith("/"))
			_globalPrefix = "/" + _globalPrefix;
	}
	
	public RepositoryInfo(String version, String globalPrefix, String localName, ArrayList<ContentName> names) {
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
	
	public synchronized ContentName getPolicyName() throws RepositoryException {
		if (null == _policyName) {
			try {
				_policyName = ContentName.fromNative(_globalPrefix + '/' + _localName 
						+ '/' + Repository.REPO_DATA + '/' + Repository.REPO_POLICY);
			} catch (MalformedContentNameStringException e) {
				throw new RepositoryException("Cannot set policy name for repository based on configuration parameters.",
												e);
			}
		}
		return _policyName;
	}
	
	public ArrayList<ContentName> getNames() {
		return _names;
	}
	
	public RepoInfoType getType() {
		return _type;
	}
	
	public String getRepositoryVersion() {
		return _repoVersion;
	}
	
	public String getVersion() {
		return _version;
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(REPOSITORY_INFO_ELEMENT);
		if (!decoder.peekStartElement(REPOSITORY_INFO_TYPE_ELEMENT)) {
			// This is a bad idea. It silently leaves a wad of stuff on the stream.
			// Better to throw an exception so caller knows something is wrong.
			// If you want to punt, need to skip rest of object. Isn't useful,
			// version should come first, and be checked.
			_type = RepoInfoType.UNKNOWN;
			return;
		}
		_type = RepoInfoType.valueFromString(decoder.readUTF8Element(REPOSITORY_INFO_TYPE_ELEMENT));
		_version = decoder.readUTF8Element(REPOSITORY_INFO_VERSION_ELEMENT);
		_repoVersion = decoder.readUTF8Element(REPOSITORY_VERSION_ELEMENT);
		_globalPrefix = decoder.readUTF8Element(GLOBAL_PREFIX_ELEMENT);
		_localName = decoder.readUTF8Element(LOCAL_NAME_ELEMENT);
		while (decoder.peekStartElement(ContentName.CONTENT_NAME_ELEMENT)) {
			ContentName name = new ContentName();
			name.decode(decoder);
			_names.add(name);
		}
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(REPOSITORY_INFO_ELEMENT);
		encoder.writeElement(REPOSITORY_INFO_TYPE_ELEMENT, getType().toString());
		encoder.writeElement(REPOSITORY_INFO_VERSION_ELEMENT, _version);
		encoder.writeElement(REPOSITORY_VERSION_ELEMENT, _repoVersion);
		// Should these be names?
		encoder.writeElement(GLOBAL_PREFIX_ELEMENT, _globalPrefix);
		encoder.writeElement(LOCAL_NAME_ELEMENT, _localName);
		if (_names.size() > 0) {
			for (ContentName name : _names)
				name.encode(encoder);
		}
		encoder.writeEndElement();
	}

	public boolean validate() {
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((_globalPrefix == null) ? 0 : _globalPrefix.hashCode());
		result = prime * result
				+ ((_localName == null) ? 0 : _localName.hashCode());
		result = prime * result + ((_names == null) ? 0 : _names.hashCode());
		result = prime * result
				+ ((_policyName == null) ? 0 : _policyName.hashCode());
		result = prime * result
				+ ((_repoVersion == null) ? 0 : _repoVersion.hashCode());
		result = prime * result + ((_type == null) ? 0 : _type.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		RepositoryInfo other = (RepositoryInfo) obj;
		if (_globalPrefix == null) {
			if (other._globalPrefix != null)
				return false;
		} else if (!_globalPrefix.equals(other._globalPrefix))
			return false;
		if (_localName == null) {
			if (other._localName != null)
				return false;
		} else if (!_localName.equals(other._localName))
			return false;
		if (_names == null) {
			if (other._names != null)
				return false;
		} else if (!_names.equals(other._names))
			return false;
		if (_policyName == null) {
			if (other._policyName != null)
				return false;
		} else if (!_policyName.equals(other._policyName))
			return false;
		if (_repoVersion == null) {
			if (other._repoVersion != null)
				return false;
		} else if (!_repoVersion.equals(other._repoVersion))
			return false;
		if (_type == null) {
			if (other._type != null)
				return false;
		} else if (!_type.equals(other._type))
			return false;
		return true;
	}
}
