package com.parc.ccn.apps;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.PublicKey;
import java.util.ArrayList;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.BinaryXMLCodec;
import com.parc.ccn.data.util.TextXMLCodec;
import com.parc.ccn.library.CCNLibrary;
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
		if ((args.length < 4) || (args.length > 5)) {
			usage();
		}
		
		boolean result = (new puttap().go(args[0], args[1], args[2], args[3], ((args.length == 5) ? args[4] : null)));
		if (result) {
			System.exit(0);
		} else {
			System.exit(1);
		}
	}
	
	public boolean go(String encFlag, String ccnName, String tapName, String readName, String verifyFlag) {
		CCNNetworkManager manager = null;
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
				return false;
			}
			
			boolean verify = false;
			if ((null != verifyFlag) && (verifyFlag.equals("-s")))
				verify = true;

			// Get writing library 
			CCNLibrary library = CCNLibrary.open();
			manager = library.getNetworkManager();
			// Set up tap so packets get written to file
			manager.setTap(tapName);
			
			ContentName name = ContentName.fromURI(ccnName);
			
			// Register standing interest so our put's will flow
			// This must be through separate library instance so it 
			// appears that there is an interest from a separate app
			// because interest from the same app as the writer will 
			// not consume the data and therefore will block
			if (false) {
				// new library semantics makes this unnecessary.
				CCNLibrary reader = CCNLibrary.open();
				reader.expressInterest(new Interest(ccnName), this);
			}
			
			PublicKey publicKey = null;
			// If we're verifying, pull our default public key as that's what we're using.
			if (verify)
				publicKey = library.keyManager().getDefaultPublicKey();

			// Dump the file in small packets
	        InputStream is = new FileInputStream(theFile);
	        byte[] bytes = new byte[CHUNK_SIZE];
	        int i = 0;
	        while (is.read(bytes) >= 0) {
	        	ContentObject cn = library.put(ContentName.fromNative(name, new Integer(i++).toString()), bytes);
	        	if (!cn.validate()) {
	        		Library.logger().severe("BAD COMPLETENAME: does not validate");
	        		return false;
	        	}
	        	if (verify) {
	        		if (!ContentObject.verify(cn.name(), cn.authenticator(), bytes, cn.signature(), publicKey)) {
	        			Library.logger().severe("BAD SIGNATURE: puttap: object failed to verify: " + cn.name());
	        			return false;
	        		}
	        	}
	        }
	        
	        return true;

		} catch (Exception e) {
			e.printStackTrace();
			return false;
		} finally {
			if (null != manager) {
		        // Need to call shutdown directly on manager at this point
				manager.shutdown();
			}
		}

	}

	public static void usage() {
		System.out.println("usage: puttap 0|1 <ccnname> <tapname> <filename> [-s]");
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

	public Interest handleContent(ArrayList<ContentObject> results) {
		// Intentional no-op
		return null;
	}

	public void interestTimedOut(Interest interest) {
		// Intentional no-op
	}

	public boolean matchesInterest(CompleteName name) {
		// Intentional no-op
		return false;
	}

}
