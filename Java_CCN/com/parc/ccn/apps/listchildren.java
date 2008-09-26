package com.parc.ccn.apps;

import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.network.CCNRepositoryManager;

public class listchildren {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 1) {
			usage();
			return;
		}
		
		try {
			// List contents under all names given
			
			for (int i=0; i < args.length; ++i) {
				ContentName argName = ContentName.fromURI(args[i]);
			
				ArrayList<CompleteName> names = 
					CCNRepositoryManager.getRepositoryManager().getChildren(new CompleteName(argName, null, null));
				
				System.out.println("Retrieved " + names.size() + " names matching: " + argName);
				
				for (int j=0; j < names.size(); ++j) {
					System.out.println(names.get(j).name());
				}
			} 
			System.exit(0);
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("IOException in enumerate: " + e.getMessage());
			e.printStackTrace();
		} 

	}
	
	public static void usage() {
		System.out.println("usage: listchildren <ccnname> [<ccnname>...]");
	}

}
