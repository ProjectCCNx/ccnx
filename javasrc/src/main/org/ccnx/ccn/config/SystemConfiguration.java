/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2011 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * A class encapsulating a number of system-level default parameters as well as helper
 * functionality for managing log output and printing debug data. Eventually will
 * be supported by an external configuration file for controlling key parameters.
 * 
 * The current basic logging infrastructure uses standard Java logging, 
 * controlled only by a system-wide Level value.
 * That value, as well as other logging-related parameters are currently managed by the
 * Log class, but should eventually migrate here. There is a facility for selective logging
 * control, by turning on and off logging for individual named "modules"; though that has
 * not yet been widely utilized. Eventually we should support separate log Level settings
 * for each module when necessary.
 */
public class SystemConfiguration {

	/**
	 * String constants, to define these in one place.
	 */
	public static final String STRING_FALSE = "false";
	public static final String STRING_TRUE = "true";

	/**
	 * System operation timeout. Very long timeout used to wait for system events
	 * such as stopping Daemons.
	 */
	public final static int SYSTEM_STOP_TIMEOUT = 30000;

	/**
	 * Very long timeout for network operations, in msec..
	 */
	public final static int MAX_TIMEOUT = 10000;

	/**
	 * Extra-long timeout, e.g. to get around reexpression timing issues.
	 */
	public final static int EXTRA_LONG_TIMEOUT = 6000;

	/**
	 * Longer timeout, for e.g. waiting for a latest version and being sure you
	 * have anything available locally in msec.
	 */
	public final static int LONG_TIMEOUT = 3000;

	/**
	 * Medium timeout, used as system default.
	 */
	public static final int MEDIUM_TIMEOUT = 1000;

	/**
	 * Short timeout; for things you expect to exist or not exist locally.
	 */
	public static final int SHORT_TIMEOUT = 300;
	
	protected static final String CCN_PROTOCOL_PROPERTY = "org.ccnx.protocol";
	
	public static final String DEFAULT_PROTOCOL = "TCP";  // UDP or TCP allowed
	public static NetworkProtocol AGENT_PROTOCOL = null; // Set up below
	public static final String AGENT_PROTOCOL_PROPERTY = "org.ccnx.agent.protocol";
	public static final String AGENT_PROTOCOL_ENVIRONMENT_VARIABLE = "CCN_AGENT_PROTOCOL";
	
	/**
	 * Controls whether we should exit on severe errors in the network manager. This should only be
	 * set true in automated tests. In live running code, we hope to be able to recover instead.
	 */
	public static final boolean DEFAULT_EXIT_ON_NETWORK_ERROR = false;
	public static boolean EXIT_ON_NETWORK_ERROR = DEFAULT_EXIT_ON_NETWORK_ERROR;
	public static final String CCN_EXIT_ON_NETWORK_ERROR_PROPERTY = "org.ccnx.ExitOnNetworkError";
	public static final String CCN_EXIT_ON_NETWORK_ERROR_ENVIRONMENT_VARIABLE = "CCN_EXIT_ON_NETERROR";
	
	/**
	 * Interest reexpression period
	 * TODO - This is (currently) an architectural constant. Not all code has been changed to use it.
	 */
	public static final int INTEREST_REEXPRESSION_DEFAULT = 4000;

	public enum DEBUGGING_FLAGS {DEBUG_SIGN, DEBUG_VERIFY, DUMP_DAEMONCMD, REPO_EXITDUMP};
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
	public static String DEBUG_DATA_DIRECTORY = null;

	/** 
	 * Tunable timeouts as well as timeout defaults.
	 */

	/**
	 * Enumerated Name List looping timeout in ms.
	 * Default is 300ms
	 */
	protected static final String CHILD_WAIT_INTERVAL_PROPERTY = "org.ccnx.EnumList.WaitInterval";
	public final static int CHILD_WAIT_INTERVAL_DEFAULT = 300;
	public static int CHILD_WAIT_INTERVAL = CHILD_WAIT_INTERVAL_DEFAULT;

	/**
	 * Default timeout for the flow controller
	 */
	protected static final String FC_TIMEOUT_PROPERTY = "org.ccnx.fc.timeout";
	public final static int FC_TIMEOUT_DEFAULT = MAX_TIMEOUT;
	public static int FC_TIMEOUT = FC_TIMEOUT_DEFAULT;

