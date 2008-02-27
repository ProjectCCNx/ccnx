package com.parc.ccn.data.query;

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
public abstract class BasicQueryListener implements CCNQueryListener {

	protected ArrayList<CCNQueryDescriptor> _queries = new ArrayList<CCNQueryDescriptor>();
	
	/**
	 * This allows the same basic class to handle interest
	 * expressed at the CCNLibrary level or directly to
	 * a CCNRepository.
	 */
	protected CCNBase _queryProvider = null;
	
	public BasicQueryListener(CCNBase queryProvider) {
		_queryProvider = queryProvider;
	}
	
	public void addQuery(CCNQueryDescriptor query) {
		_queries.add(query);
	}
	
	public void cancelQueries() {
		for (int i=0; i < _queries.size(); ++i) {
			//try {
				Library.logger().info("Add back in cancel queries...");
			//	_queryProvider.cancelInterest(_queries.get(i));
		//	} catch (IOException e) {
			//	Library.logger().warning("Exception canceling query: " + e.getMessage());
		//	}
		}			
	}

	public CCNQueryDescriptor[] getQueries() {
		return _queries.toArray(new CCNQueryDescriptor[_queries.size()]);
	}

	public abstract int handleResults(ArrayList<CompleteName> results);

	public boolean matchesQuery(CompleteName name) {
		Iterator<CCNQueryDescriptor> queryIt = _queries.iterator();
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
		_queries.remove(query);
	}

	public void queryTimedOut(CCNQueryDescriptor query) {
		Library.logger().info("Query timed out: " + query.name());
		_queries.remove(query);
	}
}
