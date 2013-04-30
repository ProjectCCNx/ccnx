/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

/**
 * PD org.ccnx.ccn.impl.support
 */
package org.ccnx.ccn.impl.support;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ccnx.ccn.config.SystemConfiguration;

/**
 * Wrapper for the standard java.util Logging classes.
 *
 * This allows log messages which will not actually be output due to being at a lower
 * level than the current logging level to not affect performance by performing expensive calculations to
 * compute their parameters.
 *
 * To send log entries to file, specify the log output directory using either the system property
 * org.ccnx.ccn.LogDir or the environment variable CCN_LOG_DIR.	 To override the default
 * log level for whatever program you are running, set the system property org.ccnx.ccn.LogLevel.
 */
public class Log {

	/**
	 * Allow override on command line or from configuration file.
	 */
	public static final String DEFAULT_APPLICATION_CLASS =
		"ccnx";

	public static final String DEFAULT_LOG_FILE = "ccn_";
	public static final String DEFAULT_LOG_SUFFIX = ".log";
	protected static final int offValue = Level.OFF.intValue();

	/**
	 * Properties and environment variables to set log parameters.
	 */
	public static final String DEFAULT_LOG_LEVEL_PROPERTY = "org.ccnx.ccn.LogLevel";
	public static final String DEFAULT_LOG_LEVEL_ENV = "CCN_LOG_LEVEL";

	public static final String LOG_DIR_PROPERTY = "org.ccnx.ccn.LogDir";
	public static final String LOG_DIR_ENV = "CCN_LOG_DIR";

	protected static Logger _systemLogger = null;
	protected static Logger[] _facilityLoggers = null;

	//static int _level;
	//static boolean useDefaultLevel = true; // reset if an external override of the default level was specified

	// ==========================================================
	// Facility based logging.
	// To add a new facility:
	//	  1) Add a public final static int for it
	//	  2) Add a facility name to FAC_LOG_LEVEL_PROPERTY array
	//	  3) Add a facility name to FAC_LOG_LEVEL_ENV array
	//	  4) Set the default log level in FAC_DEFAULT_LOG_LEVEL array
	// The facilities FAC_ALL and FAC_DEFAULT must be the values 0 and 1 respectively

	// Definition of logging facilities
	public static final int FAC_ALL			= 0;
	public static final int FAC_DEFAULT		= 1;
	public static final int FAC_PIPELINE	= 2;
	public static final int FAC_NETMANAGER	= 3;
	public static final int FAC_USER0		= 4;
	public static final int FAC_USER1		= 5;
	public static final int FAC_USER2		= 6;
	public static final int FAC_USER3		= 7;
	public static final int FAC_ACCESSCONTROL = 8;
	public static final int FAC_REPO 		= 9;
	public static final int FAC_TIMING		= 10;  // includes a timestamp
	public static final int FAC_TRUST		= 11; // trust enforcement
	public static final int FAC_KEYS		= 12; // key publishing/retrieval
	public static final int FAC_ENCODING	= 13;
	public static final int FAC_IO			= 14;
	public static final int FAC_SIGNING		= 15;
	public static final int FAC_VERIFY		= 16;
	public static final int FAC_USER4		= 17;
	public static final int FAC_USER5		= 18;
	public static final int FAC_USER6		= 19;
	public static final int FAC_USER7		= 20;
	public static final int FAC_USER8		= 21;
	public static final int FAC_USER9		= 22;
	public static final int FAC_USER10		= 23;
	public static final int FAC_USER11		= 24;
	public static final int FAC_USER12		= 25;
	public static final int FAC_USER13		= 26;
	public static final int FAC_USER14		= 27;
	public static final int FAC_USER15		= 28;
	public static final int FAC_TEST		= 29;	// For Junit tests
	public static final int FAC_SEARCH		= 30;	// name enumeration, path finder
	public static final int FAC_SYNC		= 31;	// sync api and sync control traffic processing


	protected static final String [] FAC_NAME = {
		"ccnx.All", 		// should never show up
		"ccnx.Default",
		"ccnx.Pipeline",
		"ccnx.NetManager",
		"ccnx.User0",
		"ccnx.User1",
		"ccnx.User2",
		"ccnx.User3",
		"ccnx.AccessControl",
		"ccnx.Repo",
		"ccnx.Timing",
		"ccnx.Trust",
		"ccnx.Keys",
		"ccnx.Encoding",
		"ccnx.IO",
		"ccnx.Signing",
		"ccnx.Verify",
		"ccnx.User4",
		"ccnx.User5",
		"ccnx.User6",
		"ccnx.User7",
		"ccnx.User8",
		"ccnx.User9",
		"ccnx.User10",
		"ccnx.User11",
		"ccnx.User12",
		"ccnx.User13",
		"ccnx.User14",
		"ccnx.User15",
		"ccnx.Test",
		"ccnx.Search",
		"ccnx.Sync",
	};


