package com.parc.ccn;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
	public static final String DEFAULT_LOG_FILE = "ccn.log";
	public static final Level DEFAULT_LOG_LEVEL = Level.FINE;
	
	static Logger _systemLogger = null;
	
	static {
		// Can add an append=true argument to generate appending behavior.
		Handler theHandler = null;
		try {
			String sep = System.getProperty("file.separator");
			theHandler = new FileHandler(DEFAULT_LOG_DIR + sep + DEFAULT_LOG_FILE);
		} catch (IOException e) {
			// Can't open that file
			_systemLogger.addHandler(new ConsoleHandler());
		}
		_systemLogger = Logger.getLogger(DEFAULT_APPLICATION_CLASS);
		if (null != theHandler) {
			_systemLogger.addHandler(theHandler);
		}
		// Could just do a console handler if the file won't open.
		// Do that eventually, for debugging put in both.
		// Actually, right now, seem to get a console handler by default.
		 
		Handler[] handlers = Logger.getLogger( "" ).getHandlers();
		for ( int index = 0; index < handlers.length; index++ ) {
			handlers[index].setLevel( DEFAULT_LOG_LEVEL );
		}

		// We also have to set our logger to log finer-grained
		// messages
		_systemLogger.setLevel(DEFAULT_LOG_LEVEL);
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
}
