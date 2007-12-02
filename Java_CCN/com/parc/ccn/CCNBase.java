package com.parc.ccn;

import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.query.CCNQueryDescriptor;
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
	 * @param name
	 * @param authenticator
	 * @return
	 * @throws IOException
	 */
	public ArrayList<ContentObject> get(
			ContentName name,
			ContentAuthenticator authenticator) throws IOException;

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
	 * @param TTL limited-duration query, removes
	 * 	the requirement to call cancelInterest. TTL
	 *  <= 0 signals a query that runs until cancelled.
	 * @return returns a unique identifier that can
	 * 		be used to cancel this query.
	 * @throws IOException
	 */
	public CCNQueryDescriptor expressInterest(
			ContentName name,
			ContentAuthenticator authenticator,
			CCNQueryListener callbackListener,
			long TTL) throws IOException;
	
	public void cancelInterest(CCNQueryDescriptor query) throws IOException;

}
