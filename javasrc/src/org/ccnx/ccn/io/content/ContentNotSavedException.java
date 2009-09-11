package org.ccnx.ccn.io.content;

import java.io.IOException;

/**
 * @author smetters
 *
 */
public class ContentNotSavedException extends IOException {

	/**
	 * 
	 */
	private static final long serialVersionUID = -7882656440295361498L;

	/**
	 * TODO add constructor taking Throwable when move to 1.6
	 */

	public ContentNotSavedException() {
		super();
	}

	/**
	 * @param s
	 */
	public ContentNotSavedException(String s) {
		super(s);
	}

}
