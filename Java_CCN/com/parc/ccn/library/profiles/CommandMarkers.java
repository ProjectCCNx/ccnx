package com.parc.ccn.library.profiles;

/**
 * A place for now to place command markers
 * We are using 0xC0 and 0xC1 for these
 * 
 * @author rasmusse
 *
 */
public class CommandMarkers {

	/**
	 * Repository "markers"
	 */
	public static final byte[] REPO_START_WRITE = {(byte)0xc0, 'R', 0x1};
	public static final byte[] REPO_GET_HEADER = {(byte)0xc0, 'R', 0x2};
	
	/**
	 * Nonce marker
	 */
	public static final byte[] NONCE_MARKER = {(byte)0xc0, 'N'};
}
