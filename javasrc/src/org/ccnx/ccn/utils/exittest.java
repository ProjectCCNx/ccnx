/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;

/**
 * An empty program to make sure nothing is hanging in CCN to prevent
 * a program from exiting.
 */
public class exittest {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		try {
			CCNHandle handle = CCNHandle.open();
			handle.close();
			KeyManager.closeDefaultKeyManager();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}

}
