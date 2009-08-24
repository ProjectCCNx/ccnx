package org.ccnx.ccn.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class get_file {
	
	public static Integer timeout = null;
	public static boolean unversioned = false;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		int startArg = 0;
		
		for (int i = 0; i < args.length - 2; i++) {
			if (args[i].equals("-unversioned")) {
				if (startArg <= i)
					startArg = i + 1;
				unversioned = true;
			} else if (args[i].equals("-timeout")) {
				if (args.length < (i + 2)) {
					usage();
					return;
				}
				try {
					timeout = Integer.parseInt(args[++i]);
				} catch (NumberFormatException nfe) {
					usage();
					return;
				}
				if (startArg <= i)
					startArg = i + 1;
			} else if (args[i].equals("-log")) {
				Level level = null;
				if (args.length < (i + 2)) {
					usage();
				}
				try {
					level = Level.parse(args[++i]);
				} catch (NumberFormatException nfe) {
					usage();
				}
				Log.setLevel(level);
				if (startArg <= i)
					startArg = i + 1;
			} else {
				usage();
				System.exit(1);
			}
		}
		
		if (args.length < startArg + 2) {
			usage();
			System.exit(1);
		}
		
		try {
			int readsize = 1024; // make an argument for testing...
			// If we get one file name, put as the specific name given.
			// If we get more than one, put underneath the first as parent.
			// Ideally want to use newVersion to get latest version. Start
			// with random version.
			ContentName argName = ContentName.fromURI(args[startArg]);
			
			CCNHandle library = CCNHandle.open();

			File theFile = new File(args[startArg + 1]);
			if (theFile.exists()) {
				System.out.println("Overwriting file: " + args[startArg + 1]);
			}
			FileOutputStream output = new FileOutputStream(theFile);
			
			long starttime = System.currentTimeMillis();
			CCNInputStream input;
			if (unversioned)
				input = new CCNInputStream(argName, null, library);
			else
				input = new CCNFileInputStream(argName, library);
			if (timeout != null) {
				input.setTimeout(timeout); 
			}
			byte [] buffer = new byte[readsize];
			
			int readcount = 0;
			int readtotal = 0;
			//while (!input.eof()) {
			while ((readcount = input.read(buffer)) != -1){
				//readcount = input.read(buffer);
				readtotal += readcount;
				output.write(buffer, 0, readcount);
				output.flush();
			}
			System.out.println("get_file took: "+(System.currentTimeMillis() - starttime)+"ms");
			System.out.println("Retrieved content " + args[1] + " got " + readtotal + " bytes.");
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
		System.exit(1);
	}
	
	public static void usage() {
		System.out.println("usage: get_file [-unversioned] [-timeout millis] <ccnname> <filename>");
	}
}
