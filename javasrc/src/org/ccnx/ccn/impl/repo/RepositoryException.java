package org.ccnx.ccn.impl.repo;

/**
 * 
 * @author rasmusse
 *
 */
public class RepositoryException extends Exception {
	
	private static final long serialVersionUID = -1467841589101068250L;

	public RepositoryException(String msg) {
		super(msg);
	}
	
	public RepositoryException(String msg, Throwable cause) {
		super(msg, cause);
	}
}
