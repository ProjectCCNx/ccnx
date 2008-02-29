package com.parc.ccn.data.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;

/**
 * A base class handling standard query listener
 * functionality.
 * @author smetters
 *
 */
public abstract class BasicQueryListener implements CCNInterestListener {

	protected ArrayList<Interest> _interests = new ArrayList<Interest>();
	
	/**
	 * This allows the same basic class to handle interests
	 * expressed at the CCNLibrary level or directly to
	 * a CCNRepository.
	 */
	protected CCNBase _interestProvider = null;
	
	public BasicQueryListener(CCNBase interestProvider) {
		_interestProvider = interestProvider;
	}
	
	public void addInterest(Interest interest) {
		_interests.add(interest);
	}
	
	public void cancelInterests() {
		for (int i=0; i < _interests.size(); ++i) {
			try {
				Library.logger().info("Back in cancel interests...");
				_interestProvider.cancelInterest(_interests.get(i));
			} catch (IOException e) {
				Library.logger().warning("Exception canceling interest: " + e.getMessage());
			}
		}			
	}

	public Interest[] getInterests() {
		return _interests.toArray(new CCNQueryDescriptor[_interests.size()]);
	}

	public abstract int handleResults(ArrayList<CompleteName> results);

	public boolean matchesQuery(CompleteName name) {
		Iterator<CCNQueryDescriptor> queryIt = _interests.iterator();
		while (queryIt.hasNext()) {
			CCNQueryDescriptor qd = queryIt.next();
			if ((null != qd) && (qd.matchesQuery(name))) {
				return true;
			}
		}
		return false;
	}
	
	public void queryCanceled(CCNQueryDescriptor query) {
		Library.logger().info("Query cancelled: " + query.name());
		// What happens if we do this in the middle of cancel queries?
		_interests.remove(query);
	}

	public void queryTimedOut(CCNQueryDescriptor query) {
		Library.logger().info("Query timed out: " + query.name());
		_interests.remove(query);
	}
}
