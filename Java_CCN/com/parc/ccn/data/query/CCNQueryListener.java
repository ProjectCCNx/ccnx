package com.parc.ccn.data.query;

import java.util.ArrayList;

import com.parc.ccn.data.CompleteName;

public interface CCNQueryListener {
	
	/**
	 * Callback called when we get new results for our query.
	 * @param results
	 * @return
	 */
    public int handleResults(ArrayList<CompleteName> results);
    
    /**
     * Notification that our query has been canceled.
     * @param query
     */
    public void queryCanceled(CCNQueryDescriptor query);
    
    /**
     * Notification that our query has timed out.
     * @param query
     */
    public void queryTimedOut(CCNQueryDescriptor query);

     /**
     * Returns the queries we are listening for.
     */
    public CCNQueryDescriptor [] getQueries();
    
    /**
     * Does this CompleteName match one of our queries?
     * @param object
     * @return
     */
    public boolean matchesQuery(CompleteName name);
    
    /**
     * Cancel all the queries we are listening to.
     *
     */
    public void cancelQueries();
    
}
