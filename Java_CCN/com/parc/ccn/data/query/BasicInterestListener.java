package com.parc.ccn.data.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentObject;

/**
 * A base class handling standard query listener
 * functionality.
 * @author smetters
 *
 */
public abstract class BasicInterestListener implements CCNInterestListener {

	/**
	 * Interest must implement Comparable.
	 */
	protected Set<Interest> _interests = new TreeSet<Interest>();
	
	/**
	 * This allows the same basic class to handle interests
	 * expressed at the CCNLibrary level or directly to
	 * a CCNRepository.
	 */
	protected CCNBase _interestProvider = null;
	
	public BasicInterestListener(CCNBase interestProvider) {
		_interestProvider = interestProvider;
	}
	
	public void addInterest(Interest interest) {
		_interests.add(interest);
	}
	
	public void cancelInterests() {
		Iterator<Interest> it = _interests.iterator();
		while (it.hasNext()) {
			try {
				Interest interest = it.next();
				Library.logger().info("Back in cancel interests...");
				_interestProvider.cancelInterest(interest, this);
			} catch (IOException e) {
				Library.logger().warning("Exception canceling interest: " + e.getMessage());
			}
		}			
	}

	public Interest[] getInterests() {
		return _interests.toArray(new Interest[_interests.size()]);
	}

	public abstract Interest handleContent(ArrayList<ContentObject> results);

	public boolean matchesInterest(CompleteName name) {
		Iterator<Interest> iIt = _interests.iterator();
		while (iIt.hasNext()) {
			Interest it = iIt.next();
			if ((null != it) && (it.matches(name))) {
				return true;
			}
		}
		return false;
	}
	
	public void cancelInterest(Interest interest) throws IOException {
		Library.logger().info("Interest cancelled: " + interest.name());
		// What happens if we do this in the middle of cancel interests?
		_interestProvider.cancelInterest(interest, this);
		_interests.remove(interest);
	}

	public void interestTimedOut(Interest interest) {
		Library.logger().info("Interest timed out: " + interest.name());
		_interests.remove(interest);
	}
}
