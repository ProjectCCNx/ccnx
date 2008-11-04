package com.parc.ccn.data.query;

import java.util.ArrayList;

import com.parc.ccn.data.ContentObject;

public interface CCNInterestListener {
	
	/**
	 * Callback called when we get new results for our query.
	 * @param results Change to a content object, as that is what
	 * 			ccnd is currently handing back anyway.
	 * @return any updates to the standing interest to be expressed
	 */
    public Interest handleContent(ArrayList<ContentObject> results);
    
    /**
     * Does this ContentObject match one of our queries?
     * @param object
     * @return
     */
    public boolean matchesInterest(ContentObject content);
    
    /**
     * Cancel all the queries we are listening to.
     *
     */
    public void cancelInterests();
    
}
