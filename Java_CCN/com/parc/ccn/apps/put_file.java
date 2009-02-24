package com.parc.ccn.apps;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNDescriptor;

public class put_file {
	
	private static int BLOCK_SIZE = 8096;
	private static boolean rawMode = false;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		int startArg = 0;
		if (args.length > 0 && args[0].equals("-raw")) {
			startArg++;
			rawMode = true;
		}
		if (args.length < startArg + 2) {
			usage();
			return;
		}
		
		try {
			// If we get one file name, put as the specific name given.
			// If we get more than one, put underneath the first as parent.
			// Ideally want to use newVersion to get latest version. Start
			// with random version.
			ContentName argName = ContentName.fromURI(args[startArg]);
			
			CCNLibrary library = CCNLibrary.open();
			
			if (args.length == (startArg + 2)) {
				
				File theFile = new File(args[startArg + 1]);
				if (!theFile.exists()) {
					System.out.println("No such file: " + args[startArg + 1]);
					usage();
					return;
				}
				Library.logger().info("put_file: putting file " + args[startArg + 1] + " bytes: " + theFile.length());
				
				CCNDescriptor ccnd;
				if (rawMode)
					ccnd = library.open(argName, library.getDefaultPublisher(), null, null);
				else
					ccnd = library.repoOpen(argName, library.getDefaultPublisher(), null, null);
				do_write(ccnd, theFile);
				
				System.out.println("Inserted file " + args[startArg + 1] + ".");
				System.exit(0);
			} else {
				for (int i=startArg + 1; i < args.length; ++i) {
					
					File theFile = new File(args[i]);
					if (!theFile.exists()) {
						System.out.println("No such file: " + args[i]);
						usage();
						return;
					}
					
					//FileOutputStream testOut = new FileOutputStream("put_file" + i + ".dat");
					//testOut.write(contents);
					//testOut.flush();
					//testOut.close();
					
					// put as child of name
					ContentName nodeName = ContentName.fromURI(argName, theFile.getName());
					
					// int version = new Random().nextInt(1000);
					// would be version = library.latestVersion(argName) + 1;
					CCNDescriptor ccnd;
					if (rawMode)
						ccnd = library.open(nodeName, library.getDefaultPublisher(), null, null);
					else
						ccnd = library.repoOpen(nodeName, library.getDefaultPublisher(), null, null);
					do_write(ccnd, theFile);
					
					System.out.println("Inserted file " + args[i] + ".");
					
				}
			}
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in put: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + args[startArg] + " " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot read file. " + e.getMessage());
			e.printStackTrace();
		} catch (XMLStreamException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
	
	private static void do_write(CCNDescriptor ccnd, File file) throws IOException {
		FileInputStream fis = new FileInputStream(file);
		int size = BLOCK_SIZE;
		byte [] buffer = new byte[BLOCK_SIZE];
		do {
			Library.logger().info("do_write: " + fis.available() + " bytes left.");
			if (size > fis.available())
				size = fis.available();
			if (size > 0) {
				fis.read(buffer, 0, size);
				ccnd.write(buffer, 0, size);
				Library.logger().info("do_write: wrote " + size + " bytes.");
			}
		} while (fis.available() > 0);
		ccnd.close();
	}
	
	public static void usage() {
		System.out.println("usage: put_file [-raw] <ccnname> <filename> [<filename> ...]");
	}

}
