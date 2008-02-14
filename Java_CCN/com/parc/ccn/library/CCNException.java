package com.parc.ccn.library;

public class CCNException extends Exception {

	private static final long serialVersionUID = 8025798509482949608L;

	public CCNException() {
	}

	public CCNException(String message) {
		super(message);
	}

	public CCNException(Throwable e) {
		super(e);
	}

	public CCNException(String message, Throwable e) {
		super(message, e);
	}

}
