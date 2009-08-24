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

public class Library {

	/**
	 * Allow override on command line or from configuration file.
	 */
	public static final String DEFAULT_APPLICATION_CLASS =
		"com.parc.ccn.Library";

	public static final String DEFAULT_LOG_DIR = "log";
	public static final String DEFAULT_LOG_FILE = "ccn_";
	public static final String DEFAULT_LOG_SUFFIX = ".log";
	public static final Level DEFAULT_LOG_LEVEL = Level.INFO;
	
	/**
	 * Property to set log level.
	 */
	public static final String DEFAULT_LOG_LEVEL_PROPERTY = "com.parc.ccn.LogLevel";
	
	static Logger _systemLogger = null;
	
	static {
		// Can add an append=true argument to generate appending behavior.
		Handler theHandler = null;
		_systemLogger = Logger.getLogger(DEFAULT_APPLICATION_CLASS);

		StringBuffer logFileName = new StringBuffer();
		
		try {
			// See if log dir exists, if not make it.
			File dir = new File(DEFAULT_LOG_DIR);
			if (!dir.exists() || !dir.isDirectory()) {
				if (!dir.mkdir()) {
					System.err.println("Cannot open log directory " + DEFAULT_LOG_DIR);
					throw new IOException("Cannot open log directory " + DEFAULT_LOG_DIR);
				}
			}
			String sep = System.getProperty("file.separator");
			
			logFileName.append(DEFAULT_LOG_DIR + sep + DEFAULT_LOG_FILE);
			Date theDate = new Date();
			SimpleDateFormat df = new SimpleDateFormat("yy-MM-dd-HHmmss");
			logFileName.append(df.format(theDate));
			logFileName.append("-" + new Random().nextInt(1000));
			logFileName.append(DEFAULT_LOG_SUFFIX);
			
			theHandler = new FileHandler(logFileName.toString());

		} catch (IOException e) {
			// Can't open that file
			System.err.println("Cannot open log file: " + logFileName);
			e.printStackTrace();
			
			theHandler = new ConsoleHandler();
		}
		if (null != theHandler) {
			_systemLogger.addHandler(theHandler);
		}
		// Could just do a console handler if the file won't open.
		// Do that eventually, for debugging put in both.
		// Actually, right now, seem to get a console handler by default.
		// This move is anti-social, but a starting point.  We don't want 
		// any handlers to be more restrictive then the level set for 
		// our _systemLevel
		Handler[] handlers = Logger.getLogger( "" ).getHandlers();
		for ( int index = 0; index < handlers.length; index++ ) {
			handlers[index].setLevel( Level.ALL );
		}
		
		// Allow override of default log level.
		String logLevelName = System.getProperty(DEFAULT_LOG_LEVEL_PROPERTY);
		
		Level logLevel = DEFAULT_LOG_LEVEL;
		
		if (null != logLevelName) {
			try {
				logLevel = Level.parse(logLevelName);
			} catch (IllegalArgumentException e) {
				logLevel = DEFAULT_LOG_LEVEL;
			}
		}

		// We also have to set our logger to log finer-grained
		// messages
		_systemLogger.setLevel(logLevel);
	}

	public static String getApplicationClass() {
		return DEFAULT_APPLICATION_CLASS;
	}
	
	public static void exitApplication() {
		// Clean up and get out, we've had an unrecovereable error.
		logger().severe("Exiting application.");
		System.exit(-1);
	}
	
	public static void abort() {
		Library.logger().warning("Unrecoverable error. Exiting data collection.");
		exitApplication(); // save partial results?
	}
	
	public static Logger logger() { return _systemLogger; }
	
	public static void warningStackTrace(Throwable t) {
		logStackTrace(Level.WARNING, t);
	}
	
	public static void infoStackTrace(Throwable t) {
		logStackTrace(Level.INFO, t);
	}
	
	public static void logStackTrace(Level level, Throwable t) {
		 StringWriter sw = new StringWriter();
	     t.printStackTrace(new PrintWriter(sw));
	     logger().log(level, sw.toString());
	}
	
	public static void logException(String message, 
			Exception e) {
		Library.logger().warning(message);
		Library.warningStackTrace(e);
	}
}
