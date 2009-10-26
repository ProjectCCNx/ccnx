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

package org.ccnx.ccn.impl.repo;

import java.io.InputStream;
import java.util.ArrayList;

import org.ccnx.ccn.impl.encoding.TextXMLCodec;
import org.ccnx.ccn.impl.encoding.XMLCodecFactory;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 * A reference implementation of Policy using an XML based policy file
 * 
 * TODO policy change requests via the network are currently totally insecure and could easily be 
 * exploited to disrupt the proper functioning of a repository.
 * 
 * The policy file is structured as follows:
 * 
 * All elements must be nested within a <policy> element.<p>
 * 
 * The file must contain a <version> element with an id attribute set to a string
 * corresponding to the current version of the repository which is "1.4".<p>
 * 
 * The file must contain a <localname> element which specifies the local name.<p>
 * The file must contain a <globalname> element which specifies the global name.<p>
 * The file may contain any number of <namespace> elements specifying namespaces<p>
 * covered by the repository
 * 
 * <pre>
 * For example:
 * 	<Policy>
 *		<PolicyVersion> 1.5 </PolicyVersion>
 *		<LocalName> TestRepository </LocalName>
 *		<GlobalPrefix> parc.com/csl/ccn/repositories </GlobalPrefix>
 *		<Namespace> /testNameSpace </Namespace>
 *		<Namespace> /testNameSpace2 </Namespace>
 * 	</Policy>
 * </pre>
 * 
 */
public class BasicPolicy implements Policy {
	
	public static final String POLICY = "POLICY";
	public String POLICY_VERSION = "1.5";

	
	private byte [] _content = null;
	private ContentName _globalPrefix = null;
	private String _localName = null;
	
	protected String _repoVersion = null;	// set from repo	
	private ArrayList<ContentName> _nameSpace = new ArrayList<ContentName>(0);
	
	/**
	 * Constructor defaulting the initial namespace to "everything"
	 * @param name local name for this repository
	 */
	public BasicPolicy(String name) {
		try {
			if (null != name)
				this._localName = name;
			_nameSpace.add(ContentName.fromNative("/"));
		} catch (MalformedContentNameStringException e) {}
	}
	
	/**
	 * Constructor allowing an initial namespace to be set
	 * @param name local name for this repository
	 * @param namespace the initial namespace
	 */
	@SuppressWarnings("unchecked")
	public BasicPolicy(String name, ArrayList<ContentName> namespace) {
		if (null != name) {
			this._localName = name;
		}
		if (null != namespace) {
			_nameSpace = (ArrayList<ContentName>) namespace.clone();
		}
	}
	
	public PolicyXML updateFromInputStream(InputStream stream) throws ContentDecodingException, RepositoryException {
		PolicyXML pxml = createPolicyXML(stream);
		update(pxml, false);
		return pxml;
	}
	
	/**
	 * Applies policy changes
	 * 
	 * @param pxml policy data
	 * @return
	 * @throws XMLStreamException
	 */
	public void update(PolicyXML pxml, boolean fromNet) throws RepositoryException {
		if (pxml._version == null)
			throw new RepositoryException("No version in policy file");
		if (!pxml._version.equals(POLICY_VERSION)) {
			Log.warning("Bad version in policy file: {0}", pxml._version);
			throw new RepositoryException("Bad version in policy file");
		}
		
		if (null == pxml._localName)
			throw new RepositoryException("No local name in policy file");
		if (fromNet) {
			if (!pxml._localName.equals(_localName)) {
				Log.warning("Repository local name doesn't match: request = {0}", pxml._localName);
				throw new RepositoryException("Repository local name doesn't match policy file");
			}
		} else {
			try {
				setLocalName(pxml._localName);
			} catch (MalformedContentNameStringException e) {
				throw new RepositoryException(e.getMessage());
			}
		}
		
		if (null == pxml._globalPrefix)
			throw new RepositoryException("No globalPrefix in policy file");
		
		if (fromNet) {
			try {
				if (!ContentName.fromNative(fixSlash(pxml._globalPrefix)).equals(_globalPrefix)) {
					Log.warning("Repository globalPrefix doesn't match: request = {0}", pxml._globalPrefix);
					throw new RepositoryException("Repository global prefix doesn't match policy file");
				}
			} catch (MalformedContentNameStringException e) {
				throw new RepositoryException(e.getMessage());
			}
		} else {
			try {
				changeGlobalPrefix(pxml._globalPrefix);
			} catch (MalformedContentNameStringException e) {
				Log.warning("Policy file contains invalid global prefix {0}", pxml._globalPrefix);
				throw new RepositoryException("Policy file specifies invalid global prefix");
			}
		}
			
		ArrayList<ContentName> tmpNameSpace = new ArrayList<ContentName>();
		for (String name : pxml._nameSpace) {
			try {
				tmpNameSpace.add(ContentName.fromNative(name));
			} catch (MalformedContentNameStringException e) {
				Log.warning("Policy file contains invalid namespace {0}", name);
				throw new RepositoryException("Policy file specifies invalid namespace");
			}
		}
		_nameSpace = tmpNameSpace;
		_nameSpace.add(_globalPrefix);
	}

