package org.ccnx.ccn.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

import org.ccnx.ccn.CCNSync;
import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

public class ccnsync implements Usage, CCNSyncHandler{
	static ccnsync ccnsync = new ccnsync();
	
	@Override
	public void usage() {
		System.out.println("usage: ccnsync [-v (verbose)]  [-log level] [-ac (access control)] <topo> <prefix> [-f (filter) filter]");
		System.exit(1);
	}
	
	public static void main(String[] args) {
		ccnsync.startSync(args);
	}

	public void startSync(String[] args) {
		Log.setDefaultLevel(Level.WARNING);
		
		ContentName topo = null;
		ContentName prefix = null;
		ArrayList<ContentName> filters = null;
		ConfigSlice slice = null;
		
		try {
			topo = ContentName.fromURI(args[0]);
			System.out.println("topo prefix: "+topo);
			prefix = ContentName.fromURI(args[1]);
			System.out.println("prefix: "+prefix);
			
			if (args.length > 2 ) {
				filters = new ArrayList<ContentName>(args.length - 2);
				for (int i = 2; i < args.length; i++) {
					filters.add(ContentName.fromURI(args[i]));
				}
			}
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_IO, "Failed to create ContentNames from command line args {0}", e.getMessage());
			System.exit(1);
		}
		
		CCNSync mySync = new CCNSync();
		try {
			slice = mySync.startSync(topo, prefix, filters, this);
			System.out.println("created slice! "+slice);
		} catch (IOException e) {
			Log.warning(Log.FAC_REPO, "failed to start sync for prefix {0}: {1}", prefix, e.getMessage());
		} catch (ConfigurationException e){
			Log.info(Log.FAC_TEST, "failed to start sync for prefix {0}: {1}", prefix, e.getMessage());
		}
		
		while (true) {
			try {
				Thread.sleep(60000);
			} catch (InterruptedException e) {
				System.out.println("interrupted while sleeping...  ");
			}
			System.out.println("main loop...");
		}
	}

	@Override
	public void handleContentName(ConfigSlice syncSlice, ContentName syncedContent) {
		System.out.println("Got a new name!!!! "+ syncedContent);
	}
}
