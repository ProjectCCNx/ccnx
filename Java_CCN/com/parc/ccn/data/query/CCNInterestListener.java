package com.parc.ccn.data.query;

import java.util.ArrayList;

import com.parc.ccn.data.ContentObject;

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
