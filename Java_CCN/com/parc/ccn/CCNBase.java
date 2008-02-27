package com.parc.ccn;

import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;

public interface CCNBase {
	
	/** 
	 * Make a data item available to the CCN.
	 * @param name
	 * @param authenticator
	 * @param content
	 * @return
	 * @throws IOException
	 */
	public CompleteName put(ContentName name,
						    ContentAuthenticator authenticator,
						    byte [] content) throws IOException;
	
	/**
	 * Retrieve any local content available for a
	 * given name. Returns immediately.
	 * For now, allow control of recursive vs non-recursive
	 * gets, to determine whether this is key functionality.
	 * Interests are always recursive.
	 * @param name
	 * @param authenticator
	 * @param isRecursive if false, just return content objects
	 * 	associated with this name if any, or an empty list if none.
	 * @return
	 * @throws IOException
	 */
	public ArrayList<ContentObject> get(
			ContentName name,
			ContentAuthenticator authenticator,
			boolean isRecursive) throws IOException;

	/**
	 * Query, or express an interest in particular
	 * content. This request is sent out over the
	 * CCN to other nodes. On any results, the
	 * callbackListener if given, is notified.
	 * Results may also be cached in a local repository
	 * for later retrieval by get().
	 * Get and expressInterest could be implemented
	 * as a single function that might return some
	 * content immediately and others by callback;
	 * we separate the two for now to simplify the
	 * interface.
	 * @param name
	 * @param authenticator
	 * @param callbackListener
	 * @return returns a unique identifier that can
	 * 		be used to cancel this query.
	 * @throws IOException
	 */
	public CCNQueryDescriptor expressInterest(
			Interest interest,
			CCNQueryListener callbackListener) throws IOException;
	
	public void cancelInterest(CCNQueryDescriptor query) throws IOException;

}
