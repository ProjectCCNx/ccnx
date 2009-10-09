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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * Provide the generic policy-handling features of a RepositoryStore to simplify
 * implementation of subclasses for different storage systems.
 */
public abstract class RepositoryStoreBase implements RepositoryStore {
	
	protected Policy _policy = null;
	protected RepositoryInfo _info = null;

	/**
	 * Decide whether incoming data is a request to update the repository policy and attempt to
	 * update the policy if so. This is done by simply comparing the prefix of the name to the prefix
	 * expected for a policy file.
	 * 
	 * TODO: This assumes that an entire policy update is contained within 1 ContentObject. To fix this
	 * we need to assemble multiple packets into a file somewhere - probably this needs support from the
	 * upper layer.
	 */
	public boolean checkPolicyUpdate(ContentObject co)
			throws RepositoryException {
		if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING))
			Log.info("Got potential policy update: {0}, expected prefix {1}.", co.name(), _info.getPolicyName());
		if (_info.getPolicyName().isPrefixOf(co.name())) {
			ByteArrayInputStream bais = new ByteArrayInputStream(co.content());
			try {
				if (_policy.update(bais, true)) {
					ContentName policyName = VersioningProfile.addVersion(
							ContentName.fromNative(REPO_NAMESPACE + "/" + _info.getLocalName() + "/" + REPO_POLICY));
					if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING))
						Log.info("REPO: got policy update, global name {0} local name {1}, saving to {2}", _policy.getGlobalPrefix(), _policy.getLocalName(), policyName);
					ContentObject policyCo = new ContentObject(policyName, co.signedInfo(), co.content(), co.signature());
	   				saveContent(policyCo);
	   				if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING))
	   					Log.info("REPO: Saved policy to repository: {0}", policyCo.name());
	   				return true;
				}
			} catch (Exception e) {
				Log.logStackTrace(Level.WARNING, e);
				e.printStackTrace();
			} 
		}
		return false;
	}

	/**
	 * Handle diagnostic requests
	 * 
	 * @return true if request recognized and carried out
	 */
	public boolean diagnostic(String name) {
		return false;
	}

	public abstract ContentObject getContent(Interest interest) throws RepositoryException;

	public abstract NameEnumerationResponse getNamesWithPrefix(Interest i);

	/**
	 * Gets the currently valid namespace for this repository
	 * @return the namespace as an ArrayList of ContentNames containing prefixes of valid namespaces
	 */
	public ArrayList<ContentName> getNamespace() {
		return _policy.getNameSpace();
	}

	/**
	 * Gets the current policy for this repository
	 */
	public Policy getPolicy() {
		return _policy;
	}

	/**
	 * Returns the current version of the repository instance.
	 * Subclasses must implement this method to report their version for returning
	 * repository information.
	 * @return the version as a String
	 */
	public abstract String getVersion();
	
	/**
	 * Gets current repository information to be used as content in a ContentObject
	 * @param names intended for nonimplemented repository ACK protocol - currently unused
	 */
	public byte[] getRepoInfo(ArrayList<ContentName> names) {
		try {
			RepositoryInfo rri = _info;
			if (names != null)
				rri = new RepositoryInfo(getVersion(), _info.getGlobalPrefix(), _info.getLocalName(), names);	
			return rri.encode();
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Initialize a repository
	 */
	public abstract void initialize(CCNHandle handle, String repositoryRoot,
			File policyFile, String localName, String globalPrefix)
			throws RepositoryException;
	
	/**
	 * Initialize internal policy state, from file if policyFile != null
	 * This method is intended to be called at the beginning of a subclass initialize()
	 * method to handle the generic policy setup, after which the subclass initialize() 
	 * should adjust policy (including calling readPolicy) as appropriate.
	 * @param policyFile policy file
	 * @throws RepositoryException
	 */
	public void startInitPolicy(File policyFile) throws RepositoryException {
		_policy = new BasicPolicy(null);
		_policy.setVersion(getVersion());

		if (null != policyFile) {
			try {
				_policy.update(new FileInputStream(policyFile), false);
			} catch (Exception e) {
				throw new InvalidParameterException(e.getMessage());
			}
		}
	}
	
	/**
	 * Read policy from persistent storage under standard naming convention.
	 * This method may be called optionally during initialization by a subclass
	 * after it is initialized enough to process getContent() calls 
	 * @param localName
	 * @throws RepositoryException
	 */
	public void readPolicy(String localName) throws RepositoryException {
		if (null != localName) {
			try {
				if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING))
					Log.info("REPO: reading policy from network: {0}/{1}/{2}", REPO_NAMESPACE, localName, REPO_POLICY);
				ContentObject policyObject = getContent(
						new Interest(ContentName.fromNative(REPO_NAMESPACE + "/" + localName + "/" + REPO_POLICY)));
				if (policyObject != null) {
					ByteArrayInputStream bais = new ByteArrayInputStream(policyObject.content());
					_policy.update(bais, false);
				}
			} catch (MalformedContentNameStringException e) {} // None of this should happen
			catch (XMLStreamException e) {} 
			catch (IOException e) {}
		}
	}
	
	/**
	 * Complete policy initialization, to be called after subclass has adjusted 
	 * policy state based on persistent stored records.
	 * @throws MalformedContentNameStringException 
	 */
	public void finishInitPolicy(String globalPrefix, String localName) throws MalformedContentNameStringException {
		_info = new RepositoryInfo(getVersion(), globalPrefix, localName);
	}

	public abstract NameEnumerationResponse saveContent(ContentObject content) throws RepositoryException;

	public void setPolicy(Policy policy) {
		_policy = policy;
	}
	
	public ContentName getGlobalPrefix() {
		return _policy.getGlobalPrefix();
	}

	public abstract void shutDown();
}
