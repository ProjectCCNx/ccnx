package org.ccnx.ccn.io.content;

import java.io.IOException;

/**
 * @author smetters
 *
 */
public class ContentNotReadyException extends IOException {

	/**
	 * TODO add constructor taking Throwable when move to 1.6
	 */
	private static final long serialVersionUID = 6732044053240082669L;

	/**
	 * 
	 */
	public ContentNotReadyException() {
		super();
	}

	/**
	 * @param s
	 */
	public ContentNotReadyException(String s) {
		super(s);
	}

}
