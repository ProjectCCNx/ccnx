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

import java.io.File;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.protocol.CCNTime;
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

	public static final String REPO_POLICY = "policy.xml";
	public static final String REPO_NAMESPACE = "/ccn/repository";
	public static final String REPO_DATA = "data";
	public static final String REPO_LOGGING = "repo";
	
	
	/**
	 * NameEnumerationResponse objects are used to respond to incoming NameEnumeration interests.
	 * 
	 * NameEnumerationResponses are generated in two ways, in direct response to an interest
	 * where there is new information to return, and where a previous interest was not
	 * satisfied (set the interest flag), but a later save occurs directly under the namespace.
	 *
	 */
	public class NameEnumerationResponse{
		private ContentName prefix;
		private ArrayList<ContentName> names;
		private CCNTime version;
		
		/**
		 * Empty NameEnumerationResponse constructor that sets the variables to null.
		 */
		public NameEnumerationResponse(){
			prefix = null;
			names = null;
			version = null;
		}
		
		/**
		 * NameEnumerationResponse constructor that populates the object's variables.
		 * 
		 * @param p ContentName that is the prefix for this response
		 * @param n ArrayList<ContentName> of the names under the prefix
		 * @param ts CCNTime is the timestamp used to create the version component
		 *   for the object when it is written out
		 */
		public NameEnumerationResponse(ContentName p, ArrayList<ContentName> n, CCNTime ts){
			prefix = p;
			names = n;
			version = ts;
		}
		
		/**
		 * Method to set the NameEnumerationReponse prefix.
		 * 
		 * @param p ContentName of the prefix for the response
		 * @return void
		 */
		public void setPrefix(ContentName p){
			prefix = p;
		}
		
		/**
		 * Method to set the names to return under the prefix.
		 * 
		 * @param n ArrayList<ContentName> of the children for the response
		 * @return void
		 */
		public void setNameList(ArrayList<ContentName> n){
			names = n;
		}
		
		/**
		 * Method to get the prefix for the response.
		 * 
		 * @return ContentName prefix for the response
		 */
		public ContentName getPrefix(){
			return prefix;
		}
		
		/**
		 * Method to get the names for the response.
		 * 
		 * @return ArrayList<ContentName> Names to return in the response
		 */
		public ArrayList<ContentName> getNames(){
			return names;
		}
		
		
		/**
		 * Method to set the timestamp for the response version.
		 * @param ts CCNTime for the ContentObject version
		 * @return void
		 */
		public void setTimestamp(CCNTime ts){
			version = ts;
		}
		
		
		/**
		 * Method to get the timestamp for the response object.
		 * 
		 * @return CCNTime for the version component of the object
		 */
		public CCNTime getTimestamp(){
			return version;
		}
		
		/**
		 * Method to return a Collection object for the names in the response
		 * 
		 * @return Collection A collection of the names (as Link objects) to return.
		 */
		public Collection getNamesInCollectionData(){
			Link[] temp = new Link[names.size()];
			for (int x = 0; x < names.size(); x++) {
				temp[x] = new Link(names.get(x));
			}
			return new Collection(temp);
		}
		
		/**
		 * Method to check if the NameEnumerationResponse object has names to return.
		 * 
		 * @return boolean True if there are names to return, false if there are no
		 *   names or the list of names is null
		 */
		public boolean hasNames(){
			if (names!=null && names.size()>0)
				return true;
			else
				return false;
		}
		
	}
	
	/**
	 * Initialize the repository
	 * @param handle
	 * @param repositoryRoot
	 * @param policyFile policy file to use or null
	 * @param localName may be null
	 * @param globalPrefix may be null
	 * @throws RepositoryException
	 */
	public void initialize(CCNHandle handle, String repositoryRoot, File policyFile, String localName, String globalPrefix) throws RepositoryException;
	
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
	 * 
	 * @return
	 */
	public byte [] getRepoInfo(ArrayList<ContentName> names);
	
	/**
	 * Check whether data is a policy update. Update the
	 * policy if so
	 * @param co
	 * @return true if policy update data
	 * @throws RepositoryException 
	 */
	public boolean checkPolicyUpdate(ContentObject co) throws RepositoryException;
		
	/**
	 * Get names to respond to name enumeration requests.  Returns null if there
	 * is nothing after the prefix or if there is nothing new after the prefix if
	 * there is a version on the incoming interest
	 * @param i NameEnumeration Interest defining which names to get
	 * @return NameEnumerationResponse
	 */
    public NameEnumerationResponse getNamesWithPrefix(Interest i);
    
    /**
     * Hook to shutdown the store (close files for example)
     */
    public void shutDown();
    
    /**
     * Get the global prefix for this repository
     */
    public ContentName getGlobalPrefix();
    
    /**
     * Execute diagnostic operation.  The diagnostic operations are 
     * particular to the implementation and are intended for testing
     * and debugging only.
     * @param name the name of the implementation-specific diagnostic operation to perform
     * @return true if diagnostic operation is supported and was performed, false otherwise
     */
    public boolean diagnostic(String name);
}
