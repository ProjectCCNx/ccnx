package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * Represents repo policy data
 */
public class PolicyXML extends GenericXMLEncodable implements XMLEncodable {
	
	public static class PolicyObject extends CCNEncodableObject<PolicyXML> {
		
		protected RepositoryStore _repo = null;	// Non null if we are saving from within a repository
		
		public PolicyObject(ContentName name, PolicyXML data, CCNHandle handle, RepositoryStore repo) throws IOException {
			super(PolicyXML.class, true, name, data, handle);
			_repo = repo;
		}
		
		public PolicyObject(ContentName name, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(PolicyXML.class, true, name, (PublisherPublicKeyDigest)null, handle);
		}
		
		public PolicyXML policyXML() throws ContentNotReadyException, ContentGoneException {
			return data();
		}
		
		protected synchronized void createFlowController() throws IOException {
			if (null != _repo)
				_flowControl = new RepositoryInternalFlowControl(_repo, _handle);
			super.createFlowController();
		}
	}
	
	protected static final String POLICY_OBJECT_ELEMENT = "Policy";
	
	private interface ElementPutter {
		public void put(PolicyXML pxml, String value);
	}
	
	private enum PolicyElement {
		VERSION (POLICY_VERSION, new VersionPutter()),
		NAMESPACE (POLICY_NAMESPACE, new NameSpacePutter()),
		LOCALNAME (POLICY_LOCALNAME, new LocalNamePutter()),
		GLOBALPREFIX (POLICY_GLOBALPREFIX, new GlobalPrefixPutter());
		
		private String _stringValue;
		private ElementPutter _putter;
		
		PolicyElement(String stringValue, ElementPutter putter) {
			_stringValue = stringValue;
			_putter = putter;
		}
	}
	
	private static class VersionPutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) {
			pxml._version = value.trim();
		}
	}
	private static class GlobalPrefixPutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) {
			pxml._globalPrefix = value.trim();
		}
	}
	private static class LocalNamePutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) {
			pxml._localName = value.trim();
		}
	}
	private static class NameSpacePutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) {
			if (null == pxml._nameSpace)
				pxml._nameSpace = new ArrayList<String>();
			pxml._nameSpace.add(value.trim());
		}
	}
	
	protected static final String POLICY_VERSION = "PolicyVersion";
	protected static final String POLICY_NAMESPACE = "Namespace";
	protected static final String POLICY_GLOBALPREFIX = "GlobalPrefix";
	protected static final String POLICY_LOCALNAME = "LocalName";
	
	protected String _version = null;
	protected String _globalPrefix = null;
	protected String _localName = null;
	
	protected ArrayList<String> _nameSpace = new ArrayList<String>(0);

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		PolicyElement foundElement;
		do {
			foundElement = null;
			for (PolicyElement element : PolicyElement.values()) {
				if (decoder.peekStartElement(element._stringValue)) {
					foundElement = element;
					break;
				}		
			}
			if (null != foundElement) {
				String value = decoder.readUTF8Element(foundElement._stringValue);
				foundElement._putter.put(this, value);
				Log.fine("Found policy element {0} with value {1}", foundElement._stringValue, value);
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
		encoder.writeElement(POLICY_VERSION, _version);	
		encoder.writeElement(POLICY_LOCALNAME, _localName);	
		encoder.writeElement(POLICY_GLOBALPREFIX, _globalPrefix);	
		
		if (null != _nameSpace) {
			synchronized (_nameSpace) {
				for (String name : _nameSpace)
					encoder.writeElement(POLICY_NAMESPACE, name);
			}
		}
		encoder.writeEndElement();   			
	}

	@Override
	public String getElementLabel() {
		return POLICY_OBJECT_ELEMENT;
	}

	@Override
	public boolean validate() {
		return null != _version;
	}
}