	public ArrayList<ContentName> getNameSpace() {
		return _nameSpace;
	}
	
	public void setNameSpace(ArrayList<ContentName> nameSpace) {
		_nameSpace = nameSpace;
	}

	/**
	 * Gets the current policy as a ContentObject
	 * @return the policy content
	 */
	public ContentObject getPolicyContent() {
		// TODO WARNING: this code should not call a generic content builder meant for
		// making test content. The repository needs to have its own set of keys, manage
		// them and use them rather than using the default key manager (which will pull
		// keys from whatever user keystore that started the repo). The repo should build
		// a keystore for its own use, and when it starts up instantiate a key manager
		// that uses that keystore. That key manager should be used for all of its
		// operations.
		return ContentObject.buildContentObject(
				getPolicyName(_globalPrefix, _localName),
				_content);
	}
	
	/**
	 * Creates the path for a policy file for a repository given it's global prefix and local name
	 * 
	 * @param globalPrefix global prefix as a ContentName
	 * @param localName local name as a / separated String
	 * @return the name as a ContentName
	 */
	public static ContentName getPolicyName(ContentName globalPrefix, String localName) {
		return ContentName.fromNative(globalPrefix, new String[]{localName, RepositoryStore.REPO_DATA, RepositoryStore.REPO_POLICY});
	}
	
	public static PolicyXML createPolicyXML(InputStream stream) throws ContentDecodingException {
		XMLDecoder decoder = XMLCodecFactory.getDecoder(TextXMLCodec.codecName());
		decoder.beginDecoding(stream);
		PolicyXML pxml = new PolicyXML();
		pxml.decode(decoder);
		decoder.endDecoding();
		return pxml;
	}
	
	/**
	 * Gets the policy path for this repository
	 * @return the policy path as a ContentName
	 */
	public ContentName getPolicyName() { return getPolicyName(_globalPrefix, _localName); }

	/**
	 * Sets the repository version to be used by this policy interpreter. After this call any
	 * policy file containing a different version will be rejected.
	 * 
	 * @param version The version as a String
	 */
	public void setVersion(String version) {
		_repoVersion = version;
	}

	/**
	 * Sets the global prefix for this policy interpreter. After this call any policy file
	 * containing a different global prefix will be rejected
	 *
	 * @param globalPrefix the global prefix as a slash separated String
	 */
	public void setGlobalPrefix(String globalPrefix) throws MalformedContentNameStringException {
		if (null == _globalPrefix) {
			changeGlobalPrefix(globalPrefix);
		}
	}
	
	/**
	 * Gets the global prefix currently in use for this repository
	 * 
	 * @return the global prefix as a ContentName
	 */
	public ContentName getGlobalPrefix() { return _globalPrefix; }
	
	/**
	 * Gets the local name currently used by this repository
	 * 
	 * @return the local name as a slash separated String
	 */
	public String getLocalName() { return _localName; }

	public void setLocalName(String localName) throws MalformedContentNameStringException {
		if (null == _localName) {
			_localName = localName;
		}
	}
	
	private String fixSlash(String name) {
		if (!name.startsWith("/"))
			name = "/" + name;
		return name;
	}
	
	private void changeGlobalPrefix(String globalPrefix) throws MalformedContentNameStringException {
		// Note - need to synchronize on "this" to synchronize with events reading
		// the name space in the policy clients which have access only to this object
		synchronized (this) {
			if (null != _globalPrefix)
				_nameSpace.remove(_globalPrefix);
			_globalPrefix = ContentName.fromNative(fixSlash(globalPrefix));
			_nameSpace.add(_globalPrefix);
		}
	}
}
