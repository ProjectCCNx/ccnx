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