	/**
	 * Allow override to only save to a local repository
	 */
	protected static final String FC_LOCALREPOSITORY_PROPERTY = "org.ccnx.fc.localrepository";
	protected final static String FC_LOCALREPOSITORY_ENV_VAR = "FC_LOCALREPOSITORY";
	public final static boolean FC_LOCALREPOSITORY_DEFAULT = false;
	public static boolean FC_LOCALREPOSITORY = FC_LOCALREPOSITORY_DEFAULT;

	/**
	 * How long to wait for a service discovery timeout in CCNNetworkManager, in ms
	 *
	 * This should be longer than the interest timeout to permit at least one re-expression.
	 */
	protected static final String CCNDID_DISCOVERY_TIMEOUT_PROPERTY = "org.ccnx.ccndid.timeout";
	public final static int CCNDID_DISCOVERY_TIMEOUT_DEFAULT = 4200;
	public static int CCNDID_DISCOVERY_TIMEOUT = CCNDID_DISCOVERY_TIMEOUT_DEFAULT;

	/**
	 * Pipeline size for pipeline in CCNAbstractInputStream
	 * Default is 4
	 */
	protected static final String PIPELINE_SIZE_PROPERTY = "org.ccnx.PipelineSize";
	protected static final String PIPELINE_SIZE_ENV_VAR = "JAVA_PIPELINE_SIZE";
	public static int PIPELINE_SIZE = 4;

	/**
	 * Pipeline segment attempts for pipeline in CCNAbstractInputStream
	 * Default is 5
	 */
	protected static final String PIPELINE_ATTEMPTS_PROPERTY = "org.ccnx.PipelineAttempts";
	protected static final String PIPELINE_ATTEMPTS_ENV_VAR = "JAVA_PIPELINE_ATTEMPTS";
	public static int PIPELINE_SEGMENTATTEMPTS = 5;

	/**
	 * Pipeline round trip time factor for pipeline in CCNAbstractInputStream
	 * Default is 2
	 */
	protected static final String PIPELINE_RTT_PROPERTY = "org.ccnx.PipelineRTTFactor";
	protected static final String PIPELINE_RTT_ENV_VAR = "JAVA_PIPELINE_RTTFACTOR";
	public static int PIPELINE_RTTFACTOR = 2;

	/**
	 * Pipeline stat printouts in CCNAbstractInputStream
	 * Default is off
	 */
	protected static final String PIPELINE_STATS_PROPERTY = "org.ccnx.PipelineStats";
	protected static final String PIPELINE_STATS_ENV_VAR = "JAVA_PIPELINE_STATS";
	public static boolean PIPELINE_STATS = false;
	
	/**
	 * Default block size for IO
	 */
	protected static final String BLOCK_SIZE_PROPERTY = "ccn.lib.blocksize";
	protected static final String BLOCK_SIZE_ENV_VAR = "CCNX_BLOCKSIZE";
	public static int BLOCK_SIZE = SegmentationProfile.DEFAULT_BLOCKSIZE;

	/**
	 * Backwards-compatible handling of old header names. 
	 * Current default is true; eventually will be false.
	 */
	protected static final String OLD_HEADER_NAMES_PROPERTY = "org.ccnx.OldHeaderNames";
	protected static final String OLD_HEADER_NAMES_ENV_VAR = "CCNX_OLD_HEADER_NAMES";
	public static boolean OLD_HEADER_NAMES = true;


	/**
	 * Timeout used for communication with local 'ccnd' for control operations.
	 *
	 * An example is Face Creation and Prefix Registration.
	 * Should be longer than the interest timeout to permit at least one re-expression.
	 * TODO - ccnop would properly be spelled ccndop
	 */
	protected static final String CCND_OP_TIMEOUT_PROPERTY = "org.ccnx.ccnop.timeout";
	protected final static String CCND_OP_TIMEOUT_ENV_VAR = "CCND_OP_TIMEOUT";
	public final static int CCND_OP_TIMEOUT_DEFAULT = 4200;
	public static int CCND_OP_TIMEOUT = CCND_OP_TIMEOUT_DEFAULT;

	/**
	 * System default timeout
	 */
	protected static final String CCNX_TIMEOUT_PROPERTY = "org.ccnx.default.timeout";
	protected final static String CCNX_TIMEOUT_ENV_VAR = "CCNX_TIMEOUT";
	public final static int CCNX_TIMEOUT_DEFAULT = EXTRA_LONG_TIMEOUT;

