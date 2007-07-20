package com.parc.ccn.data.query;

import java.util.ArrayList;

import com.parc.ccn.data.ContentObject;

public interface CCNQueryListener {
	
	public enum CCNQueryType {EXACT_MATCH};

	/**
	 * Callback called when we get new results for our query.
	 * @param results
	 * @return
	 */
    public int handleResults(ArrayList<ContentObject> results);
    
    public void setQuery(CCNQueryDescriptor query);

    /**
     * Returns the queries we are listening for.
     */
    public CCNQueryDescriptor getQuery();
    
    public boolean matchesQuery(ContentObject object);
    
    public void cancel();
    
}
