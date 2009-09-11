package org.ccnx.ccn.profiles.access;

import java.io.IOException;

/**
 * @author smetters
 *
 * A real AccessDeniedException doesn't appear in Java until 1.7. Till then,
 * make our own. Even then so far it doesn't take sub-exceptions as constructor
 * arguments.
 * 
 * Similarly, IOException doesn't take a Throwable as a constructor argument till 1.6.
 * Until we move to 1.6, fake one out.
 */
public class AccessDeniedException extends IOException {

	private static final long serialVersionUID = -7802610745471335632L;

	AccessDeniedException() {
		super();
	}
	
	AccessDeniedException(String message) {
		super(message);
	}
	
	AccessDeniedException(String message, Throwable cause) {
		// TODO -- move to better constructor in 1.6
		// super(message, cause);
		super(message + ": Nested exception: " + cause.getClass().getName() + ": " + cause.getMessage());
	}
	
	AccessDeniedException(Throwable cause) 	{
		// TODO -- move to better constructor in 1.6
		// super(cause);
		super("Nested exception: " + cause.getClass().getName() + ": " + cause.getMessage());
	}
}