	/**
	 * GetLatestVersion attempt timeout.
	 * TODO  This timeout is set to MEDIUM_TIMEOUT to work around the problem
	 * in ccnd where some interests take >300ms (and sometimes longer, have seen periodic delays >800ms)
	 * when that bug is found and fixed, this can be reduced back to the SHORT_TIMEOUT.
	 * long attemptTimeout = SystemConfiguration.SHORT_TIMEOUT;
	 */
	protected static final String GLV_ATTEMPT_TIMEOUT_PROPERTY = "org.ccnx.glv.attempt.timeout";
	protected final static String GLV_ATTEMPT_TIMEOUT_ENV_VAR = "GLV_ATTEMPT_TIMEOUT";
	public final static int GLV_ATTEMPT_TIMEOUT_DEFAULT = SHORT_TIMEOUT;
	public static int GLV_ATTEMPT_TIMEOUT = GLV_ATTEMPT_TIMEOUT_DEFAULT;

	/**
	 * "Short timeout" that can be set
	 */
	protected static final String SETTABLE_SHORT_TIMEOUT_PROPERTY = "org.ccnx.short.timeout";
	protected final static String SETTABLE_SHORT_TIMEOUT_ENV_VAR = "SETTABLE_SHORT_TIMEOUT";
	public static int SETTABLE_SHORT_TIMEOUT = SHORT_TIMEOUT;

	/**
	 * Should we dump netmanager statistics on shutdown
	 */
	protected static final String DUMP_NETMANAGER_STATS_PROPERTY = "org.ccnx.dump.netmanager.stats";
	protected final static String DUMP_NETMANAGER_STATS_ENV_VAR = "CCNX_DUMP_NETMANAGER_STATS";
	public static boolean DUMP_NETMANAGER_STATS = false;


	/**
	 * Settable system default timeout.
	 */
	protected static int _defaultTimeout = CCNX_TIMEOUT_DEFAULT;

	/**
	 * Get system default timeout.
	 * @return the default timeout.
	 */
	public static int getDefaultTimeout() { return _defaultTimeout; }

	/**
	 * Set system default timeout.
	 */
	public static void setDefaultTimeout(int newTimeout) { _defaultTimeout = newTimeout; }

	/**
	 * No timeout. Should be single value used in all places in the code where you
	 * want to block forever.
	 */
	public final static int NO_TIMEOUT = -1;

	/**
	 * Set the maximum number of attempts that VersioningProfile.getLatestVersion will
	 * try to get a later version of an object.
	 */
	public static final int GET_LATEST_VERSION_ATTEMPTS = 10;


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
	
	public static final int SYSTEM_THREAD_LIFE = 10;
	public static ThreadPoolExecutor _systemThreadpool = (ThreadPoolExecutor)Executors.newCachedThreadPool();

	/**
	 * Obtain the management bean for this runtime if it is available.
	 * The class of the management bean is discovered at runtime and there
	 * should be no static dependency on any particular bean class.
	 * @return the bean or null if none available
	 */
	public synchronized static Object getManagementBean() {
		// Check if we already have a management bean; retrieve only
		// once per VM
		if (null == runtimeMXBean) {
			ClassLoader cl = SystemConfiguration.class.getClassLoader();
			try {
				Class<?> mgmtclass = cl.loadClass("java.lang.management.ManagementFactory");
				Method getRuntimeMXBean = mgmtclass.getDeclaredMethod("getRuntimeMXBean", (Class[])null);
				runtimeMXBean = getRuntimeMXBean.invoke(mgmtclass, (Object[])null);
			} catch (Exception ex) {
				Log.log(Level.WARNING, "Management bean unavailable: {0}", ex.getMessage());
			}
		}
		return runtimeMXBean;
	}

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

