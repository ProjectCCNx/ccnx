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

package org.ccnx.ccn.io;

import java.io.IOException;

/**
 * Exception to throw when we are asked to pull data from a network object or stream that is
 * already in an error state.
 */
public class ErrorStateException extends IOException {

	/**
	 * TODO use superclass constructor that takes a Throwable when move to 1.6
	 */
	
	private static final long serialVersionUID = 7578950167467684673L;
	
	protected Throwable _nestedException;

	public ErrorStateException() {
		super();
	}

	/**
	 * @param s
	 */
	public ErrorStateException(String s) {
		super(s);
	}


	/**
	 * @param s
	 * @param t
	 */
	public ErrorStateException(String s, Throwable t) {
		super(s);
		_nestedException = t;
	}
	
	/**
	 * @param t
	 */
	public ErrorStateException(Throwable t) {
		super();
		_nestedException = t;
	}

}
