package com.parc.ccn.data.query;

import java.util.ArrayList;

public interface CCNFilterListener {

	/**
	 * Callback called when we get new interests matching our filter.
	 * @param interests The matching interests
	 * @return
	 */
    public int handleInterests(ArrayList<Interest> interests);
    

}
