/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.profiles.ccnd;

import org.ccnx.ccn.CCNException;


/**
 * A real AccessDeniedException doesn't appear in Java until 1.7. Till then,
 * make our own. Even then so far it doesn't take sub-exceptions as constructor
 * arguments.
 * 
 * Similarly, IOException doesn't take a Throwable as a constructor argument till 1.6.
 * Until we move to 1.6, fake one out.
 */
public class CCNDaemonException extends CCNException {

	private static final long serialVersionUID = -7802610745471335654L;

	CCNDaemonException() {
		super();
	}
	
	CCNDaemonException(String message) {
		super(message);
	}
	
	CCNDaemonException(String message, Throwable cause) {
		// TODO -- move to better constructor in 1.6
		// super(message, cause);
		super(message + ": Nested exception: " + cause.getClass().getName() + ": " + cause.getMessage());
	}
	
	CCNDaemonException(Throwable cause) 	{
		// TODO -- move to better constructor in 1.6
		// super(cause);
		super("Nested exception: " + cause.getClass().getName() + ": " + cause.getMessage());
	}
}
