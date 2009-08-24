package com.parc.ccn.apps;

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
