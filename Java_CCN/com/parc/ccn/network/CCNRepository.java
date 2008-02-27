package com.parc.ccn.network;

import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.CCNBase;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.query.Interest;

/**
 * In addition to the standard base functions,
 * we need a few other means of access to our
 * repositories. First we need an enumerate function,
 * to list the names matching a given query.
 * Second, we need a way to store and return
 * internal properties on existing nodes, and
 * to store and return internal nodes, both used
 * for bookkeeping but not sent externally.
 */
public interface CCNRepository extends CCNBase {

	/**
	 * List all of the names available in this repository
	 * that match this name.
	 * TODO make one interface for detecting matches,
	 * 	used by both this and the one in CCNQueryListener.
	 * @throws IOException 
	 */
	public ArrayList<CompleteName> enumerate(
			Interest interest) throws IOException;
	
	/**
	 * Now that enumerate and interests are always
	 * recursive, we have applications that really
	 * want to walk the repository level by level.
	 * At each level, the content that is actually
	 * a CCN object will be returned as a full CompleteName;
	 * that which is just a parent will have a null
	 * ContentAuthenticator.
	 */
	public ArrayList<CompleteName> getChildren(
			CompleteName name) throws IOException;
	
	/**
	 * Annotate content (internal or public) with
	 * additional information.
	 */
	public CompleteName addProperty(
			CompleteName target,
			String propertyName, 
		    byte [] propertyValue) throws IOException;

	/**
	 * Annotate content (internal or public) with
	 * additional information.
	 */
	public CompleteName addProperty(
			CompleteName target,
			String propertyName, 
		    String propertyValue) throws IOException;
	/**
	 * Retrieve additional information.
	 */
	public String getStringProperty(
			CompleteName target,
			String propertyName) throws IOException;

	/**
	 * Retrieve additional information.
	 */
	public byte [] getByteProperty(
			CompleteName target,
			String propertyName) throws IOException;

}