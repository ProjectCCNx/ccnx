package com.parc.ccn.apps;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SignatureException;
import java.util.ArrayList;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.BinaryXMLCodec;
import com.parc.ccn.data.util.TextXMLCodec;
import com.parc.ccn.library.StandardCCNLibrary;
import com.parc.ccn.network.CCNNetworkManager;

/**
 * Low-level writing of packets to file.  This program is designed to 
 * generate signed and encoded packets using only the base library facilities,
 * i.e. not even fragmentation and versioning but just basic interest and data
 * @author jthornto
 *
 */
public class puttap implements CCNInterestListener {

	public static final int CHUNK_SIZE = 432;
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length != 4) {
			usage();
			return;
		}
		
		new puttap().go(args[0], args[1], args[2], args[3]);
	}
	
	public void go(String encFlag, String ccnName, String tapName, String readName) {
		try {
			if (encFlag.equals("0")) {
				SystemConfiguration.setDefaultEncoding(TextXMLCodec.codecName());
			} else {
				SystemConfiguration.setDefaultEncoding(BinaryXMLCodec.codecName());
			}
			File theFile = new File(readName);
			if (!theFile.exists()) {
				System.out.println("No such file: " + readName);
				usage();
				return;
			}

			// Set up tap so packets get written to file
			CCNNetworkManager.getNetworkManager().setTap(tapName);
			
			// Get writing library 
			StandardCCNLibrary library = new StandardCCNLibrary();
			
			ContentName name = new ContentName(ccnName);
			
			// Register standing interest so our put's will flow
			// This must be through separate library instance so it 
			// appears that there is an interest from a separate app
			// because interest from the same app as the writer will 
			// not consume the data and therefore will block
			StandardCCNLibrary reader = new StandardCCNLibrary();
			reader.expressInterest(new Interest(ccnName), this);

			// Dump the file in small packets
	        InputStream is = new FileInputStream(theFile);
	        byte[] bytes = new byte[CHUNK_SIZE];
	        int i = 0;
	        while (is.read(bytes) >= 0) {
	        	library.put(new ContentName(name, new Integer(i++).toString()), bytes);
	        }
	        
	        // Need to call shutdown directly on manager at this point
	        CCNNetworkManager.getNetworkManager().shutdown();

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static void usage() {
		System.out.println("usage: puttap 0|1 <ccnname> <tapname> <filename>");
	}

	public void addInterest(Interest interest) {
		// Intentional no-op
	}

	public void cancelInterests() {
		// Intentional no-op
	}

	public Interest[] getInterests() {
		// Intentional no-op
		return null;
	}

	public int handleContent(ArrayList<ContentObject> results) {
		// Intentional no-op
		return 0;
	}

	public void interestTimedOut(Interest interest) {
		// Intentional no-op
	}

	public boolean matchesInterest(CompleteName name) {
		// Intentional no-op
		return false;
	}

}
