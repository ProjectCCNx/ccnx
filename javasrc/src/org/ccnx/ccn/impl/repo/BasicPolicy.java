/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import static org.ccnx.ccn.impl.repo.RepositoryStore.REPO_DATA;
import static org.ccnx.ccn.impl.repo.RepositoryStore.REPO_POLICY;

import java.io.InputStream;
import java.util.ArrayList;

import org.ccnx.ccn.impl.encoding.TextXMLCodec;
import org.ccnx.ccn.impl.encoding.XMLCodecFactory;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.protocol.ContentName;
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
	
	protected String _repoVersion = null;	// set from repo		
	protected PolicyXML _pxml = null;
	
	/**
	 * Constructor defaulting initial namespace to everything with no
	 * localName
	 */
	public BasicPolicy() {
		this(null);
	}
	
	/**
	 * Constructor defaulting the initial namespace to "everything"
	 * @param name local name for this repository
	 */
	public BasicPolicy(String name) {
		_pxml = new PolicyXML();
		try {
			if (null != name)
				_pxml.setLocalName(name);
			_pxml.setVersion(POLICY_VERSION);
			_pxml.addNamespace(ContentName.fromNative("/"));
		} catch (MalformedContentNameStringException e) {}
	}
	
	/**
	 * Constructor allowing an initial namespace to be set
	 * @param name local name for this repository
	 * @param namespace the initial namespace
	 */
	public BasicPolicy(String name, ArrayList<ContentName> namespace) {
		this(name);
		if (null != namespace) {
			_pxml.setNamespace(namespace);
		}
	}
	
	public void updateFromInputStream(InputStream stream) throws RepositoryException {
		PolicyXML pxml;
		try {
			pxml = createPolicyXML(stream);
		} catch (ContentDecodingException e) {
			throw new RepositoryException(e.getMessage());
		}
		update(pxml, false);
		_pxml = pxml;
	}
	
	/**
	 * Applies policy changes
	 * 
	 * @param pxml policy data
	 * @return
	 * @throws XMLStreamException
	 */
	public void update(PolicyXML pxml, boolean fromNet) throws RepositoryException {
		Log.info(Log.FAC_REPO, "Updating policy");
		if (pxml._version == null)
			throw new RepositoryException("No version in policy file");
		if (!pxml._version.equals(POLICY_VERSION)) {
			Log.warning(Log.FAC_REPO, "Bad version in policy file: {0}", pxml._version);
			throw new RepositoryException("Bad version in policy file");
		}
		
		if (null == pxml._localName)
			throw new RepositoryException("No local name in policy file");
		if (fromNet) {
			if (!pxml._localName.equals(_pxml.getLocalName())) {
				Log.warning(Log.FAC_REPO, "Repository local name doesn't match: request = {0}", pxml._localName);
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
			if (!pxml.getGlobalPrefix().equals(_pxml.getGlobalPrefix())) {
				Log.warning("Repository globalPrefix doesn't match: request = {0}", pxml._globalPrefix);
				throw new RepositoryException("Repository global prefix doesn't match policy file");
			}
		} else {
			_pxml.setGlobalPrefixOnly(pxml._globalPrefix);
		}
			
		_pxml.setNamespace(pxml.getNamespace());
		if (null != pxml.getNamespace()) {
			String message = "";
			for (ContentName name : pxml.getNamespace()) {
				message += name.toString() + ':';
			}
			Log.info(Log.FAC_REPO, "Policy has been updated. New namespace is: " + message);
		}
	}

	public ArrayList<ContentName> getNamespace() {
		return _pxml.getNamespace();
	}
	
	public void setNamespace(ArrayList<ContentName> namespace) {
		_pxml.setNamespace(namespace);
	}
	
	/**
	 * Creates the path for a policy file for a repository given it's global prefix and local name
	 * 
	 * @param globalPrefix global prefix as a ContentName
	 * @param localName local name as a / separated String
	 * @return the name as a ContentName
	 */
	public static ContentName getPolicyName(ContentName globalPrefix) {
		return new ContentName(globalPrefix, REPO_DATA, REPO_POLICY);
	}

	public static PolicyXML createPolicyXML(InputStream stream) throws ContentDecodingException {
		Log.info(Log.FAC_REPO, "Creating policy file");
		XMLDecoder decoder = XMLCodecFactory.getDecoder(TextXMLCodec.codecName());
		decoder.beginDecoding(stream);
		PolicyXML pxml = new PolicyXML();
		pxml.decode(decoder);
		Log.fine(Log.FAC_REPO, "Finished pxml decoding");
		decoder.endDecoding();
		return pxml;
	}
	
	/**
	 * Gets the policy path for this repository
	 * @return the policy path as a ContentName
	 */
	public ContentName getPolicyName() { return getPolicyName(_pxml.getGlobalPrefix()); }

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
		_pxml.setGlobalPrefix(globalPrefix);
	}
	
	/**
	 * Gets the global prefix currently in use for this repository
	 * 
	 * @return the global prefix as a ContentName
	 */
	public ContentName getGlobalPrefix() { return _pxml.getGlobalPrefix(); }
	
	/**
	 * Gets the local name currently used by this repository
	 * 
	 * @return the local name as a slash separated String
	 */
	public String getLocalName() { return _pxml.getLocalName(); }

	public void setLocalName(String localName) throws MalformedContentNameStringException {
		if (null == _pxml.getLocalName()) {
			_pxml.setLocalName(localName);
		}
	}
	
	public synchronized PolicyXML getPolicyXML() {
		return _pxml;
	}
	
	public synchronized void setPolicyXML(PolicyXML pxml) {
		_pxml = pxml;
	}
}
