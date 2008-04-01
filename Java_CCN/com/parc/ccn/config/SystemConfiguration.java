package com.parc.ccn.config;

import com.parc.ccn.data.util.TextXMLCodec;

public class SystemConfiguration {

	protected static final int DEFAULT_REPO2TRANSPORT_PORT = 43567;
	protected static final int DEFAULT_TRANSPORT2REPO_PORT = 43568;
	
	/**
	 * Can set compile-time default encoding here. Choices are
	 * currently "Text" and "Binary", or better yet
	 * BinaryXMLCodec.codecName() or TextXMLCodec.codecName().
	 */
	protected static final String SYSTEM_DEFAULT_ENCODING = TextXMLCodec.codecName();
	
	/**
	 * Run-time default. Set to command line property if given, if not,
	 * the system default above.
	 */
	protected static String DEFAULT_ENCODING = null;
	
	/**
	 * Command-line property to set default encoding
	 * @return
	 */
	protected static final String DEFAULT_ENCODING_PROPERTY = 
		"com.parc.ccn.data.DefaultEncoding";

	public static int defaultTransportPort() { return DEFAULT_REPO2TRANSPORT_PORT; }

	public static int defaultRepositoryPort() { return DEFAULT_TRANSPORT2REPO_PORT; }

	public static String getLocalHost() {
//		InetAddress.getLocalHost().toString(),
		return "127.0.0.1"; // using InetAddress.getLocalHost gives bad results
	}
	
	/**
	 * Order of precedence (highest to lowest):
	 * 
	 * 1) dynamic setting on an individual encoder or decoder,
	 * or in a single encode or decode call
	 * 
	 * 2) command-line property
	 * 
	 * 3) compiled-in default
	 * 
	 * The latter two are handled here, the former in the encoder/decoder
	 * machinery itself.
	 * @return
	 */
	public static String getDefaultEncoding() {
		if (null == DEFAULT_ENCODING) {
			// First time, check for argument
			String commandLineProperty = System.getProperty(DEFAULT_ENCODING_PROPERTY);
			if (null == commandLineProperty) 
				DEFAULT_ENCODING = SYSTEM_DEFAULT_ENCODING;
			else
				DEFAULT_ENCODING = commandLineProperty;
		}
		return DEFAULT_ENCODING;
	}
}
