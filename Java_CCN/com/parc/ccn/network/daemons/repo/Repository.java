package com.parc.ccn.network.daemons.repo;

import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;

/**
 * Designed to contain all methods that talk to the repository
 * directly so that we can easily replace the repository with a
 * different implementation.  
 * 
 * @author rasmusse
 *
 */

public interface Repository {
	
	/**
	 * Initialize the repository
	 * @param args - user arguments to the repository
	 * @return
	 */
	public String[] initialize(String[] args) throws RepositoryException;
	
	/**
	 * Save the specified content in the repository
	 * @param content
	 */
	public void saveContent(ContentObject content) throws RepositoryException;
	
	/**
	 * Return the matching content if it exists
	 * @param name
	 * @return
	 */
	public ContentObject getContent(Interest interest) throws RepositoryException;
	
	/**
	 * Get namespace interest
	 * @return
	 */
	public Interest getNamespaceInterest();
	
	/**
	 * Get interest to receive policy changes for the repository
	 * @return
	 */
	public Interest getPolicyInterest();
	
	/**
	 * Set the policy with XML based policy
	 * @param policy
	 */
	public void setPolicy(Policy policy);
	
	/**
	 * Get required arguments for this implementation
	 * @return
	 */
	public String getUsage();

}
