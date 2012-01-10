/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.repo.PolicyXML.PolicyObject;
import org.ccnx.ccn.impl.repo.RepositoryInfo.RepositoryInfoObject;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
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
	protected CCNHandle _handle = null;
	protected KeyManager _km = null;
	
	/**
	 * Handle diagnostic requests
	 * 
	 * @return true if request recognized and carried out
	 */
	public boolean diagnostic(String name) {
		return false;
	}

	public abstract ContentObject getContent(Interest interest) throws RepositoryException;

	public abstract NameEnumerationResponse getNamesWithPrefix(Interest i, ContentName responseName);
	
	/**
	 * @return returns null prior to calls to initialize()
	 */
	public CCNHandle getHandle() { return _handle; }
	
	public KeyManager getKeyManager() { return _km; }

	/**
	 * Gets the currently valid namespace for this repository
	 * @return the namespace as an ArrayList of ContentNames containing prefixes of valid namespaces
	 */
	public ArrayList<ContentName> getNamespace() {
		return _policy.getNamespace();
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
	public RepositoryInfoObject getRepoInfo(ContentName name, String info, ArrayList<ContentName> names) {
		try {
			RepositoryInfo rri = _info;
			if (names != null || info != null) {
				if (names != null)
					rri = new RepositoryInfo(getVersion(), _info.getGlobalPrefix(), _info.getLocalName(), names);
				else
					rri = new RepositoryInfo(getVersion(), _info.getGlobalPrefix(), _info.getLocalName(), info);
			}
			RepositoryInfoObject rio = new RepositoryInfoObject(name, rri, SaveType.RAW, _handle);
			rio.setFreshnessSeconds(12); // Same time as repo will express interest
			return rio;
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Initialize a repository
	 */
	public abstract void initialize(String repositoryRoot,
			File policyFile, String localName, String globalPrefix, 
			String nameSpace, CCNHandle handle)
			throws RepositoryException;
	
	/**
	 * Initialize internal policy state, from file if policyFile != null
	 * This method is intended to be called at the beginning of a subclass initialize()
	 * method to handle the generic policy setup, after which the subclass initialize() 
	 * should adjust policy (including calling readPolicy) as appropriate.
	 * If both "policy file" and "initial namespace" are non-null the policy file takes precedence
	 * @param policyFile policy file
	 * @param initial namespace
	 * @throws RepositoryException
	 * @throws FileNotFoundException 
	 * @throws ContentDecodingException 
	 * @throws MalformedContentNameStringException 
	 */
	public PolicyXML startInitPolicy(File policyFile, String nameSpace) throws RepositoryException {
		BasicPolicy policy = new BasicPolicy(null);
		policy.setVersion(getVersion());

		if (null != policyFile) {
			try {
				FileInputStream fis = new FileInputStream(policyFile);
				try {
					policy.updateFromInputStream(fis);
				} finally {
					try {
						fis.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			} catch (FileNotFoundException e) {
				throw new RepositoryException(e.getMessage());
			}
		} else if (null != nameSpace) { // Try setting an initial namespace from the namespace parameter
			ArrayList<ContentName> nameSpaceAL = new ArrayList<ContentName>(1);
			try {
				nameSpaceAL.add(ContentName.fromNative(nameSpace));
			} catch (MalformedContentNameStringException e) {
				Log.warning(Log.FAC_REPO, "Invalid namespace specified: {0}", nameSpace);
			}
			policy.setNamespace(nameSpaceAL);
		}
		return policy.getPolicyXML();
	}
	
	/**
	 * Read policy from persistent storage under standard naming convention.
	 * This method may be called optionally during initialization by a subclass
	 * after it is initialized enough to process getContent() calls 
	 * @param globalPrefix - used to find our policy file
	 * @return XML for the current policy or null if no current policy
	 * @throws MalformedContentNameStringException 
	 * @throws IOException 
	 */
	public PolicyXML readPolicy(ContentName globalPrefix) throws MalformedContentNameStringException, IOException {
		if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
			Log.info(Log.FAC_REPO, "REPO: reading policy from network: {0}/{1}/{2}", REPO_NAMESPACE, globalPrefix, REPO_POLICY);
		ContentName policyName = BasicPolicy.getPolicyName(globalPrefix);
		
		// We can't use the regular repo handle for this because we need ccnd to communicate across the faces
		CCNHandle readHandle;
		readHandle = CCNHandle.open(_km);
		PolicyObject policyObject = new PolicyObject(policyName, readHandle);
		try {
			return policyObject.policyInfo();
		} catch (ContentNotReadyException cge) {}
		  finally {readHandle.close();}
		return null;
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
	
	public String getLocalName() {
		return _policy.getLocalName();
	}

	public void shutDown() {
		Log.info(Log.FAC_REPO, "RespositoryStoreBase.shutdown()");
		if( null != _handle )
			_handle.close();
	}
}
