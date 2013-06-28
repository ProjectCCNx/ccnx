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

package org.ccnx.ccn.config;

import org.ccnx.ccn.CCNException;

/**
 * A marker exception thrown to indicate some problem with the user or system configuration,
 * that cannot be overcome without some intervention to manually correct the problem.
 */
public class ConfigurationException extends CCNException {
	
	private static final long serialVersionUID = -8498363015808971905L;

	public ConfigurationException(String message) {
		super(message);
	}
	
	public ConfigurationException(Exception e) {
		super(e);
	}
	
	public ConfigurationException(String message, Exception e) {
		super(message, e);
	}

	public ConfigurationException() {
		super();
	}
}
