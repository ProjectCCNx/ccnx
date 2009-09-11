package org.ccnx.ccn.protocol;

import org.ccnx.ccn.CCNException;

/**
 * @author briggs
 *
 */
public class MalformedContentNameStringException extends CCNException {

	private static final long serialVersionUID = 7844927632290343475L;

	/**
	 * 
	 */
	public MalformedContentNameStringException() {
	}

	/**
	 * @param message
	 */
	public MalformedContentNameStringException(String message) {
		super(message);
	}

	/**
	 * @param cause
	 */
	public MalformedContentNameStringException(Throwable cause) {
		super(cause);
	}

	/**
	 * @param message
	 * @param cause
	 */
	public MalformedContentNameStringException(String message, Throwable cause) {
		super(message, cause);
	}

}
