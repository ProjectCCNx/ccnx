package org.ccnx.ccn;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.Interest;



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
	 * expressed at the CCNHandle level or directly to
	 * a CCNRepository.
	 */
	protected CCNBase _interestProvider = null;
	
	public BasicInterestListener(CCNBase interestProvider) {
		_interestProvider = interestProvider;
	}
	
	public void cancelInterest(Interest interest) throws IOException {
		Log.logger().info("Interest cancelled: " + interest.name());
		// What happens if we do this in the middle of cancel interests?
		_interestProvider.cancelInterest(interest, this);
		_interests.remove(interest);
	}
}
