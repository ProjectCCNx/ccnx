package org.ccnx.ccn.impl.repo;

import java.io.IOException;

import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * Special purpose listener to handle network files written to control the repository
 * Currently the only example of this are policy files.
 * 
 * This listener is used for anything whose prefix matches the global prefix for the
 * repository
 * 
 * It inherits all behavior from the superclass - i.e. expresses interests for more
 * segments, etc.
 */

public class RepositoryPolicyListener extends RepositoryDataListener {
	
	private class PolicyDataHandler extends RepositoryDataHandler {

		public PolicyDataHandler(ContentObject co, RepositoryServer server) {
			super(co, server);
		}
		
		/**
		 * Here's where we check for policy updates from the net
		 */
		@Override
		public void specialFunctionality(ContentObject co) throws RepositoryException, IOException {
			if (_server.getRepository().checkPolicyUpdate(co)) {
				_server.resetNameSpaceFromHandler();
			}
		}
	}

	public RepositoryPolicyListener(Interest origInterest, Interest interest,
			RepositoryServer server) {
		super(origInterest, interest, server);
	}
	
	/**
	 * Need to use our own data handler to allow the special functionality to check for
	 * policy updates
	 */
	@Override
	public void handleData(ContentObject co) {
		_server.getThreadPool().execute(new PolicyDataHandler(co, _server));
	}
}