	// The System property name for each Facility
	public static final String [] FAC_LOG_LEVEL_PROPERTY = {
		DEFAULT_LOG_LEVEL_PROPERTY + ".All",
		DEFAULT_LOG_LEVEL_PROPERTY,
		DEFAULT_LOG_LEVEL_PROPERTY + ".Pipeline",
		DEFAULT_LOG_LEVEL_PROPERTY + ".NetManager",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User0",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User1",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User2",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User3",
		DEFAULT_LOG_LEVEL_PROPERTY + ".AccessControl",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Repo",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Timing",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Trust",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Keys",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Encoding",
		DEFAULT_LOG_LEVEL_PROPERTY + ".IO",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Signing",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Verify",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User4",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User5",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User6",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User7",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User8",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User9",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User10",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User11",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User12",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User13",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User14",
		DEFAULT_LOG_LEVEL_PROPERTY + ".User15",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Test",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Search",
		DEFAULT_LOG_LEVEL_PROPERTY + ".Sync",
	};

	// The environment variable for each facility
	public static final String [] FAC_LOG_LEVEL_ENV = {
		DEFAULT_LOG_LEVEL_ENV + "_ALL",
		DEFAULT_LOG_LEVEL_ENV,
		DEFAULT_LOG_LEVEL_ENV + "_PIPELINE",
		DEFAULT_LOG_LEVEL_ENV + "_NETMANAGER",
		DEFAULT_LOG_LEVEL_ENV + "_USER0",
		DEFAULT_LOG_LEVEL_ENV + "_USER1",
		DEFAULT_LOG_LEVEL_ENV + "_USER2",
		DEFAULT_LOG_LEVEL_ENV + "_USER3",
		DEFAULT_LOG_LEVEL_ENV + "_ACCESSCONTROL",
		DEFAULT_LOG_LEVEL_ENV + "_REPO",
		DEFAULT_LOG_LEVEL_ENV + "_TIMING",
		DEFAULT_LOG_LEVEL_ENV + "_TRUST",
		DEFAULT_LOG_LEVEL_ENV + "_KEYS",
		DEFAULT_LOG_LEVEL_ENV + "_ENCODING",
		DEFAULT_LOG_LEVEL_ENV + "_IO",
		DEFAULT_LOG_LEVEL_ENV + "_SIGNING",
		DEFAULT_LOG_LEVEL_ENV + "_VERIFY",
		DEFAULT_LOG_LEVEL_ENV + "_USER4",
		DEFAULT_LOG_LEVEL_ENV + "_USER5",
		DEFAULT_LOG_LEVEL_ENV + "_USER6",
		DEFAULT_LOG_LEVEL_ENV + "_USER7",
		DEFAULT_LOG_LEVEL_ENV + "_USER8",
		DEFAULT_LOG_LEVEL_ENV + "_USER9",
		DEFAULT_LOG_LEVEL_ENV + "_USER10",
		DEFAULT_LOG_LEVEL_ENV + "_USER11",
		DEFAULT_LOG_LEVEL_ENV + "_USER12",
		DEFAULT_LOG_LEVEL_ENV + "_USER13",
		DEFAULT_LOG_LEVEL_ENV + "_USER14",
		DEFAULT_LOG_LEVEL_ENV + "_USER15",
		DEFAULT_LOG_LEVEL_ENV + "_TEST",
		DEFAULT_LOG_LEVEL_ENV + "_SEARCH",
		DEFAULT_LOG_LEVEL_ENV + "_SYNC",
	};

