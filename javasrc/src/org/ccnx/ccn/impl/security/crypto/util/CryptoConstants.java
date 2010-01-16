package org.ccnx.ccn.impl.security.crypto.util;

public class CryptoConstants {

	/*
	 * The core encryption algorithms supported. Any native encryption
	 * mode supported by Java *should* work, but these are compactly
	 * encodable.
	 */
	public static final String CBC_MODE = "CBC";
	public static final String CTR_MODE = "CTR";
	public static final String CTR_POSTFIX = "/CTR/NoPadding";
	public static final String CBC_POSTFIX = "/CBC/PKCS5Padding";
	public static final String AES_ALGORITHM = "AES";
	public static final String AES_CTR_MODE = AES_ALGORITHM + CTR_POSTFIX;
	public static final String AES_CBC_MODE = AES_ALGORITHM + CBC_POSTFIX;

}
