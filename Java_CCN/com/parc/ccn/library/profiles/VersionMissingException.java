package com.parc.ccn.library.profiles;

/**
 * Thrown when a version field is expected and can't be found
 */
public class VersionMissingException extends Exception {
	
	private static final long serialVersionUID = 6839100050364261153L;

	public VersionMissingException() {
		super();
	}

	public VersionMissingException(String string) {
		super(string);
	}
}
