package org.ccnx.ccn.profiles;

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
	
	/**
	 * Nonce marker
	 */
	public static final byte[] NONCE_MARKER = {(byte)0xc0, 'N'};
	
	/**
	 * Reserved bytes.
	 */
	public static byte[] CCN_reserved_markers = { (byte)0xC0, (byte)0xC1, (byte)0xF5, 
	(byte)0xF6, (byte)0xF7, (byte)0xF8, (byte)0xF9, (byte)0xFA, (byte)0xFB, (byte)0xFC, 
	(byte)0xFD, (byte)0xFE};
}