	public static final Level [] FAC_LOG_LEVEL_DEFAULT = {
		Level.OFF,		// value has no meaning for All
		Level.INFO,		// Default
		Level.WARNING,	// Pipelining
		Level.INFO,		// NetManager
		Level.INFO,		// User0
		Level.INFO,		// User1
		Level.INFO,		// User2
		Level.INFO,		// User3
		Level.INFO,		// Access control
		Level.INFO,		// Repo
		Level.INFO,		// Timing
		Level.INFO,		// Trust
		Level.INFO,		// Keys
		Level.INFO,		// Encoding
		Level.INFO,		// IO
		Level.INFO,		// Signing
		Level.INFO,		// Verify
		Level.INFO,		// User4
		Level.INFO,		// User5
		Level.INFO,		// User6
		Level.INFO,		// User7
		Level.INFO,		// User8
		Level.INFO,		// User9
		Level.INFO,		// User10
		Level.INFO,		// User11
		Level.INFO,		// User12
		Level.INFO,		// User13
		Level.INFO,		// User14
		Level.INFO,		// User15
		Level.INFO,		// Test
		Level.INFO,		// Search
		Level.INFO,		// Sync
	};

	protected static Level [] _fac_level = new Level[FAC_LOG_LEVEL_PROPERTY.length];
	protected static int [] _fac_value = new int[FAC_LOG_LEVEL_PROPERTY.length];

	protected static boolean _timestamp = false;

	// ==========================================================

	static {
		// Can add an append=true argument to generate appending behavior.
        int nFac = FAC_NAME.length;
		Handler theHandler = null;
		_systemLogger = Logger.getLogger(DEFAULT_APPLICATION_CLASS);
		_facilityLoggers = new Logger[nFac];
        
        if (FAC_LOG_LEVEL_PROPERTY.length != nFac ||
            FAC_LOG_LEVEL_ENV.length != nFac ||
            FAC_LOG_LEVEL_DEFAULT.length != nFac) {
            System.err.println("Log facility consistency check failed");
            System.exit(1);
        }

		// We restrict logging based on our _fac_level, not on the system logger
		_systemLogger.setLevel(Level.ALL);
		for (int i=FAC_DEFAULT; i < nFac; i++) {
			_facilityLoggers[i] = Logger.getLogger(FAC_NAME[i]);
			_facilityLoggers[i].setLevel(Level.ALL);
		}
		//		_systemLogger.info("Initializing CCNX Logging");

		String logdir = System.getProperty(LOG_DIR_PROPERTY);
		if (null == logdir) {
			logdir = System.getenv(LOG_DIR_ENV);
		}

		// Only set up file handler if log directory is set
		if (null != logdir) {
			StringBuffer logFileName = new StringBuffer();
			try {
				// See if log dir exists, if not make it.
				File dir = new File(logdir);
				if (!dir.exists() || !dir.isDirectory()) {
					if (!dir.mkdir()) {
						System.err.println("Cannot open log directory "
								+ logdir);
						throw new IOException("Cannot open log directory "
								+ logdir);
					}
				}
				String sep = System.getProperty("file.separator");

				logFileName.append(logdir + sep + DEFAULT_LOG_FILE);
				Date theDate = new Date();
				SimpleDateFormat df = new SimpleDateFormat("yy-MM-dd-HHmmss");
				logFileName.append(df.format(theDate));
				String pid = SystemConfiguration.getPID();
				if (null != pid) {
					logFileName.append("-" + pid);
				} else {
					logFileName.append("-R" + new Random().nextInt(1000));
				}
				logFileName.append(DEFAULT_LOG_SUFFIX);

				theHandler = new FileHandler(logFileName.toString());
				// Force a standard XML encoding (avoids unusual ones like MacRoman in XML)
				theHandler.setEncoding("UTF-8");
				System.out.println("Writing log records to " + logFileName);

			} catch (IOException e) {
				// Can't open that file
				System.err.println("Cannot open log file: " + logFileName);
				e.printStackTrace();

				theHandler = new ConsoleHandler();
			}
		}
		if (null != theHandler) {
			_systemLogger.addHandler(theHandler);
		}
		// Could just do a console handler if the file won't open.
		// Do that eventually, for debugging put in both.
		// Actually, right now, seem to get a console handler by default.
		// This move is anti-social, but a starting point.	We don't want
		// any handlers to be more restrictive then the level set for
		// our _systemLevel
		Handler[] handlers = Logger.getLogger( "" ).getHandlers();
		for (int index = 0; index < handlers.length; index++) {
			handlers[index].setLevel(Level.ALL);

			// TODO Enabling the following by default seems to cause ccn_repo to
			// hang when run from the command line, at least on Leopard.
			// Not sure why, so make it a special option.
			if (SystemConfiguration.hasLoggingConfigurationProperty(SystemConfiguration.DETAILED_LOGGER)) {
				if (handlers[index] instanceof ConsoleHandler) {
					handlers[index].setFormatter(new DetailedFormatter());
				}
			}
		}

		// Allow override of default log level.
		setLogLevels();
	}

