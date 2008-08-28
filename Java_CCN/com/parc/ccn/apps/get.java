package com.parc.ccn.apps;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.StandardCCNLibrary;

public class get {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 2) {
			usage();
			return;
		}
		
		try {
			// If we get one file name, put as the specific name given.
			// If we get more than one, put underneath the first as parent.
			// Ideally want to use newVersion to get latest version. Start
			// with random version.
			ContentName argName = new ContentName(args[0]);
			
			StandardCCNLibrary library = StandardCCNLibrary.open();
			
			if (args.length == 2) {
				// Adjust to use defragmenting interface, find latest
				// version, etc...
				ArrayList<ContentObject> objects = library.get(argName, null, true);
				
				System.out.println("Retrieved " + objects.size() + " objects named: " + argName);
				System.out.println("Writing to file " + args[1]);
				if (objects.size() > 0) {
					// just handle first one for now
				
					FileOutputStream fos = new FileOutputStream(args[1]);
					fos.write(objects.get(0).content());
					fos.flush();
					fos.close();
				}
				
			} else {
				// put something more interesting here
				usage();
				return;
			}
			System.exit(0);
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in get: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + args[0] + " " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot write file. " + e.getMessage());
			e.printStackTrace();
		} catch (InterruptedException e) {
			System.out.println("Cannot write file. " + e.getMessage());
			e.printStackTrace();
		} 

	}
	
	public static void usage() {
		System.out.println("usage: get <ccnname> <filename>");
	}

}
