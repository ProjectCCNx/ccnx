package org.ccnx.ccn.io;

import java.io.IOException;

/**
 * @author smetters
 *
 */
public class NoMatchingContentFoundException extends IOException {

	/**
	 * TODO add constructor that takes a Throwable when move to 1.6
	 */
	
	private static final long serialVersionUID = 3578950166467684673L;

	public NoMatchingContentFoundException() {
		super();
	}

	/**
	 * @param s
	 */
	public NoMatchingContentFoundException(String s) {
		super(s);
	}

}
