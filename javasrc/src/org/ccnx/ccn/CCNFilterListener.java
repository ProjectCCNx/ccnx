package org.ccnx.ccn;

import java.util.ArrayList;

import org.ccnx.ccn.protocol.Interest;


public interface CCNFilterListener {

	/**
	 * Callback called when we get new interests matching our filter.
	 * @param interests The matching interests
	 * @return
	 */
    public int handleInterests(ArrayList<Interest> interests);
    

}