	// deprecated
	public static String getApplicationClass() {
		return DEFAULT_APPLICATION_CLASS;
	}

	public static String getApplicationClass(int facility) {
		return FAC_NAME[facility];
	}

	public static void exitApplication() {
		// Clean up and get out, we've had an unrecovereable error.
		_facilityLoggers[FAC_DEFAULT].severe("Exiting application.");
		System.exit(-1);
	}

	public static void abort() {
		_facilityLoggers[FAC_DEFAULT].warning("Unrecoverable error. Exiting data collection.");
		exitApplication(); // save partial results?
	}

	// These following methods duplicate methods provided by java.util.Logger
	// but add varargs functionality which allows args to only have .toString()
	// called when logging is enabled.
	/**
	 * Logs message with level = info
	 * @see Log#log(Level, String, Object...)
	 */
	public static void info(String msg, Object... params) {
		doLog(FAC_DEFAULT, Level.INFO, msg, params);
	}

	public static void info(int facility, String msg, Object... params) {
		if (FAC_DEFAULT <= facility && facility < _fac_level.length)
			doLog(facility, Level.INFO, msg, params);
	}

	/**
	 * Logs message with level = warning
	 * @see Log#log(Level, String, Object...)
	 */
	public static void warning(String msg, Object... params) {
		doLog(FAC_DEFAULT, Level.WARNING, msg, params);
	}

	public static void warning(int facility, String msg, Object... params) {
		if (FAC_DEFAULT <= facility && facility < _fac_level.length)
			doLog(facility, Level.WARNING, msg, params);
	}

	/**
	 * Logs message with level = severe
	 * @see Log#log(Level, String, Object...)
	 */
	public static void severe(String msg, Object... params) {
		doLog(FAC_DEFAULT, Level.SEVERE, msg, params);
	}

	public static void severe(int facility, String msg, Object... params) {
		if (FAC_DEFAULT <= facility && facility < _fac_level.length)
			doLog(facility, Level.SEVERE, msg, params);
	}

	/**
	 * Logs message with level = fine
	 * @see Log#log(Level, String, Object...)
	 */
	public static void fine(String msg, Object... params) {
		doLog(FAC_DEFAULT, Level.FINE, msg, params);
	}

	public static void fine(int facility, String msg, Object... params) {
		if (FAC_DEFAULT <= facility && facility < _fac_level.length)
			doLog(facility, Level.FINE, msg, params);
	}

	/**
	 * Logs message with level = finer
	 * @see Log#log(Level, String, Object...)
	 */
	public static void finer(String msg, Object... params) {
		doLog(FAC_DEFAULT, Level.FINER, msg, params);
	}

	public static void finer(int facility, String msg, Object... params) {
		if (FAC_DEFAULT <= facility && facility < _fac_level.length)
			doLog(facility, Level.FINER, msg, params);
	}

	/**
	 * Logs message with level = finest
	 * @see Log#log(Level, String, Object...)
	 */
	public static void finest(String msg, Object... params) {
		doLog(FAC_DEFAULT, Level.FINEST, msg, params);
	}

	public static void finest(int facility, String msg, Object... params) {
		if (FAC_DEFAULT <= facility && facility < _fac_level.length)
			doLog(facility, Level.FINEST, msg, params);
	}

	// pass these methods on to the java.util.Logger for convenience
	public static void setLevel(Level l) {
		_fac_level[FAC_DEFAULT] = l;
		_fac_value[FAC_DEFAULT] = l.intValue();
	}

	// pass these methods on to the java.util.Logger for convenience
	public static void setLevel(int facility, Level l) {
		if (FAC_DEFAULT <= facility && facility < _fac_level.length) {
			_fac_level[facility] = l;
			_fac_value[facility] = l.intValue();
//			System.out.println(String.format("Log.setLevel(%d, %s)", facility, l));
		} else if (facility == FAC_ALL) {
			for (int i=FAC_DEFAULT; i < _fac_level.length; i++) {
				_fac_level[i] = l;
				_fac_value[i] = l.intValue();
//				System.out.println(String.format("Log.setLevel(%d, %s)", facility, l));
			}
		}
	}

	public static void setLevels(Level [] levels) {
		if( levels.length != _fac_level.length ) {
			doLog(FAC_DEFAULT, Level.SEVERE, "setLevels array incorrect size");
			return;
		}

		// The 0 element (FAC_ALL) is always null
		for( int i = 1; i < levels.length; i++ )
			setLevel(i, levels[i]);
	}

