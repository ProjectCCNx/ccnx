/**
 * 
 */
package org.ccnx.ccn.io.content;

import java.io.IOException;

/**
 * @author smetters
 *
 */
public class ContentGoneException extends IOException {

	/**
	 * TODO add constructor taking Throwable when move to 1.6
	 */
	private static final long serialVersionUID = 6732044153240082669L;

	/**
	 * 
	 */
	public ContentGoneException() {
		super();
	}

	/**
	 * @param s
	 */
	public ContentGoneException(String s) {
		super(s);
	}

}
