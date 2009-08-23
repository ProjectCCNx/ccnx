package org.ccnx.ccn.io.content;

import org.ccnx.ccn.CCNException;

public class ContentDecodingException extends CCNException {

	private static final long serialVersionUID = -3241398413568999091L;

	public ContentDecodingException() {
	}

	public ContentDecodingException(String message) {
		super(message);
	}

	public ContentDecodingException(Throwable cause) {
		super(cause);
	}

	public ContentDecodingException(String message, Throwable cause) {
		super(message, cause);
	}

}
