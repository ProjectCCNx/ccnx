package com.parc.ccn.apps;

import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.StandardCCNLibrary;

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
			StandardCCNLibrary library = new StandardCCNLibrary();
			// List contents under all names given
			
			for (int i=0; i < args.length; ++i) {
				Interest interest = new Interest(args[i]);
			
				ArrayList<CompleteName> names = library.enumerate(interest);
				
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
