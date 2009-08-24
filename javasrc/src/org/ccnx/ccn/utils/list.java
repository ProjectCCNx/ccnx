package org.ccnx.ccn.utils;

import java.io.IOException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class list {

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
			
				ArrayList<ContentObject> names = library.enumerate(interest, CCNHandle.NO_TIMEOUT);
				
				System.out.println("Retrieved " + names.size() + " names matching: " + interest.name());
				
				for (int j=0; j < names.size(); ++j) {
					ContentName name = names.get(j).name();
					System.out.println(name + " (" + 
							name.component(name.count()-1).length + " str len: " +
							name.stringComponent(name.count()-1).length() + ")");
				//	System.out.println(ContentName.hexPrint(name.component(name.count()-1)));
				}
			} 
			System.exit(0);
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in List: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("IOException in enumerate: " + e.getMessage());
			e.printStackTrace();
		} 

	}
	
	public static void usage() {
		System.out.println("usage: List <ccnname> [<ccnname>...]");
	}

}
