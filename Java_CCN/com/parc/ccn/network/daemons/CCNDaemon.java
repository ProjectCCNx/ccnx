package com.parc.ccn.network.daemons;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;

import org.ccnx.ccn.Library;

import com.parc.ccn.network.CCNNetworkManager;

/**
 * Start a ccnd that we can control for testing
 * @author rasmusse
 *
 */

public class CCNDaemon extends Daemon {
	
	public static final String PROP_CCND_DEBUG = "ccnd.debug";
	
	private static final String DEFAULT_CCND_COMMAND_STRING = "../ccnd/agent/ccnd";
	protected String _command = DEFAULT_CCND_COMMAND_STRING;
	protected Process _ccndProcess = null;
	protected CCNDaemon _daemon = null;
	
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
		
		public void initialize() {
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
				Library.logStackTrace(Level.WARNING, e);
				e.printStackTrace();
			}
			String outputFile = System.getProperty(PROP_DAEMON_OUTPUT);
			if (outputFile != null) {
				try {
					new DaemonOutput(_ccndProcess.getInputStream(), outputFile, true);
				} catch (FileNotFoundException e) {
					Library.logStackTrace(Level.WARNING, e);
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
			// Without parsing args, we don't know which repo impl we will get, so show the default 
			// impl usage and allow for differences 
			String msg = "usage: " + this.getClass().getName() + "[-start | -stop | -interactive | -signal <signal>] [-command <command>]";
			System.out.println(msg);
			Library.logger().severe(msg);
		} catch (Exception e) {
			e.printStackTrace();
			Library.logStackTrace(Level.SEVERE, e);
		}
		System.exit(1);
	}

	public static void main(String[] args) {
		CCNDaemon daemon = null;
		try {
			daemon = new CCNDaemon();
			//if (args[0].equals("-interactive"))
				//daemon.setInteractive();
			runDaemon(daemon, args);
		} catch (Exception e) {
			System.err.println("Error attempting to start daemon.");
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
		}
	}
}
