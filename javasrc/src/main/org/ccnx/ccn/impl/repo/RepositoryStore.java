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

import java.io.File;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.repo.RepositoryInfo.RepositoryInfoObject;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;


/**
 * A RepositoryStore stores ContentObjects for later retrieval
 * by Interest and supports enumeration of names.
 * A variety of different implementations are possible; typically
 * RepositoryStores will provide persistence of 
 * content through use of an external stable store like 
 * a filesystem.  A RepositoryStore is the lower half of 
 * a full persistent Repository, the part that handles 
 * the storage, as opposed to the Repository Protocol which 
 * is implemented by a RepositoryServer. 
 * 
 */

public interface RepositoryStore {

	public static final Component REPO_POLICY = new Component("policy.xml");
	public static final ContentName REPO_NAMESPACE = new ContentName("ccn", "repository");
	public static final Component REPO_DATA = new Component("data");
	public static final String REPO_LOGGING = "repo";
	
	public static final String REPO_SIMPLE_STATUS_REQUEST = "simpleStatus";
		
	/**
	 * Initialize the repository
	 * @param repositoryRoot
	 * @param policyFile policy file to use or null
	 * @param localName may be null
	 * @param globalPrefix may be null
	 * @param nameSpace initial namespace for repository
	 * @param handle optional CCNHandle if caller wants to override the
	 * 	default connection/identity behavior of the repository -- this
	 * 	provides a KeyManager and handle for the repository to use to 
	 * 	obtain its keys and communicate with ccnd. If null, the repository
	 * 	will configure its own based on policy, or if none, create one
	 * 	using the executing user's defaults.
	 * @throws RepositoryException
	 */
	public void initialize(String repositoryRoot, File policyFile, 
						   String localName, String globalPrefix,
						   String nameSpace,
						   CCNHandle handle) throws RepositoryException;
	
	
	/**
	 * Get the handle the repository is using to communicate with ccnd.
	 * This encapsulates the repository's KeyManager, which determines
	 * the identity (set of keys) the repository uses to sign messages
	 * it generates. That handle couuld be created by the repository
	 * or provided in a constructor. 
	 * 
	 * @return may return null if initialize() has not yet been called.
	 */
	public CCNHandle getHandle();

	/**
	 * Save the specified content in the repository.  If content is added to a name that has
	 * been the subject of a name enumeration request without a newer version at that time,
	 * the save will trigger a response to avoid forcing the enumerating node to wait for an
	 * Interest timeout to ask again.
	 * @param content
	 * @return NameEnumerationResponse
	 */
	public NameEnumerationResponse saveContent(ContentObject content) throws RepositoryException;
	
	/**
	 * Return the matching content if it exists
	 * @param interest Interest to match
	 * @return
	 */
	public ContentObject getContent(Interest interest) throws RepositoryException;
	
	/**
	 * Check for content matching the given name, without retrieving the content itself.
	 * @param name ContentName to match exactly, including digest as final explicit component
	 * @return true if there is a ContentObject with exactly the given name, false otherwise
	 */
	public boolean hasContent(ContentName name) throws RepositoryException;
	
	/**
	 * Bulk import of data from a file. Data must be in a format compatible with the
	 * repository store.  This would normally mean wire format
	 * @param fileName the name of the file to import data from.
	 * @return false if reexpression for import in progress
	 * @throws RepositoryException
	 */
	public boolean bulkImport(String name) throws RepositoryException;
	
	/**
	 * Get namespace interest
	 * @return
	 */
	public ArrayList<ContentName> getNamespace();
	
	/**
	 * Set the policy with XML based policy
	 * @param policy
	 */
	public void setPolicy(Policy policy);
	
	/**
	 * Get the current policy
	 * @return
	 */
	public Policy getPolicy();
	
	/**
	 * Get information about repository to return to write
	 * requestor, possibly with confirmation filename for sync
	 * @param name ContentName of netobject to write back out
	 * @param info arbitrary String info to be returned
	 * @param names Names of acked data for Ack protocol (currently unused)
	 * 
	 * @return
	 */
	RepositoryInfoObject getRepoInfo(ContentName name, String info, ArrayList<ContentName> names);
		
	/**
	 * Get names to respond to name enumeration requests.  Returns null if there
	 * is nothing after the prefix or if there is nothing new after the prefix if
	 * there is a version on the incoming interest
	 * @param i NameEnumeration Interest defining which names to get
	 * @param responseName 
	 * @return NameEnumerationResponse
	 */
    public NameEnumerationResponse getNamesWithPrefix(Interest i, ContentName responseName);
    
    /**
     * Hook to shutdown the store (close files for example)
     */
    public void shutDown();
    
    /**
     * Get the global prefix for this repository
     */
    public ContentName getGlobalPrefix();
    
    /**
     * Get the local name for this repository
     */
    public String getLocalName();
    
    /**
     * Execute diagnostic operation.  The diagnostic operations are 
     * particular to the implementation and are intended for testing
     * and debugging only.
     * @param name the name of the implementation-specific diagnostic operation to perform
     * @return true if diagnostic operation is supported and was performed, false otherwise
     */
    public boolean diagnostic(String name);
    
    /**
     * Get the repo's key manager. We should sign all repo data using this keymanager
     * @return the KeyManager
     */
    public KeyManager getKeyManager();
    
    /**
     * Get implementation defined status
     */
    public Object getStatus(String type);
    
    /**
     * We can't read/write policy files until after we have started the server so this is a
     * hook to do it at the right time.
     */
    public void policyUpdate() throws RepositoryException;
}
