/**
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

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class interest {
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 1) {
			usage();
			return;
		}
		
		try {
			CCNHandle library = CCNHandle.open();
			// List contents under all names given
			for (int i=0; i < args.length; ++i) {
				Interest interest = new Interest(args[i]);
			
				library.expressInterest(interest, null);
				
			} 
			System.exit(0);
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in interest: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("IOException in interest: " + e.getMessage());
			e.printStackTrace();
		} 

	}
	
	public static void usage() {
		System.out.println("usage: interest <ccnname> [<ccnname>...]");
	}

}