	static {
		// NOTE: do not call Log.* methods from the initializer as log depends on SystemConfiguration.
		
		// Allow override of basic protocol
		String proto = SystemConfiguration.retrievePropertyOrEnvironmentVariable(AGENT_PROTOCOL_PROPERTY, AGENT_PROTOCOL_ENVIRONMENT_VARIABLE, DEFAULT_PROTOCOL);

		boolean found = false;
		for (NetworkProtocol p : NetworkProtocol.values()) {
			String pAsString = p.toString();
			if (proto.equalsIgnoreCase(pAsString)) {
//				if (!pAsString.equalsIgnoreCase(DEFAULT_PROTOCOL)) System.err.println("CCN agent protocol changed to " + pAsString + " per property");
				AGENT_PROTOCOL = p;
				found = true;
				break;
			}
		}
		if (!found) {
			System.err.println("The protocol must be UDP(17) or TCP (6)");
			throw new IllegalArgumentException("Invalid protocol '" + proto + "' specified in " + AGENT_PROTOCOL_PROPERTY);
		}
		
		// Allow override of exit on network error
		try {
			EXIT_ON_NETWORK_ERROR = Boolean.parseBoolean(retrievePropertyOrEnvironmentVariable(CCN_EXIT_ON_NETWORK_ERROR_PROPERTY, CCN_EXIT_ON_NETWORK_ERROR_ENVIRONMENT_VARIABLE,
					Boolean.toString(DEFAULT_EXIT_ON_NETWORK_ERROR)));
//			System.err.println("CCND_OP_TIMEOUT = " + CCND_OP_TIMEOUT);
		} catch (NumberFormatException e) {
			System.err.println("The exit on network error must be an boolean.");
			throw e;
		}
		
		// Allow override of default enumerated name list child wait timeout.
		try {
			CHILD_WAIT_INTERVAL = Integer.parseInt(System.getProperty(CHILD_WAIT_INTERVAL_PROPERTY, Integer.toString(CHILD_WAIT_INTERVAL_DEFAULT)));
			//			System.err.println("CHILD_WAIT_INTERVAL = " + CHILD_WAIT_INTERVAL);
		} catch (NumberFormatException e) {
			System.err.println("The ChildWaitInterval must be an integer.");
			throw e;
		}

		// Allow override of default pipeline size for CCNAbstractInputStream
		try {
			PIPELINE_SIZE = Integer.parseInt(retrievePropertyOrEnvironmentVariable(PIPELINE_SIZE_PROPERTY, PIPELINE_SIZE_ENV_VAR, "4"));
			//PIPELINE_SIZE = Integer.parseInt(System.getProperty(PIPELINE_SIZE_PROPERTY, "4"));
		} catch (NumberFormatException e) {
			System.err.println("The PipelineSize must be an integer.");
			throw e;
		}

		// Allow override of default pipeline size for CCNAbstractInputStream
		try {
			PIPELINE_SEGMENTATTEMPTS = Integer.parseInt(retrievePropertyOrEnvironmentVariable(PIPELINE_ATTEMPTS_PROPERTY, PIPELINE_ATTEMPTS_ENV_VAR, "5"));
			//PIPELINE_SIZE = Integer.parseInt(System.getProperty(PIPELINE_SIZE_PROPERTY, "4"));
		} catch (NumberFormatException e) {
			System.err.println("The PipelineAttempts must be an integer.");

		}

		// Allow override of default pipeline rtt multiplication factor for CCNAbstractInputStream
		try {
			PIPELINE_RTTFACTOR = Integer.parseInt(retrievePropertyOrEnvironmentVariable(PIPELINE_RTT_PROPERTY, PIPELINE_RTT_ENV_VAR, "2"));
		} catch (NumberFormatException e) {
			System.err.println("The PipelineRTTFactor must be an integer.");

		}

		// Allow printing of pipeline stats in CCNAbstractInputStream
		PIPELINE_STATS = Boolean.parseBoolean(retrievePropertyOrEnvironmentVariable(PIPELINE_STATS_PROPERTY, PIPELINE_STATS_ENV_VAR, STRING_FALSE));

		// Allow override of default ccndID discovery timeout.
		try {
			CCNDID_DISCOVERY_TIMEOUT = Integer.parseInt(System.getProperty(CCNDID_DISCOVERY_TIMEOUT_PROPERTY, Integer.toString(CCNDID_DISCOVERY_TIMEOUT_DEFAULT)));
			//			System.err.println("CCNDID_DISCOVERY_TIMEOUT = " + CCNDID_DISCOVERY_TIMEOUT);
		} catch (NumberFormatException e) {
			System.err.println("The ccndID discovery timeout must be an integer.");
			throw e;
		}

		// Allow override of default flow controller timeout.
		try {
			FC_TIMEOUT = Integer.parseInt(System.getProperty(FC_TIMEOUT_PROPERTY, Integer.toString(FC_TIMEOUT_DEFAULT)));
			//			System.err.println("FC_TIMEOUT = " + FC_TIMEOUT);
		} catch (NumberFormatException e) {
			System.err.println("The default flow controller timeout must be an integer.");
			throw e;
		}

		// Allow override for local repository override 
		try {
			FC_LOCALREPOSITORY = Boolean.parseBoolean(retrievePropertyOrEnvironmentVariable(FC_LOCALREPOSITORY_PROPERTY, FC_LOCALREPOSITORY_ENV_VAR, Boolean.toString(FC_LOCALREPOSITORY_DEFAULT)));
		} catch (NumberFormatException e) {
			System.err.println("The local repository flow controller override must be a boolean.");
			throw e;
		}

		// Allow override of ccn default timeout.
		try {
			_defaultTimeout = Integer.parseInt(retrievePropertyOrEnvironmentVariable(CCNX_TIMEOUT_PROPERTY, CCNX_TIMEOUT_ENV_VAR, Integer.toString(CCNX_TIMEOUT_DEFAULT)));
			//			System.err.println("CCNX_TIMEOUT = " + CCNX_TIMEOUT);
		} catch (NumberFormatException e) {
			System.err.println("The ccnd default timeout must be an integer.");
			throw e;
		}

		// Allow override of ccnd op timeout.
		try {
			CCND_OP_TIMEOUT = Integer.parseInt(System.getProperty(CCND_OP_TIMEOUT_PROPERTY, Integer.toString(CCND_OP_TIMEOUT_DEFAULT)));
			//			System.err.println("CCND_OP_TIMEOUT = " + CCND_OP_TIMEOUT);
		} catch (NumberFormatException e) {
			System.err.println("The ccnd op timeout must be an integer.");
			throw e;
		}

		// Allow override of getLatestVersion attempt timeout.
		try {
			GLV_ATTEMPT_TIMEOUT = Integer.parseInt(retrievePropertyOrEnvironmentVariable(GLV_ATTEMPT_TIMEOUT_PROPERTY, GLV_ATTEMPT_TIMEOUT_ENV_VAR, Integer.toString(GLV_ATTEMPT_TIMEOUT_DEFAULT)));
			//			System.err.println("GLV_ATTEMPT_TIMEOUT = " + GLV_ATTEMPT_TIMEOUT);
		} catch (NumberFormatException e) {
			System.err.println("The getlatestversion attempt timeout must be an integer.");
			throw e;
		}

		// Allow override of settable short timeout.
		try {
			SETTABLE_SHORT_TIMEOUT = Integer.parseInt(retrievePropertyOrEnvironmentVariable(SETTABLE_SHORT_TIMEOUT_PROPERTY, SETTABLE_SHORT_TIMEOUT_ENV_VAR, Integer.toString(SHORT_TIMEOUT)));
			//			System.err.println("SETTABLE_SHORT_TIMEOUT = " + SETTABLE_SHORT_TIMEOUT);
		} catch (NumberFormatException e) {
			System.err.println("The settable short timeout must be an integer.");
			throw e;
		}
		
		_systemThreadpool.setKeepAliveTime(SYSTEM_THREAD_LIFE, TimeUnit.SECONDS);
		
		// Dump netmanager statistics if requested
		DUMP_NETMANAGER_STATS = Boolean.parseBoolean(retrievePropertyOrEnvironmentVariable(DUMP_NETMANAGER_STATS_PROPERTY, DUMP_NETMANAGER_STATS_ENV_VAR, Boolean.toString(DUMP_NETMANAGER_STATS)));
	
		// Allow override of block size
		// TODO should we make sure its a reasonable number?
		try {
			BLOCK_SIZE = Integer.parseInt(retrievePropertyOrEnvironmentVariable(BLOCK_SIZE_PROPERTY, BLOCK_SIZE_ENV_VAR, Integer.toString(BLOCK_SIZE)));
		} catch (NumberFormatException e) {
			System.err.println("The settable block size must be an integer.");
			throw e;
		}
		
		// Handle old-style header names
		OLD_HEADER_NAMES = Boolean.parseBoolean(
				retrievePropertyOrEnvironmentVariable(OLD_HEADER_NAMES_PROPERTY, OLD_HEADER_NAMES_ENV_VAR, STRING_TRUE));

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

	/**
	 * Management bean for this runtime, if available.  This is not dependent
	 * upon availability of any particular class but discovered dynamically 
	 * from what is available at runtime.
	 */
	private static Object runtimeMXBean = null;

	public static void setDebugFlag(DEBUGGING_FLAGS debugFlag, boolean value) {
		Log.info("Debug Flag {0} set to {1}", debugFlag.toString(), value);
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
			flags.append(availableFlags[i]);
		}
		return flags.toString();
	}