	/**
	 * Set the default log level that will be in effect unless overridden by
	 * the system property.	 Use of this method allows a program to change the
	 * default logging level while still allowing external override by the user
	 * at runtime.
	 *
	 * This must be called before using setLevel().	 Calling this method will
	 * reset all log levels to the default or to the system property level.
	 * @param l the new default level
	 */
	public static void setDefaultLevel(Level l) {
		for (int i = FAC_DEFAULT; i < FAC_LOG_LEVEL_DEFAULT.length; i++ )
			FAC_LOG_LEVEL_DEFAULT[i] = l;
		setLogLevels();
	}

	public static void setDefaultLevel(int facility, Level l) {
		if (FAC_DEFAULT <= facility && facility < FAC_LOG_LEVEL_DEFAULT.length) {
			FAC_LOG_LEVEL_DEFAULT[facility] = l;
		} else if (facility == FAC_ALL) {
			for (int i = FAC_DEFAULT; i < FAC_LOG_LEVEL_DEFAULT.length; i++ ) {
				FAC_LOG_LEVEL_DEFAULT[i] = l;
			}
		}
		setLogLevels();
	}

	/**
	 * Set the facility log levels based on the defaults and system overrides
	 */
	public static void setLogLevels() {
		String logLevelName;
		Level logLevel;

		// First get the FAC_ALL value, and if set use it to set the default log level for all facilities
		logLevelName = SystemConfiguration.retrievePropertyOrEnvironmentVariable(
				FAC_LOG_LEVEL_PROPERTY[FAC_ALL],
				FAC_LOG_LEVEL_ENV[FAC_ALL],
				null);
		if (logLevelName != null) {
			try {
				logLevel = Level.parse(logLevelName);
				for (int i = FAC_DEFAULT; i < FAC_LOG_LEVEL_DEFAULT.length; i++ ) {
					FAC_LOG_LEVEL_DEFAULT[i] = logLevel;
				}
			} catch (IllegalArgumentException e) {
				doLog(FAC_DEFAULT, Level.SEVERE, String.format("Error parsing property %s=%s",
						FAC_LOG_LEVEL_PROPERTY[FAC_ALL], logLevelName));
				e.printStackTrace();
			}
		}

		// Then get the individual facility's log level from property/environment, or the default
		for (int i = FAC_DEFAULT; i < FAC_LOG_LEVEL_PROPERTY.length; i++ ) {
			logLevelName = SystemConfiguration.retrievePropertyOrEnvironmentVariable(
					FAC_LOG_LEVEL_PROPERTY[i],
					FAC_LOG_LEVEL_ENV[i],
					FAC_LOG_LEVEL_DEFAULT[i].getName());
			try {
				logLevel = Level.parse(logLevelName);
			} catch(IllegalArgumentException e) {
				doLog(FAC_DEFAULT, Level.SEVERE, String.format("Error parsing property %s=%s", FAC_LOG_LEVEL_PROPERTY[i], logLevelName));
				e.printStackTrace();
				logLevel = FAC_LOG_LEVEL_DEFAULT[i];
			}

			if (logLevel.intValue() != FAC_LOG_LEVEL_DEFAULT[i].intValue())
				doLog(FAC_DEFAULT, Level.INFO, String.format("Set log level for facility %s to %s",
						FAC_LOG_LEVEL_PROPERTY[i], logLevel));

			setLevel(i, logLevel);
		}
	}

	/**
	 * Gets the current log level
	 * @return
	 */
	public static Level getLevel() {
		return _fac_level[FAC_DEFAULT];
	}

	/**
	 * Gets the current log level
	 * @return may be null if invalid facility number
	 */
	public static Level getLevel(int facility) {
		if (0 <= facility && facility < _fac_level.length)
			return _fac_level[facility];
		else
			return null;
	}

	/**
	 * Returns a copy of the array of all log levels.  The 0 element (FAC_ALL) will be null.
	 */
	public static Level [] getLevels() {
		Level [] copy = new Level[_fac_level.length];
		for(int i = 0; i < _fac_level.length; i++)
			copy[i] = _fac_level[i];
		return copy;
	}

	/**
	 * Would the given log level write to the log?
	 * @param level
	 * @return true means would write log
	 */
	public static boolean isLoggable(Level level) {
		if (level.intValue() <	_fac_value[FAC_DEFAULT]	 || _fac_value[FAC_DEFAULT] == offValue) {
			return false;
		}
		return true;
	}

