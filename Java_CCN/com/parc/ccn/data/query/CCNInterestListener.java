package com.parc.ccn.data.query;

import java.util.ArrayList;

import com.parc.ccn.data.CompleteName;

public interface CCNInterestListener {
	
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
    public void interestCanceled(Interest interest);
    
    /**
     * Notification that our query has timed out.
     * @param query
     */
    public void interestTimedOut(Interest interest);

     /**
     * Returns the queries we are listening for.
     */
    public Interest [] getInterests();
    
    /**
     * Adds another query we are listening for.
     * Normally this will be done automatically,
     * to avoid race conditions. This is just to
     * allow manual listener management.
     */
    public void addInterest(Interest interest);
    
    /**
     * Does this CompleteName match one of our queries?
     * @param object
     * @return
     */
    public boolean matchesInterest(CompleteName name);
    
    /**
     * Cancel all the queries we are listening to.
     *
     */
    public void cancelInterests();
    
}