	public static void setDebugFlag(String debugFlag) {
		setDebugFlag(debugFlag, true);
	}

	public static void setDebugDataDirectory(String dir) {
		DEBUG_DATA_DIRECTORY=dir;
	}

	public static void outputDebugData(ContentName name, XMLEncodable data) {
		try {
			byte [] encoded = data.encode();
			outputDebugData(name, encoded);
		} catch (ContentEncodingException ex) {
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
			try {
				fos.write(data);
			} finally {
				fos.close();
			}
		} catch (Exception e) {
			Log.warning("Exception attempting to log debug data for name: " + name.toString() + " " + e.getClass().getName() + ": " + e.getMessage());
		}
	}

	public static void outputDebugObject(File dataDir, String postfix, ContentObject object) {
		// Output debug data under a given name.
		try {	
			if (!dataDir.exists()) {
				if (!dataDir.mkdirs()) {
					Log.warning("outputDebugData: Cannot create debug data directory: " + dataDir.getAbsolutePath());
					return;
				}
			}
			/*
			File outputParent = new File(dataDir, object.name().toString());
			if (!outputParent.exists()) {
				if (!outputParent.mkdirs()) {
					Log.warning("outputDebugData: cannot create data parent directory: " + outputParent);
				}
			}
			 */
			byte [] objectDigest = object.digest();
			StringBuffer contentName = new StringBuffer(new BigInteger(1, objectDigest).toString(DEBUG_RADIX));
			if (null != postfix) {
				contentName = contentName.append(postfix);
			}
			contentName.append(".ccnb");
			File outputFile = new File(dataDir, contentName.toString());

			Log.finest("Attempting to output debug data for name " + object.name().toString() + " to file " + outputFile.getAbsolutePath());

			FileOutputStream fos = new FileOutputStream(outputFile);
			try {
				object.encode(fos);
			} finally {
				fos.close();
			}
		} catch (Exception e) {
			Log.warning("Exception attempting to log debug data for name: " + object.name().toString() + " " + e.getClass().getName() + ": " + e.getMessage());
		}
	}

