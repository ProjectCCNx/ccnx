package org.ccnx.ccn;

import java.util.ArrayList;

import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;


public interface CCNInterestListener {
	
	/**
	 * Callback called when we get new results for our query.
	 * @param results Change to a content object, as that is what
	 * 			ccnd is currently handing back anyway.
	 * @param interest Interest that we matched
	 * @return new Interest to be expressed
	 */
    public Interest handleContent(ArrayList<ContentObject> results, Interest interest);
    
}
