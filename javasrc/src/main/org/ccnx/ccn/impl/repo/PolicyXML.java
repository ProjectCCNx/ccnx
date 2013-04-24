/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2011, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * Represents repo policy data
 */
public class PolicyXML extends GenericXMLEncodable implements XMLEncodable {
	
	public static class PolicyObject extends CCNEncodableObject<PolicyXML> {
		
		/**
		 * Read constructor
		 */
		public PolicyObject(ContentName name, CCNHandle handle) 
		throws ContentDecodingException, IOException {
			super(PolicyXML.class, true, name, (PublisherPublicKeyDigest)null, handle);
		}

		/**
		 * Write constructor.
		 */
		public PolicyObject(ContentName name,
				PolicyXML data, SaveType saveType, CCNHandle handle)
				throws IOException {
			super(PolicyXML.class, true, name, data, saveType, handle);
		}

		/**
		 * Write constructor.
		 */
		public PolicyObject(ContentName name,
				PolicyXML data, PublisherPublicKeyDigest publisher,
				KeyLocator keyLocator, CCNFlowControl flowControl)
				throws IOException {
			super(PolicyXML.class, true, name, data, publisher, keyLocator, flowControl);
		}
		
		public PolicyXML policyInfo() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
	}
	
	/**
	 * The following interface and enumeration allow user created policy files with the
	 * data in any order. The encoder goes through the user's names, checks for matches with
	 * the names from the enumeration and encodes the name it sees appropriately
	 */
	private interface ElementPutter {
		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException;
	}
	
	private enum PolicyElement {
		VERSION (CCNProtocolDTags.PolicyVersion, new VersionPutter()),
		NAMESPACE (CCNProtocolDTags.Namespace, new NameSpacePutter()),
		LOCALNAME (CCNProtocolDTags.LocalName, new LocalNamePutter()),
		GLOBALPREFIX (CCNProtocolDTags.GlobalPrefix, new GlobalPrefixPutter());
		
		private final int _tagValue;
		private ElementPutter _putter;
		
		PolicyElement(int tagValue, ElementPutter putter) {
			_tagValue = tagValue;
			_putter = putter;
		}
		
		public long getTagValue() { return _tagValue; }
	}
	
	private static class VersionPutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException {
			pxml._version = value.trim();
		}
	}
	private static class GlobalPrefixPutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException {
			pxml.setGlobalPrefix(value.trim());
		}
	}
	private static class LocalNamePutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException {
			pxml._localName = value.trim();
		}
	}
	private static class NameSpacePutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException {
			pxml.addNamespace(ContentName.fromNative(value.trim()));
		}
	}
	
	protected String _version = null;
	protected ContentName _globalPrefix = null;
	protected String _localName = null;
	
	protected ArrayList<ContentName> _namespace = new ArrayList<ContentName>();

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		PolicyElement foundElement;
		do {
			foundElement = null;
			// Don't need to back up the stream 4 times for every read...
			// Probably can improve this yet more given a bit of time.
			Long startElement = decoder.peekStartElementAsLong();
			
			if (null == startElement) {
				break;
			}
			
			long startElementVal = startElement.longValue();
			
			for (PolicyElement element : PolicyElement.values()) {
				if (element.getTagValue() == startElementVal) {
					foundElement = element;
					break;
				}		
			}
			if (null != foundElement) {
				String value = decoder.readUTF8Element(foundElement.getTagValue());
				try {
					foundElement._putter.put(this, value);
				} catch (MalformedContentNameStringException e) {
					throw new ContentDecodingException(e.getMessage());
				}
				Log.fine(Log.FAC_REPO, "Found policy element {0} with value {1}", foundElement.getTagValue(), value);
			}
		} while (null != foundElement);
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		encoder.writeElement(CCNProtocolDTags.PolicyVersion, _version);	
		encoder.writeElement(CCNProtocolDTags.LocalName, _localName);
		encoder.writeElement(CCNProtocolDTags.GlobalPrefix, _globalPrefix.toString());
		
		if (null != _namespace) {
			synchronized (_namespace) {
				for (ContentName name : _namespace)
					encoder.writeElement(CCNProtocolDTags.Namespace, name.toString());
			}
		}
		encoder.writeEndElement();   			
	}
	
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_version == null) ? 0 : _version.hashCode());
		result = PRIME * result + ((_localName == null) ? 0 : _localName.hashCode());
		result = PRIME * result + ((_globalPrefix == null) ? 0 : _globalPrefix.hashCode());
		result = PRIME * result + _namespace.hashCode();
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
		final PolicyXML other = (PolicyXML) obj;
		if (!_version.equals(other._version))
			return false;
		if (!_localName.equals(other._localName))
			return false;
		if (!_globalPrefix.equals(other._globalPrefix))
			return false;
		if (_namespace == null) {
			return (other._namespace == null);
		}
		if (other._namespace == null)
			return false;
		if (_namespace.size() != other._namespace.size())
			return false;
		ContentName[] ourNamespace = new ContentName[_namespace.size()];
		ContentName[] otherNamespace = new ContentName[_namespace.size()];
		_namespace.toArray(ourNamespace);
		other._namespace.toArray(otherNamespace);
		Arrays.sort(ourNamespace);
		Arrays.sort(otherNamespace);
		if (!Arrays.equals(ourNamespace, otherNamespace))
			return false;
		return true;
	}

	@Override
	public long getElementLabel() {
		return CCNProtocolDTags.Policy;
	}

	@Override
	public boolean validate() {
		return null != _version;
	}
	
	@SuppressWarnings("unchecked")
	public synchronized ArrayList<ContentName> getNamespace() {
		return (ArrayList<ContentName>)_namespace.clone();
	}
	
	public synchronized void setNamespace(ArrayList<ContentName> namespace) {
		_namespace = namespace;
	}
	
	public synchronized void addNamespace(ContentName name) {
		if (null == _namespace)
			_namespace = new ArrayList<ContentName>();
		for (ContentName n : _namespace) {
			if (n.equals(name))
				return;
		}
		_namespace.add(name);
	}
	
	public synchronized void removeNamespace(ContentName name) {
		if (null != _namespace)
			_namespace.remove(name);
	}
	
	public synchronized void setLocalName(String localName) {
		_localName = localName;
	}
	
	public synchronized String getLocalName() {
		return _localName;
	}
	
	public synchronized void setGlobalPrefix(String globalPrefix) throws MalformedContentNameStringException {
		if (null != _globalPrefix)
			_namespace.remove(_globalPrefix);
		_globalPrefix = ContentName.fromNative(fixSlash(globalPrefix));
		addNamespace(_globalPrefix);
	}
	
	/**
	 * This is a special case for transferring one policyXML to another (so we already have the
	 * namespace setup correctly).
	 * 
	 * @param globalPrefix
	 */
	public synchronized void setGlobalPrefixOnly(ContentName globalPrefix) {
		_globalPrefix = globalPrefix;
	}
	
	public synchronized ContentName getGlobalPrefix() {
		return _globalPrefix;
	}
	
	public synchronized void setVersion(String version) {
		_version = version;
	}
	
	public synchronized String getVersion() {
		return _version;
	}
	
	/**
	 * Global prefix names are not required to start with a slash. Just add one
	 * here if it doesn't
	 * @param name - the test name
	 * @return
	 */
	public static String fixSlash(String name) {
		if (!name.startsWith("/"))
			name = "/" + name;
		return name;
	}
}