	public static void outputDebugObject(ContentObject object) {
		outputDebugObject(new File(DEBUG_DATA_DIRECTORY), null, object);
	}


	/**
	 * Log information about an object at level Level.INFO. See logObject(Level, String, ContentObject) for details.
	 * @param message String to prefix output with
	 * @param co ContentObject to print debugging information about. 
	 * @see logObject(Level, String, ContentObject)
	 */
	public static void logObject(String message, ContentObject co) {
		logObject(Level.INFO, message, co);
	}

	/**
	 * Log the gory details of an object, including debugging information relevant to object signing.
	 * @param level log Level to control printing of log messages
	 * @param message message to prefix output with
	 * @param co ContentObject to print debugging information for
	 */
	public static void logObject(Level level, String message, ContentObject co) {
		try {
			byte [] coDigest = CCNDigestHelper.digest(co.encode());
			byte [] tbsDigest = CCNDigestHelper.digest(ContentObject.prepareContent(co.name(), co.signedInfo(), co.content()));
			Log.log(level, message + " name: {0} timestamp: {1} digest: {2}  tbs: {3}.",
					co.name(), co.signedInfo().getTimestamp(), CCNDigestHelper.printBytes(coDigest, DEBUG_RADIX),
					CCNDigestHelper.printBytes(tbsDigest, DEBUG_RADIX));
		} catch (ContentEncodingException xs) {
			Log.log(level, "Cannot encode object for logging: {0}.", co.name());
		}

	}

	protected static String _loggingConfiguration;
	/**
	 * TODO: Fix this incorrect comment
	 * Property to turn off access control flags. Set it to any value and it will turn off
	 * access control; used for testing.
	 */
	public static final String LOGGING_CONFIGURATION_PROPERTY = "com.parc.ccn.LoggingConfiguration";

