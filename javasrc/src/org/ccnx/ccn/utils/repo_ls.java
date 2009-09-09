package org.ccnx.ccn.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.SortedSet;
import java.util.TreeSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;



public class repo_ls implements BasicNameEnumeratorListener{

	private String prefix = "";
	private ContentName name = null;
	private long timeout = 2000;
	private SortedSet<ContentName> allNames;

	public static void main(String[] args) {
		repo_ls lister = new repo_ls();
		lister.init(args);
		lister.enumerateNames();
		System.exit(0);
	}
	
	private void init(String[] args){
		// first look for prefix and timeout in the args list
		
		for (int i = 0; i < args.length; i++) {
			if(i == 0 && !args[i].equals("-timeout")){
				prefix = args[i];
			}
			else{
				i++;
				if(i >= args.length){
					usage();
					System.exit(1);
				}
				else{
					timeout = Long.parseLong(args[i]);
				}
			}
		}
		
		try {
			if(prefix==null || prefix.equals(""))
				name = new ContentName();
			else
				name = ContentName.fromNative(prefix);
			Log.fine("monitoring prefix "+name.toString());
		} catch (MalformedContentNameStringException e) {
			System.err.println(e.toString());
			System.err.println("could not create parse prefix, please be sure it is a valid name prefix");
			System.exit(1);
		}
		
		if(timeout > 0)
			Log.fine("monitoring prefix for "+timeout+"ms");
				
		allNames = new TreeSet<ContentName>();
		
	}

	private void enumerateNames(){
		try {
			CCNHandle handle = CCNHandle.open();

			CCNNameEnumerator ccnNE = new CCNNameEnumerator(handle, this);
			ccnNE.registerPrefix(name);
			
			if(timeout > 0){
				try {
					Thread.sleep(timeout);
				} catch (InterruptedException e) {
					System.err.println("error while waiting for responses from CCNNameEnumerator");
				}
			
				Log.fine("finished waiting for responses, cleaning up state");
				ccnNE.cancelPrefix(name);
				printNames();
			}
			else{
				//we do not have to exit
				while(true){
					
				}
			}
			
		} catch (ConfigurationException e) {
			System.err.println("Configuration Error");
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println(e.toString());
			e.printStackTrace();
		}
		
	}

	
	public void usage(){
		System.out.println("usage: repo_ls <ccnprefix> [-timeout millis (default is 2000ms)]");
	}

	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> names) {
		allNames.addAll(names);
		return 0;
	}
	
	public void printNames(){
		System.out.println("==========");
		System.out.println("Contents under "+name.toString());
		for(ContentName c: allNames)
			System.out.println("  + "+c);
		System.out.println("----------");
	}
	
}
