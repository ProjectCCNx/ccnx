package com.parc.ccn.security.crypto;

import java.security.MessageDigest;

import com.parc.ccn.Library;

public class Digest {

	public static String DEFAULT_DIGEST = "SHA-256";
	
    public static byte[] hash(byte [] content) {
		return hash(DEFAULT_DIGEST, content);
	}
	
	public static byte [] hash(String digestAlgorithm, byte [] content) {
		byte [] hash = null;
		try {
	        MessageDigest md = MessageDigest.getInstance(digestAlgorithm);
	        hash = md.digest(content);
			return hash;
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			Library.logger().warning("Fatal Error: cannot find algorithm " + digestAlgorithm);
			throw new RuntimeException("Error: can't find " + digestAlgorithm + "!  " + ex.toString());
		}
	}

	/**
	 * Helper functions for building Merkle hash trees. Returns digest of
	 * two concatenated byte arrays.
	 * @param content1
	 * @param content2
	 * @return
	 */
	public static byte[] hash(byte [] content1, byte [] content2) {
		return hash(DEFAULT_DIGEST, content1, content2);
	}
	
	public static byte [] hash(String digestAlgorithm, byte [] content1, byte [] content2) {
		byte [] hash = null;
		try {
	        MessageDigest md = MessageDigest.getInstance(digestAlgorithm);
	        md.update(content1);
	        md.update(content2);
	        hash = md.digest();
			return hash;
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			Library.logger().warning("Fatal Error: cannot find algorithm " + digestAlgorithm);
			throw new RuntimeException("Error: can't find " + digestAlgorithm + "!  " + ex.toString());
		}
	}
}