	/**
	 * Strings of interest to be set in the logging configuration
	 */
	public static final String DETAILED_LOGGER = "DetailedLogger";

	/**
	 * Configure logging itself. This is a set of concatenated strings set as a 
	 * command line property; it can be used to set transparent properties read 
	 * at various points in the code.
	 */
	public static String getLoggingConfiguration() {
		if (null == _loggingConfiguration) {
			_loggingConfiguration = System.getProperty(LOGGING_CONFIGURATION_PROPERTY, "");

		}
		return _loggingConfiguration;
	}

	public static boolean hasLoggingConfigurationProperty(String property) {
		if (null == property)
			return false;
		return getLoggingConfiguration().contains(property);
	}

	/**
	 * Gets a process identifier (PID) for the running Java Virtual Machine (JVM) process, if possible. 
	 * Java does not provide a supported way to obtain the operating system (OS) PID in general.
	 * This method uses technique(s) for getting the OS PID that are not necessarily portable
	 * to all Java execution environments.
	 * The PID is returned as a String value.  Where possible, the result will be the string representation of an integer
	 * that is probably identical to the OS PID of the JVM process that executed this method.  In other cases,
	 * the result will be an implementation-dependent string name that identifies the JVM instance but does not exactly 
	 * match the OS PID.  The returned value will not contain spaces.
	 * If no identifier can be obtained, the result will be null.
	 * @return A Process Identifier (PID) of the JVM (not necessarily the OS PID) or null if not available
	 * @see <a href="http://blog.igorminar.com/2007/03/how-java-application-can-discover-its.html">Techniques for Discovering PID</a>
	 */
	public static String getPID() {
		// We try the JVM mgmt bean if available, reported to work on variety
		// of operating systems on the Sun JVM.  The bean is obtained once per VM,
		// the other work to get the ID is done here.
		Object bean = getManagementBean();
		if (null == getManagementBean()) {
			return null;
		}

		try {
			String pid = null;
			String vmname = null;

			Method getName = bean.getClass().getDeclaredMethod("getName", (Class[]) null);
			if (null == getName) {
				return null;
			}
			getName.setAccessible(true);
			vmname = (String) getName.invoke(bean, (Object[]) null);

			if (null == vmname) {
				return null;
			}

			// Hopefully the string is in the form "60447@ice.local", where we can pull
			// out the integer hoping it is identical to the OS PID
			Pattern exp = Pattern.compile("^(\\d+)@\\S+$");
			Matcher match = exp.matcher(vmname);
			if (match.matches()) {
				pid = match.group(1);
			} else {
				// We don't have a candidate to match the OS PID, but we have the JVM name
				// from the mgmt bean itself so that will have to do, cleaned of spaces
				pid = vmname.replaceAll("\\s+", "_");
			}
			return pid;
		} catch (Exception e) {
			return null;
		}
	}

	protected static Boolean _accessControlDisabled;
	/**
	 * Property to turn off access control flags. Set it to any value and it will turn off
	 * access control; used for testing.
	 */
	public static final String ACCESS_CONTROL_DISABLED_PROPERTY = "com.parc.ccn.DisableAccessControl";

	/**
	 * Allow control of access control at the command line.
	 */
	public synchronized static boolean disableAccessControl() {
		if (null == _accessControlDisabled) {
			_accessControlDisabled = (null != System.getProperty(ACCESS_CONTROL_DISABLED_PROPERTY));
		}
		return _accessControlDisabled;
	}

	public static void setAccessControlDisabled(boolean accessControlDisabled) {
		_accessControlDisabled = accessControlDisabled;
	}

	/**
	 * Retrieve a string that might be stored as an environment variable, or
	 * overridden on the command line. If the command line variable is set, return
	 * its (String) value; if not, return the environment variable value if available;
	 * Caller should synchronize as appropriate.
	 * @return The value in force for this variable, or null if unset.
	 */
	public static String retrievePropertyOrEnvironmentVariable(String javaPropertyName, String environmentVariableName, String defaultValue) { 
		// First try the command line property.
		String value = null;
		if (null != javaPropertyName) {
			value = System.getProperty(javaPropertyName);
		}
		if ((null == value) && (null != environmentVariableName)) {
			// Try for an environment variable.
			value = System.getenv(environmentVariableName);
		}
		if (null == value)
			value = defaultValue;
		return value;
	}
}
