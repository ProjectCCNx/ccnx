package org.ccnx.ccn.config;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;


public class SystemConfiguration {
	
	public enum DEBUGGING_FLAGS {DEBUG_SIGNATURES};
	protected static HashMap<DEBUGGING_FLAGS,Boolean> DEBUG_FLAG_VALUES = new HashMap<DEBUGGING_FLAGS,Boolean>();

	/**
	 * Property to set debug flags.
	 */
	public static final String DEBUG_FLAG_PROPERTY = "com.parc.ccn.DebugFlags";
	
	/**
	 * Property to set directory to dump debug data.
	 */
	public static final String DEBUG_DATA_DIRECTORY_PROPERTY = "com.parc.ccn.DebugDataDirectory";
	protected static final String DEFAULT_DEBUG_DATA_DIRECTORY = "./CCN_DEBUG_DATA";
	protected static String DEBUG_DATA_DIRECTORY = null;
	
	/** 
	 * Tunable timeouts as well as timeout defaults.
	 */
	public static long TIMEOUT_FOREVER = 0;
	
	/**
	 * Can set compile-time default encoding here. Choices are
	 * currently "Text" and "Binary", or better yet
	 * BinaryXMLCodec.codecName() or TextXMLCodec.codecName().
	 */
	protected static final String SYSTEM_DEFAULT_ENCODING = BinaryXMLCodec.codecName();
	
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
	
	public static final int DEBUG_RADIX = 34;
	
	/**
	 * Property to allow/disallow logging for individual modules
	 */
	protected static TreeMap<String, Boolean> loggingInfo = new TreeMap<String, Boolean>();

	static {
		// Allow override of default debug information.
		String debugFlags = System.getProperty(DEBUG_FLAG_PROPERTY);
		if (null != debugFlags) {
			String [] flags = debugFlags.split(":");
			for (int i=0; i < flags.length; ++i) {
				setDebugFlag(flags[i]);
			}
		}
			
		DEBUG_DATA_DIRECTORY = System.getProperty(DEBUG_DATA_DIRECTORY_PROPERTY, DEFAULT_DEBUG_DATA_DIRECTORY);
	}

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
	
	public static void setDefaultEncoding(String encoding) {
		DEFAULT_ENCODING = encoding;
	}
	
	public static boolean checkDebugFlag(DEBUGGING_FLAGS debugFlag) {
		Boolean result = DEBUG_FLAG_VALUES.get(debugFlag);
		if (null == result)
			return false;
		return result.booleanValue();
	}
	
	public static void setDebugFlag(DEBUGGING_FLAGS debugFlag, boolean value) {
		DEBUG_FLAG_VALUES.put(debugFlag, Boolean.valueOf(value));
	}

	public static void setDebugFlag(String debugFlag, boolean value) {
		try {
			DEBUGGING_FLAGS df = DEBUGGING_FLAGS.valueOf(debugFlag);
			setDebugFlag(df, value);
		} catch (IllegalArgumentException ax) {
			Log.info("Cannot set debugging flag, no known flag: " + debugFlag + ". Choices are: " + debugFlagList());
		}
	}
	
	public static String debugFlagList() {
		DEBUGGING_FLAGS [] availableFlags = DEBUGGING_FLAGS.values();
		StringBuffer flags = new StringBuffer();
		for (int i=0; i < availableFlags.length; ++i) {
			if (i > 0)
				flags.append(":");
			flags.append(availableFlags);
		}
		return flags.toString();
	}
	
	public static void setDebugFlag(String debugFlag) {
		setDebugFlag(debugFlag, true);
	}
	
	public static void outputDebugData(ContentName name, XMLEncodable data) {
		try {
			byte [] encoded = data.encode();
			outputDebugData(name, encoded);
		} catch (XMLStreamException ex) {
			Log.warning("Cannot encode object : " + name + " to output for debug.");
		}
	}
	
	public static void outputDebugData(ContentName name, byte [] data) {
		// Output debug data under a given name.
		try {	
			File dataDir = new File(DEBUG_DATA_DIRECTORY);
			if (!dataDir.exists()) {
				if (!dataDir.mkdirs()) {
					Log.warning("outputDebugData: Cannot create default debug data directory: " + dataDir.getAbsolutePath());
					return;
				}
			}
			File outputParent = new File(dataDir, name.toString());
			if (!outputParent.exists()) {
				if (!outputParent.mkdirs()) {
					Log.warning("outputDebugData: cannot create data parent directory: " + outputParent);
				}
			}
			
			byte [] contentDigest = CCNDigestHelper.digest(data);
			String contentName = new BigInteger(1, contentDigest).toString(DEBUG_RADIX);
			File outputFile = new File(outputParent, contentName);
			
			Log.finest("Attempting to output debug data for name " + name.toString() + " to file " + outputFile.getAbsolutePath());
			
			FileOutputStream fos = new FileOutputStream(outputFile);
			fos.write(data);
			fos.close();
		} catch (Exception e) {
			Log.warning("Exception attempting to log debug data for name: " + name.toString() + " " + e.getClass().getName() + ": " + e.getMessage());
		}
	}
	
	public static void logObject(String message, ContentObject co) {
		logObject(Level.INFO, message, co);
	}
	
	public static void logObject(Level level, String message, ContentObject co) {
		try {
			byte [] coDigest = CCNDigestHelper.digest(co.encode());
			byte [] tbsDigest = CCNDigestHelper.digest(ContentObject.prepareContent(co.name(), co.signedInfo(), co.content()));
			Log.log(level, message + " name: " + co.name() +  " timestamp: " + co.signedInfo().getTimestamp() + " digest: " + CCNDigestHelper.printBytes(coDigest, DEBUG_RADIX) + " tbs: " + CCNDigestHelper.printBytes(tbsDigest, DEBUG_RADIX));
		} catch (XMLStreamException xs) {
			Log.log(level, "Cannot encode object for logging: " + co.name());
		}
		
	}
	
	/**
	 * Set logging for a particular module. This could (should?) be
	 * modified to allow use of a properties file.
	 * 
	 * @param name
	 * @param value
	 */
	public static void setLogging(String name, Boolean value) {
		loggingInfo.put(name, value);
	}
	
	/**
	 * Get logging for a particular module. To maintain the "status quo"
	 * we say to go ahead with the logging if logging was never setup.
	 * @param name
	 * @return
	 */
	public static boolean getLogging(String name) {
		Boolean value = loggingInfo.get(name);
		return value == null ? true : value;
	}

}
