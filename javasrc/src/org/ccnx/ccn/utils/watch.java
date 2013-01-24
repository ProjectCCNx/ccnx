/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008, 2009, 2011, 2013 Palo Alto Research Center, Inc.
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
import java.util.ArrayList;
import java.util.Date;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * Express interest in a name and report content retrieved.
 * This program has not been updated to handle interests in a sophisticated way
 * and so it is likely to receive the same content over and over.
 *
 */
public class watch extends Thread implements CCNContentHandler {
	
	protected boolean _stop = false;
	protected ArrayList<Interest> _interests = new ArrayList<Interest>();
	protected CCNHandle _handle = null;
	
	public watch(CCNHandle handle) {_handle = handle;}
	
	public void initialize() {}
	public void work() {}
	
	public void run() {
		initialize();
		
		System.out.println("Watching: " + new Date().toString() +".");
		Log.info("Watching: " + new Date().toString() +".");

		do {

			try {
				work();
			} catch (Exception e) {
				Log.warning("Error in watcher thread: " + e.getMessage());
				Log.warningStackTrace(e);
			}

			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
			}
		} while (true);

	}
	
	
	public static void usage() {
		System.out.println("usage: watch <ccnname> [<ccnname>...]");
	}
	
	public Interest handleContent(ContentObject data, Interest interest) {
		System.out.println("New content: " + data.name());
		return interest;
	}
		

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
			// Watches content, prints out what it sees.
			
			watch listener = new watch(library);
			
			for (int i=0; i < args.length; ++i) {
				Interest interest = new Interest(args[i]);
			
				library.expressInterest(interest, listener);
			} 
			
			listener.start();
			try {
				listener.join();
			} catch (InterruptedException e) {
				
			}
			System.exit(0);
			
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in watch: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("IOException in enumerate: " + e.getMessage());
			e.printStackTrace();
		} 

	}

}
