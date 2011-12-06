/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.support;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;

import org.ccnx.ccn.impl.CCNNetworkManager;


/**
 * Main ccnd command line daemon.
 * Allows start & stop of ccnd, as well as interactive runs.
 * 
 * TODO This is not actually yet used in any tests and therefore is itself not well tested
 */
public class CCNDaemon extends Daemon {
	public static final String PROP_CCND_COMMAND = "ccnd.command";
	public static final String PROP_CCND_DEBUG = "ccnd.debug";
	
	private static final String DEFAULT_CCND_COMMAND_STRING = "../ccnd/agent/ccnd";
	protected String _command = DEFAULT_CCND_COMMAND_STRING;
	protected Process _ccndProcess = null;
	protected CCNDaemon _daemon = null;
	
	/**
	 * Stop ccnd on exit from daemon
	 */
	protected class CCNDShutdownHook extends Thread {
		public void run() {
			if (_ccndProcess != null)
				_ccndProcess.destroy();
		}
	}
	
	protected class CCNDWorkerThread extends Daemon.WorkerThread {

		private static final long serialVersionUID = -6093561895394961537L;
		protected boolean _shutdown = false;
						
		protected CCNDWorkerThread(String daemonName) {
			super(daemonName);
		}
		
		public void work() {
			synchronized(this) {
				boolean interrupted = false;
				do {
					try {
						interrupted = false;
						wait();
					} catch (InterruptedException e) {
						interrupted = true;
					}		
				} while (interrupted && !_shutdown);
			}
		}
		
		/**
		 * Start ccnd but set up a shutdown hook to allow it to stop
		 */
		public void initialize() {
			String commandVal = System.getProperty(PROP_CCND_COMMAND);
			if (commandVal != null) {
				_command = commandVal;
			}
			Runtime.getRuntime().addShutdownHook(new CCNDShutdownHook());
			ProcessBuilder pb = new ProcessBuilder(_command);		
			Map<String, String> env = pb.environment();
			pb.redirectErrorStream(true);
			String portval = System.getProperty(CCNNetworkManager.PROP_AGENT_PORT);
			if (portval != null) {
				env.put("CCN_LOCAL_PORT", portval);
			}
			String debugVal = System.getProperty(PROP_CCND_DEBUG);
			if (debugVal != null) {
				env.put("CCND_DEBUG", debugVal);
			}
			try {
				_ccndProcess = pb.start();
			} catch (IOException e) {
				Log.logStackTrace(Level.WARNING, e);
				e.printStackTrace();
			}
			String outputFile = System.getProperty(PROP_DAEMON_OUTPUT);
			if (outputFile != null) {
				try {
					new DaemonOutput(_ccndProcess.getInputStream(), new FileOutputStream(outputFile, true));
				} catch (FileNotFoundException e) {
					Log.logStackTrace(Level.WARNING, e);
					e.printStackTrace();
				}
			}
		}
		
		
		public void finish() {
			synchronized (this) {
				_shutdown = true;
				notify();
			}
		}
		
		public boolean signal(String name) {
			return false;
		}
		
		public Object status(String type) {
			return "running";
		}
	}
	
	public CCNDaemon() {
		super();
		// This is a daemon: it should not do anything in the
		// constructor but everything in the initialize() method
		// which will be run in the process that will finally 
		// execute as the daemon, rather than in the launching
		// and stopping processes also.
		_daemonName = "ccnd";
		_daemon = this;
	}
	
	protected void initialize(String[] args, Daemon daemon) {
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-command")) {
				_command = args[i + 1];
			}
		}
	}
	
	protected WorkerThread createWorkerThread() {
		return new CCNDWorkerThread(daemonName());
	}
	
	protected void usage() {
		try {
			String msg = "usage: " + this.getClass().getName() + "[-start | -stop | -interactive | -signal <signal>] [-command <command>]";
			System.out.println(msg);
			Log.severe(msg);
		} catch (Exception e) {
			e.printStackTrace();
			Log.logStackTrace(Level.SEVERE, e);
		}
		System.exit(1);
	}

	public static void main(String[] args) {
		CCNDaemon daemon = null;
		try {
			daemon = new CCNDaemon();
			runDaemon(daemon, args);
		} catch (Exception e) {
			System.err.println("Error attempting to start daemon.");
			Log.warning("Error attempting to start daemon.");
			Log.warningStackTrace(e);
		}
	}
}
