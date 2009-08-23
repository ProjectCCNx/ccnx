package com.parc.ccn.data.query;

import java.util.ArrayList;

import org.ccnx.ccn.protocol.ContentName;



public interface BasicNameEnumeratorListener {

	/**
	 * Callback called when we get a collection matching a registered prefix.
	 * @param  collectionData  The list of Link objects corresponding to the names in the local namespace  
	 * @return int number of Link objects in Collection
	 */
	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> names);

}
