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

package org.ccnx.ccn.io.content;

import java.io.IOException;

public class ContentDecodingException extends IOException {

	private static final long serialVersionUID = -3241398413568999091L;

	public ContentDecodingException() {
	}

	public ContentDecodingException(String message) {
		super(message);
	}

	public ContentDecodingException(Throwable cause) {
		// Can't do this on 1.5
		// super(cause);
		super("Caused by " + cause.getClass() + ": " + cause.getMessage());
	}

	public ContentDecodingException(String message, Throwable cause) {
		// Can't do this on 1.5
		// super(message, cause);
		super(message + ": caused by " + cause.getClass() + ": " + cause.getMessage());
	}

}
