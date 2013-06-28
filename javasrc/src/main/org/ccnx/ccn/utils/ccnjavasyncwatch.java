/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNSync;
import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

public class ccnjavasyncwatch implements Usage, CCNSyncHandler{
	static ccnjavasyncwatch ccnsync = new ccnjavasyncwatch();
	
	public ArrayList<ContentName> seen = new ArrayList<ContentName>();
	public boolean checkSeen = false;
	
	public void usage(String extraUsage) {
		System.out.println("usage: ccnjavasyncwatch [-log level] -t <topo> -p <prefix> [-f filter] [-r roothash-hex] [-w timeout-secs]");
		System.exit(1);
	}
		
	public static void main(String[] args) {
		ccnsync.startSync(args);
	}

	public void startSync(String[] args) {
		
		ContentName topo = null;
		ContentName prefix = null;
		ArrayList<ContentName> filters = null;
		ConfigSlice slice = null;
		
		ContentName hash = null;
		byte[] startHash = null;
		long timeout = -1;
		Level logLevel = Level.WARNING;
		
		try {
			
			for (int i = 0; i < args.length; i++) {
				if (args[i].equals("-log"))
					logLevel = Level.parse(args[i+1]);
				else if (args[i].equals("-t"))
					topo = ContentName.fromURI(args[i+1]);
				else if (args[i].equals("-p"))
					prefix = ContentName.fromURI(args[i+1]);
				else if (args[i].equals("-f")) {
					if (filters == null)
						filters = new ArrayList<ContentName>(args.length - 2);
					filters.add(ContentName.fromURI(args[i+1]));
				} else if (args[i].equals("-r"))
					hash = ContentName.fromURI(args[i+1]);
				else if (args[i].equals("-w"))
					timeout = Long.parseLong(args[i+1]);
				else if (args[i].equals("-s")) {
					checkSeen = true;
					continue;
				}
				i = i+1;
			}
			
			if (topo == null || prefix == null) {
				System.out.println("please run with a prefix and sync topo");
				System.exit(1);
			}
			
			if (filters != null)
				Log.warning("Filters are not fully supported, sync will operate properly with the filters, but the java library sync api will not apply the filters");

			if (hash != null) {  // I guess this is supposed to be a ContentName containing the hash
				startHash = hash.lastComponent();
			}
			
			Log.setDefaultLevel(logLevel);
			
			System.out.println("topo prefix: "+topo);
			System.out.println("prefix: "+prefix);


		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_IO, "Failed to create ContentNames from command line args {0}", e.getMessage());
			System.exit(1);
		}
		
		CCNSync mySync = new CCNSync();
		try {
			slice = mySync.startSync(null, topo, prefix, filters, startHash, null, this);
			System.out.println("created slice!");
		} catch (IOException e) {
			Log.warning("failed to start sync for prefix {0}: {1}", prefix, e.getMessage());
			System.exit(1);
		} catch (ConfigurationException e){
			Log.warning("failed to start sync for prefix {0}: {1}", prefix, e.getMessage());
			System.exit(1);
		}
		try {
			if (timeout != -1)
				Thread.sleep(timeout * 1000);
			else {
				while (true) {
					Thread.sleep(60000);
				}
			}
		} catch (InterruptedException e) {
			System.out.println("interrupted while sleeping...  ");
		}
		try {
			mySync.stopSync(this, slice);
		} catch (IOException e) {
			e.printStackTrace();
		}
		CCNHandle.getHandle().close();
		
	}

	public void handleContentName(ConfigSlice syncSlice, ContentName syncedContent) {
		System.out.println("Got a new name!!!! "+ syncedContent);
		if (checkSeen) {
			if (seen.contains(syncedContent)) {
				System.out.println("Oh-oh! it was a duplicate!");
			}
			seen.add(syncedContent);
		}
	}
}
