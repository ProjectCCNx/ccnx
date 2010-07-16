/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.utils.explorer.ContentExplorer;

/**
 * A command-line wrapper class for running an explorer from ccn_run script.
 * Note class name in utils package needs to match command name to work with ccn_run, 
 * hence this wrapper exists so the real implementation can be in its own subpackage
 * of utils.
 */
public class ccnexplore {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		ContentExplorer.main(args);
	}

}
