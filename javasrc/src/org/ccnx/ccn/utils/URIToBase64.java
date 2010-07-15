package org.ccnx.ccn.utils;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.protocol.ContentName;

public class URIToBase64 {

	public static void usage() {
		System.out.println("usage: URIToBase64 uriString [uriString...]");
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 1) {
			usage();
			return;
		}
		
		for (int i=0; i < args.length; ++i) {
			processArg(args[i]);
		}
	}
	
	public static void processArg(String arg) {
		try {
			byte [] binary = ContentName.componentParseURI(arg);
			System.out.println(new String(DataUtils.base64Encode(binary)));
		} catch (Exception e) {
			e.printStackTrace();
		} 
	}

}
