package org.ccnx.ccn.impl.repo;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * Special flow controller to write CCN objects to repository internally
 *
 */

public class RepositoryInternalFlowControl extends CCNFlowControl {
	RepositoryStore _repo;

	public RepositoryInternalFlowControl(RepositoryStore repo, CCNHandle handle) throws IOException {
		super(handle);
		_repo = repo;
	}
	
	/**
	 * Put to the repository instead of ccnd
	 */
	public ContentObject put(ContentObject co) throws IOException {
		try {
			_repo.saveContent(co);
		} catch (RepositoryException e) {
			throw new IOException(e.getMessage());
		}
		return co;
	}
	
	/**
	 * Don't do waitForPutDrain
	 */
	public void afterClose() throws IOException {};
}
