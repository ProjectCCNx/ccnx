package org.ccnx.ccn.impl.security.crypto;

import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.security.crypto.util.DigestHelper;
import org.ccnx.ccn.impl.support.Log;


public class CCNDigestHelper extends DigestHelper {

	public static String DEFAULT_DIGEST_ALGORITHM = "SHA-256";
	// public static String DEFAULT_DIGEST_ALGORITHM = "SHA-1";
	public static int DEFAULT_DIGEST_LENGTH = 64;
	
	public CCNDigestHelper() {
		super();
		if (!_md.getAlgorithm().equals(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM)) {
			Log.logger().severe("Incorrect constructor in CCNDigestHelper -- picking up wrong digest algorithm!");
		}
	}
		
	public CCNDigestHelper(String digestAlgorithm) throws NoSuchAlgorithmException {
		super(digestAlgorithm);
	}

	/**
	 * Same digest preparation algorithm as ContentObject.
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] digestLeaf(
			String digestAlgorithm,
			XMLEncodable [] toBeSigneds,
			byte [][] additionalToBeSigneds) throws XMLStreamException, NoSuchAlgorithmException {
		
		if (null == toBeSigneds) {
			Log.logger().info("Value to be signed must not be null.");
			throw new XMLStreamException("Unexpected null content in digestLeaf!");
		}
		byte [][] encodedData = new byte [toBeSigneds.length + ((null != additionalToBeSigneds) ? additionalToBeSigneds.length : 0)][];
		for (int i=0; i < toBeSigneds.length; ++i) {
			encodedData[i] = toBeSigneds[i].encode();
		}
		if (null != additionalToBeSigneds) {
			for (int i=0,j=toBeSigneds.length; j < encodedData.length; ++i,++j) {
				encodedData[j] = additionalToBeSigneds[i];
			}
		}
		return DigestHelper.digest(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
				CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm, 
									encodedData);
	}

	@Override
	public String getDefaultDigest() { return DEFAULT_DIGEST_ALGORITHM; }
	
    public static byte[] digest(byte [] content) {
		if (null == content) {
			throw new IllegalArgumentException("Content cannot be null!");
		}
		return digest(content, 0, content.length);
	}
	
	public static byte [] digest(String digestAlgorithm, byte [] content) throws NoSuchAlgorithmException {
		if (null == content) {
			throw new IllegalArgumentException("Content cannot be null!");
		}
		return digest(digestAlgorithm, content, 0, content.length);
	}

	public static byte [] digest(byte [] content, int offset, int length) {
		CCNDigestHelper dh = new CCNDigestHelper();
		dh.update(content, offset, length);
		return dh.digest();
	}
	
	/**
	 * Need to handle this here to cope with digestAlgorithm = null correctly.
	 * @param digestAlgorithm
	 * @param content
	 * @param offset
	 * @param length
	 * @return
	 * @throws NoSuchAlgorithmException
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content, int offset, int length) throws NoSuchAlgorithmException {
		CCNDigestHelper dh = new CCNDigestHelper(digestAlgorithm);
		dh.update(content, offset, length);
		return dh.digest();
	}

	public static byte[] digest(byte [] content1, byte [] content2) {
		return digest(new byte [][]{content1, content2});
	}
	
	/**
	 * Helper functions for building Merkle hash trees. Returns digest of
	 * two concatenated byte arrays. If either is null, simply includes
	 * the non-null array.
	 * @param content1
	 * @param content2
	 * @return
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content1, byte [] content2) throws NoSuchAlgorithmException {
		return digest(digestAlgorithm, new byte [][]{content1, content2});
	}
	
	public static byte [] digest(byte [][] contents) {
		CCNDigestHelper dh = new CCNDigestHelper();
		for (int i=0; i < contents.length; ++i) {
			dh.update(contents[i], 0, contents[i].length);
		}
		return dh.digest();
	}	

	public static byte [] digest(String digestAlgorithm, byte [][] contents) throws NoSuchAlgorithmException {
		CCNDigestHelper dh = new CCNDigestHelper(digestAlgorithm);
		for (int i=0; i < contents.length; ++i) {
			dh.update(contents[i], 0, contents[i].length);
		}
		return dh.digest();
	}

	/**
	 * Digests some data and wraps it in a DigestInfo.
	 * @param digestAlgorithm
	 * @param content
	 * @return
	 * @throws CertificateEncodingException
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] encodedDigest(String digestAlgorithm, byte [] content) throws CertificateEncodingException, NoSuchAlgorithmException {
		byte [] digest = digest(digestAlgorithm, content);
		return digestEncoder(digestAlgorithm, digest);
	}
	
	public static byte [] encodedDigest(byte [] content) throws CertificateEncodingException {
		byte [] digest = digest(content);
		return digestEncoder(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, digest);
	}
}
