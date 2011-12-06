/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2009, 2010 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */
 
package org.ccnx.ccn.utils;

import org.ccnx.ccn.config.SystemConfiguration;

public class ccngetpid {

	/**
	 * Print out the PID determined for the VM as a test.
	 * @param args
	 */
	public static void main(String[] args) {
		String pid = SystemConfiguration.getPID();
		System.out.println("PID obtained is " + ((null == pid) ? "null" : pid));
	}

}
