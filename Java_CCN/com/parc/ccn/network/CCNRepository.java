package com.parc.ccn.network;

import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.CCNBase;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;

/**
 * In addition to the standard base functions,
 * we need a few other means of access to our
 * repositories. First we need an enumerate function,
 * to List the names matching a given query.
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
			CompleteName name) throws IOException;
	
	/**
	 * Put content to the repository that can only
	 * be retrieved by getInternal, not by get.
	 * DKS: not sure this is the best way to do this.
	 * Want a way of storing, say, reconstructed
	 * fragmented content in the repository without
	 * the risk that it will be sent out over the wire.
	 * @param name
	 * @param authenticator
	 * @param content
	 * @return
	 * @throws IOException
	 */
	public CompleteName putLocal(
			ContentName name,
		    ContentAuthenticator authenticator,
		    byte [] content) throws IOException;

	/**
	 * 
	 * Retrieve any protected content available for a
	 * given name. Returns immediately.
	 * @param name
	 * @param authenticator
	 * @return
	 * @throws IOException
	 */
	public ArrayList<ContentObject> getLocal(
			ContentName name,
			ContentAuthenticator authenticator) throws IOException;

	/**
	 * Does this name refer to a node that represents
	 * local (protected) content?
	 * @param name
	 * @return
	 */
	public boolean isLocal(CompleteName name);
	
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