package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.util.logging.Level;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.repo.RepositoryStore.NameEnumerationResponse;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * So the main listener can output interests sooner, we do the data store work
 * in a separate thread.
 */

public class RepositoryDataHandler implements Runnable {
	private ContentObject _content;
	private RepositoryServer _server;
	
	public RepositoryDataHandler(ContentObject co, RepositoryServer server) {
		if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING))
			Log.info("Saw data: {0}", co.name());
		_content = co;
		_server = server;
	}

	/**
	 * The content listener runs this thread to store data using the content store.
	 * The thread also checks for policy updates which may reset the repository's
	 * namespace and sends "early" nameEnumerationResponses when requested by the
	 * store.
	 * 
	 * @see RepositoryStore
	 */
	public void run() {
		try {
			if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
				Log.finer("Saving content in: " + _content.name().toString());
			}
			
			NameEnumerationResponse ner = _server.getRepository().saveContent(_content);
			specialFunctionality(_content);
			if (ner!=null && ner.hasNames()) {
				_server.sendEnumerationResponse(ner);
			}
		} catch (Exception e) {
			e.printStackTrace();
			Log.logStackTrace(Level.WARNING, e);
		}
	}
	
	/**
	 * Allow subclass override
	 * @param co
	 */
	public void specialFunctionality(ContentObject co) throws RepositoryException, IOException {}
}