	/**
	 * Would the given log level write to the log?
	 * @param level
	 * @return true means would write log
	 */
	public static boolean isLoggable(int facility, Level level) {
		if (FAC_DEFAULT <= facility && facility < _fac_level.length) {
			if (level.intValue() <	_fac_value[facility]  || _fac_value[facility] == offValue) {
				return false;
			}
			return true;
		}
		return false;
	}

	/**
	 * Set flag for enabling/disabling timestamps on all messages
	 */

	public static boolean setTimestamp(boolean enableTimestamp) {
		boolean previous = _timestamp;
		_timestamp = enableTimestamp;
		return previous;
	}

	/**
	 * The main logging wrapper. Allows for variable parameters to the message.
	 * Using the variable parameters here rather then constructing the message
	 * yourself helps reduce CPU load when logging is disabled. (Since the
	 * params do not have their .toString() methods called if the message is not
	 * logged).
	 * @param l Log level.
	 * @param msg Message or format string. Note that to improve performance, only
	 *			the simplest form of of MessageFormat, i.e. {0}, {1}, {2}... is supported
	 * @see java.text.MessageFormat
	 * @param params
	 */
	public static void log(Level l, String msg, Object... params) {
		// we must call doLog() to ensure caller is in right place on stack
		doLog(FAC_DEFAULT, l, msg, params);
	}

	public static void log(int facility, Level l, String msg, Object... params) {
		// we must call doLog() to ensure caller is in right place on stack
		if (0 <= facility && facility < _fac_level.length)
			doLog(facility, l, msg, params);
	}

	protected static void doLog(int facility, Level l, String msg, Object... params) {
		if (!isLoggable(facility, l))
			return;

		// Do this at the top of doLog to get timestamp closest to event
		// Use %.6f to get format same as ccnd, even though we only have
		// msec precision.
		if( (facility == FAC_TIMING)  || _timestamp) {
			long now = System.currentTimeMillis();
			double d = now / 1000.0;
			msg = String.format("%.6f %s", d, msg);
		}

		StackTraceElement ste = Thread.currentThread().getStackTrace()[3];
		Class<?> c;
		try {
			c = Class.forName(ste.getClassName());
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}

		// Some loggers e.g. the XML logger do not substitute parameters correctly
		// Therefore we do our own parameter substitution here and do not rely
		// on the system logger's ability to do it.
		int i = 0;
		for(Object o : params) {
			if (o == null) {
				o = "(null)";
			}
			int index = msg.indexOf("{" + i++ + "}");
			if (index >= 0) {
				StringBuffer sb = new StringBuffer(msg.substring(0, index));
				sb.append(o);
				sb.append(msg.substring(index + 3));
				msg = sb.toString();
			}
		}

		_facilityLoggers[facility].logp(l, c.getCanonicalName(), ste.getMethodName(), msg);
	}

	public static void flush() {
		Handler [] handlers = _facilityLoggers[FAC_DEFAULT].getHandlers();
		for (int i=0; i < handlers.length; ++i) {
			handlers[i].flush();
		}
	}

	public static void warningStackTrace(Throwable t) {
		logStackTrace(Level.WARNING, t);
	}

	public static void warningStackTrace(int facility, Throwable t) {
		logStackTrace(facility, Level.WARNING, t);
	}

	public static void severeStackTrace(int facility, Throwable t) {
		logStackTrace(facility, Level.WARNING, t);
	}

	public static void infoStackTrace(Throwable t) {
		logStackTrace(Level.INFO, t);
	}

	public static void infoStackTrace(int facility, Throwable t) {
		logStackTrace(facility, Level.INFO, t);
	}

	public static void logStackTrace(Level level, Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		_facilityLoggers[FAC_DEFAULT].log(level, sw.toString());
	}

	public static void logStackTrace(int facility, Level level, Throwable t) {
		if (!isLoggable(facility, level))
			return;

		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		_facilityLoggers[facility].log(level, sw.toString());
	}

	public static void logException(String message, Exception e) {
		_facilityLoggers[FAC_DEFAULT].warning(message);
		Log.warningStackTrace(e);
	}

	public static void logException(int facility, Level level, String message, Exception e) {
		if (!isLoggable(facility, level))
			return;

		_facilityLoggers[facility].log(level, message);
		logStackTrace(facility, level, e);
	}
}
