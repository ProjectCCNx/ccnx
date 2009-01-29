package com.parc.ccn.apps;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;

public class get_file {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 2) {
			usage();
			return;
		}
		
		try {
			int readsize = 1024; // make an argument for testing...
			// If we get one file name, put as the specific name given.
			// If we get more than one, put underneath the first as parent.
			// Ideally want to use newVersion to get latest version. Start
			// with random version.
			ContentName argName = ContentName.fromURI(args[0]);
			
			CCNLibrary library = CCNLibrary.open();

			File theFile = new File(args[1]);
			if (theFile.exists()) {
				System.out.println("Overwriting file: " + args[1]);
			}
			FileOutputStream output = new FileOutputStream(theFile);
			
			CCNInputStream input = new CCNInputStream(argName, library);
			if (args.length > 2) {
				input.setTimeout(new Integer(args[2]).intValue()); 
			}
			byte [] buffer = new byte[readsize];
			
			int readcount = 0;
			int readtotal = 0;
			while (!input.eof()) {
				readcount = input.read(buffer);
				readtotal += readcount;
				output.write(buffer, 0, readcount);
				output.flush();
			}

			System.out.println("Retrieved content " + args[1] + " as " + input.baseName() + " got " + readtotal + " bytes.");
			System.exit(0);

		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in get_file: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + args[0] + " " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot write file or read content. " + e.getMessage());
			e.printStackTrace();
		} catch (XMLStreamException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
	
	public static void usage() {
		System.out.println("usage: get_file <ccnname> <filename> [<timeoutms>]");
	}

}
