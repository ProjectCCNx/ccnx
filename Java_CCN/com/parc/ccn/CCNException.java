package com.parc.ccn;

public class CCNException extends Exception {

	private static final long serialVersionUID = 1010994050811465163L;

	public CCNException() {	}

	public CCNException(String message) {
		super(message);
	}

	public CCNException(Throwable cause) {
		super(cause);
	}

	public CCNException(String message, Throwable cause) {
		super(message, cause);
	}

}
