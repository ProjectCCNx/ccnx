/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.SystemConfiguration.DEBUGGING_FLAGS;
import org.ccnx.ccn.impl.CCNNetworkManager;


/**
 * Implements command line daemon functionality. In the normal case a daemon is started up and then runs in
 * the background after the starting process exits. An RMI file is created to allow outside processes to
 * communicate with the daemon to "signal" it or stop it.<p>
 * 
 * Daemons have several modes as seen below:
 * 
 * <pre>
 * MODE_START - used to start a daemon which will be run in the background
 * MODE_STOP  - used to stop a daemon currently running in the background
 * MODE_INTERACTIVE - used to run a daemon interactively rather than in the background
 * MODE_DAEMON - a daemon started up by a "starter" is started in MODE_DAEMON.
 * MODE_SIGNAL - used to signal a daemon from outside to get the daemon to perform implementation defined
 * 			     services
 * </pre>
 */
public class Daemon {

	protected enum Mode {MODE_UNKNOWN, MODE_START, MODE_STOP, MODE_INTERACTIVE, MODE_DAEMON, MODE_SIGNAL};

	protected static Daemon _daemon;
	protected String _daemonName = null;
	protected static DaemonListenerClass _daemonListener = null;
	protected boolean _interactive = false;
	protected String _pid;
	
	public static final String PROP_DAEMON_MEMORY = "ccn.daemon.memory";
	public static final String PROP_DAEMON_DEBUG_PORT = "ccn.daemon.debug";
	public static final String PROP_DAEMON_OUTPUT = "ccn.daemon.output";
	public static final String PROP_DAEMON_PROFILE = "ccn.daemon.profile";
	public static final String PROP_DAEMON_DEBUG_SUSPEND = "ccn.daemon.debug.suspend";
	public static final String PROP_DAEMON_DEBUG_NOSHARE = "ccn.daemon.debug.noshare";
	
	public static final String DEFAULT_OUTPUT_STREAM = "/dev/null";
	
	/**
	 * Interface describing the RMI server object sitting inside
	 * the daemon 
	 */
	public interface DaemonListener extends Remote {
		public String startLoop() throws RemoteException; // returns pid
		public void shutDown() throws RemoteException;
		public boolean signal(String name) throws RemoteException;
		public Object status(String name) throws RemoteException;
	}
	
	public class StopTimer extends TimerTask {
		private String _daemonName;
		private String _pid;
		
		private StopTimer(String daemonName, String pid) {
			_daemonName = daemonName;
			_pid = pid;
		}

		@Override
		public void run() {
			System.out.println("Attempt to contact daemon " + _daemonName + " timed out");
			Log.info("Attempt to contact daemon " + _daemonName + " timed out");
			cleanupDaemon(_daemonName, _pid);
			System.exit(1);
		}
		
	}
	
	/**
	 * Stop ccnd on exit from daemon
	 */
	protected class ShutdownHook extends Thread {
		public void run() {
			try {
				_daemon.rmRMIFile();
			} catch (IOException e) {}
		}
	}
	
	/**
	 * The thread that runs inside the daemon, doing work
	 */
	protected static class WorkerThread extends Thread implements Serializable {

		private static final long serialVersionUID = -4969812722104756329L;
		boolean _keepGoing;
		String _daemonName;

		protected WorkerThread(String daemonName) {
			_daemonName = daemonName;
		}

		public void run() {
			_keepGoing = true;		

			System.out.println("Initializing daemon thread " + new Date().toString() +".");
			Log.info("Initializing daemon thread " + new Date().toString() +".");

			initialize();
			
			System.out.println("Daemon thread started " + new Date().toString() +".");
			Log.info("Daemon thread started " + new Date().toString() +".");

			do {

				try {
					work();
				} catch (Exception e) {
					Log.warning("Error in daemon thread: " + e.getMessage());
					Log.warningStackTrace(e);
				}

				if (_keepGoing) {
					try {
						Thread.sleep(10000);
					} catch (InterruptedException e) {}
				}
			} while (_keepGoing);

			// ok, we were asked to shut down
			System.out.println("Shutting down the daemon.");	
			Log.info("Shutting down the daemon.");	

			try {
				UnicastRemoteObject.unexportObject(_daemonListener, true);
			} catch (NoSuchObjectException e) {
			}

			getRMIFile(_daemonName, SystemConfiguration.getPID()).delete();		
			System.exit(0);
		}			

		public void shutDown() {
			_keepGoing = false;
			finish();
			interrupt();
		}
		
		/**
		 * Specialized by subclasses, called by worker thread.
		 *
		 */
		public void work() {
			Log.info("Should not be here, in WorkerThread.work().");			
		}
		public void initialize() {
			Log.info("Should not be here, in WorkerThread.initialize().");
		}
		public void finish() {
			Log.info("Should not be here, in WorkerThread.finish().");
		}
		public boolean signal(String name) {
			Log.info("Should not be here, in WorkerThread.signal().");
			return false;			
		}
		public Object status(String name) {
			return null;		// We don't require implementers to implement this
		}

	}

	protected static class DaemonListenerClass extends UnicastRemoteObject implements DaemonListener {

		private static final long serialVersionUID = -9217344397211709762L;
		protected WorkerThread _daemonThread;

		public DaemonListenerClass(WorkerThread daemonThread) throws RemoteException {
			_daemonThread = daemonThread;
		}

		public void shutDown() throws RemoteException {
			_daemonThread.shutDown();			
		}

		public String startLoop() throws RemoteException {

			System.out.println("Starting the daemon loop.");
			Log.info("Starting the daemon loop.");

			try {
				_daemonThread.start();

				String pid = SystemConfiguration.getPID();
				if (null != SystemConfiguration.getPID()) {
					// PID is available on this platform and we have the startLoop() 
					// invocation so we can rename our RMI file and send PID back to controller.
					renameRMIFile(_daemonThread._daemonName, pid);
				}
				return pid;

			} catch(Exception e) {
				throw new RemoteException(e.getMessage(), e);
			}
		}

		public boolean signal(String name) throws RemoteException {
			Log.info("Signal " + name);
			try {
				return _daemonThread.signal(name);
			} catch (Exception e) {
				throw new RemoteException(e.getMessage(), e);
			}
		}

		public Object status(String name) throws RemoteException {
			Log.info("Status " + name);
			try {
				return _daemonThread.status(name);
			} catch (Exception e) {
				throw new RemoteException(e.getMessage(), e);
			}
		}
	}
	
	public Daemon() {_daemonName = "namelessDaemon";}
	
	public String daemonName() { return _daemonName; }
	
	public void setPid(String pid) {
		_pid = pid;
	}
	
	public String getPid() {
		return _pid;
	}
	
	/**
	 * Overridden by subclasses.
	 *
	 */
	protected void usage() {
		try {
			System.out.println("usage: " + this.getClass().getName() + " -start | -stop <pid> | -interactive | -signal <name> <pid>");
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);
	}
	
	/**
	 * Overridden by subclasses
	 */
	protected void initialize(String[] args, Daemon daemon) {
		System.out.println("Unknown option " + args[1]);
		daemon.usage();
	}

	/**
	 * overridden by subclasses to make right type of thread.
	 * @return
	 */
	protected WorkerThread createWorkerThread() {
		return new WorkerThread(daemonName());
	}
	
	protected static void setupRemoteAccess(Daemon daemon, WorkerThread wt) throws IOException {
		if (wt == null)
			wt = daemon.createWorkerThread();
		_daemonListener = new DaemonListenerClass(wt);

		Remote stub = RemoteObject.toStub(_daemonListener);		

		File tempFile = getRMITempFile(daemon.daemonName());

		ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(tempFile));

		out.writeObject(stub);
		out.flush();
		out.close();

		// always use name without PID at first, will rename
		// when startloop() message comes along.  This allows
		// the controlling starter process to communicate
		// without needing the pid of the child, though it
		// does limit the spawn rate
		// this is atomic
		tempFile.renameTo(getRMIFile(daemon.daemonName(), null));
		
		Runtime.getRuntime().addShutdownHook(daemon.new ShutdownHook());
	}

	private static void startDaemon(String daemonName, String daemonClass, String args[]) throws IOException, ClassNotFoundException {

		String mypid = SystemConfiguration.getPID();
		if (null == mypid) {
			// PID unavailable on this platform so we are restricted
			// to a single instance per daemon name
			if (getRMIFile(daemonName, null).exists()) {
				System.out.println("Daemon already running. Use '" + daemonName + " -stop' to kill the daemon.");
				return;
			}
		} // else it doesn't matter if one is running we may start another
		
		ArrayList<String> argList = new ArrayList<String>();

		argList.add("java");
		String classPath = System.getProperty("java.class.path");
		String [] directories = null;
		String sep = null;
		if (classPath.contains(":")) {
			sep = ":";
			directories = classPath.split(":");
		} else if (classPath.contains(";")) {
			sep = ";"; // do we need to escape?
			directories = classPath.split(";");
		}
		StringBuffer cp = new StringBuffer(
							((null != directories) &&
							  (directories.length > 0)) ?
									  directories[0] : "");
		if (null != directories) {
			for (int i=1; i < directories.length; ++i) {
				cp.append(sep);
				cp.append(directories[i]);
			}
		}
		
	    /**
	     * Add properties
	     * TODO - we might want to add them all but for now these are
	     * the critical ones
	     */
		String portval = System.getProperty(CCNNetworkManager.PROP_AGENT_PORT);
		if (portval != null) {
			argList.add("-D" + CCNNetworkManager.PROP_AGENT_PORT + "=" + portval);
		}
		String memval = System.getProperty(PROP_DAEMON_MEMORY);
		if (memval != null)
			argList.add("-Xmx" + memval);
		String debugFlagVal = System.getProperty(SystemConfiguration.DEBUG_FLAG_PROPERTY);
		if (debugFlagVal != null)
			argList.add("-D" + SystemConfiguration.DEBUG_FLAG_PROPERTY + "=" + debugFlagVal);
		String debugDirVal = System.getProperty(SystemConfiguration.DEBUG_DATA_DIRECTORY_PROPERTY);
		if (debugDirVal != null) 
			argList.add("-D" + SystemConfiguration.DEBUG_DATA_DIRECTORY_PROPERTY + "=" + debugDirVal);
		
		String suspend = System.getProperty(PROP_DAEMON_DEBUG_SUSPEND);
		String doSuspend = suspend == null ? "n" : "y";
		String debugPort = System.getProperty(PROP_DAEMON_DEBUG_PORT);
		if (debugPort != null) {
			argList.add("-Xrunjdwp:transport=dt_socket,address=" + debugPort + ",server=y,suspend=" + doSuspend);
		} else if (doSuspend.equals("y"))
			Log.info("Suspend requested without debug attach");
		
		String unshared = System.getProperty(PROP_DAEMON_DEBUG_NOSHARE);
		if (null != unshared)
			argList.add("-Xshare:off");
		
		String profileInfo = System.getProperty(PROP_DAEMON_PROFILE);
		if (profileInfo != null) {
			argList.add(profileInfo);
		}
		
		argList.add("-cp");
		argList.add(cp.toString());

		argList.add(daemonClass);
		argList.add("-daemon");
		for (int i=1; i < args.length; ++i) {
			argList.add(args[i]);
		}

		String cmd = "";
		for (String arg : argList) {
			cmd += arg +  " ";
		}
		
		if (SystemConfiguration.checkDebugFlag(DEBUGGING_FLAGS.DUMP_DAEMONCMD)) {
			FileOutputStream fos = new FileOutputStream("daemon_cmd.txt");
			fos.write(cmd.getBytes());
			fos.flush();
			fos.close();
		}
		Log.info("Starting daemon with command line: " + cmd);
		
		ProcessBuilder pb = new ProcessBuilder(argList);
		pb.redirectErrorStream(true);
		Process child = pb.start();
		
		String outputFile = System.getProperty(PROP_DAEMON_OUTPUT);
		if (outputFile != null) {
			new DaemonOutput(child.getInputStream(), new FileOutputStream(outputFile, false));
		} else {
			new DaemonOutput(child.getInputStream(), new FileOutputStream(DEFAULT_OUTPUT_STREAM));
		}
		
		// Initial RMI file never named with PID to permit
		// us to read it without knowing PID.  After 
		// daemon operation is started below the file 
		// will be renamed with PID if possible
		while (!getRMIFile(daemonName, null).exists()) {
			try {
				Thread.sleep(1000);
				
				// this should throw an exception
				try {
					InputStream childMsgs = child.getErrorStream();
					int exitValue = child.exitValue();
					// if we get here, the child has exited
					Log.warning("Could not launch daemon " + daemonName + ". Daemon exit value is " + exitValue + ".");
					System.err.println("Could not launch daemon " + daemonName + ". Daemon exit value is " + exitValue + ".");
					byte[] childMsgBytes = new byte[childMsgs.available()];
					childMsgs.read(childMsgBytes);;
					String childOutput = new String(childMsgBytes);
					childMsgs = child.getInputStream();
					childMsgBytes = new byte[childMsgs.available()];
					childMsgs.read(childMsgBytes);
					childOutput += new String(childMsgBytes);
					System.err.println("Messages from the child were: \"" + childOutput + "\"");
					return;
				} catch (IllegalThreadStateException e) {
				}

			} catch (InterruptedException e) {
			}
		}

		ObjectInputStream in = new ObjectInputStream(new FileInputStream(getRMIFile(daemonName, null)));
		DaemonListener l = (DaemonListener)in.readObject();

		String childpid = null;
		childpid = l.startLoop();
		System.out.println("Started daemon " + daemonName + "." + (null == childpid ? "" : " PID " + childpid));
		Log.info("Started daemon " + daemonName + "." + (null == childpid ? "" : " PID " + childpid));
		
		/*
		 * To log output at this level we have to keep running until the daemon exits
		 */
		if (outputFile != null) {
			boolean running = true;
			while (running) {
				try {
					Thread.sleep(1000);
					child.exitValue();
					running = false;
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IllegalThreadStateException e) {}
			}
		}
	}

	protected static void stopDaemon(Daemon daemon, String pid) throws FileNotFoundException, IOException, ClassNotFoundException {

		String daemonName = daemon.daemonName();
		if (!getRMIFile(daemonName, pid).exists()) {
			System.out.println("Daemon " + daemonName + " does not appear to be running.");
			Log.info("Daemon " + daemonName + " does not appear to be running.");
			return;
		}
		
		Timer stopTimer = new Timer(false);
		stopTimer.schedule(daemon.new StopTimer(daemonName, pid), SystemConfiguration.SYSTEM_STOP_TIMEOUT);

		ObjectInputStream in = new ObjectInputStream(new FileInputStream(getRMIFile(daemonName, pid)));

		DaemonListener l = (DaemonListener)in.readObject();		

		in.close();

		try {
			l.shutDown();
			System.out.println("Daemon " + daemonName + " is shut down.");
			Log.info("Daemon " + daemonName + " is shut down.");
		} catch(RemoteException e) {
			cleanupDaemon(daemonName, pid);
		}
	}
	
	protected static void cleanupDaemon(String daemonName, String pid) {
		// looks like the RMI file is still here, but the daemon is gone. let's delete the file, then,
		System.out.println("Daemon " + daemonName + " seems to have died some other way, cleaning up state...");
		Log.info("Daemon " + daemonName + " seems to have died some other way, cleaning up state...");
		getRMIFile(daemonName, pid).delete();
	}

	protected static void signalDaemon(String daemonName, String sigName, String pid) throws FileNotFoundException, IOException, ClassNotFoundException {
		if (!getRMIFile(daemonName, pid).exists()) {
			System.out.println("Daemon " + daemonName + " does not appear to be running.");
			Log.info("Daemon " + daemonName + " does not appear to be running.");
			return;
		}

		ObjectInputStream in = new ObjectInputStream(new FileInputStream(getRMIFile(daemonName, pid)));

		DaemonListener l = (DaemonListener)in.readObject();		

		in.close();

		try {
			if (l.signal(sigName)) {
				System.out.println("Signal " + sigName + " delivered.");
				Log.info("Signal " + sigName + " delivered.");
			} else {
				System.out.println("Signal " + sigName + " not delivered: unrecognized or signal failed");
				Log.info("Daemon " + daemonName + " not delivered: unrecognized or signal failed.");
			}
		} catch(RemoteException e) {
			// looks like the RMI file is still here, but the daemon is gone.  We won't clean up on signal.
			System.out.println("Daemon " + daemonName + " seems to have died somehow.");
			Log.info("Daemon " + daemonName + " seems to have died somehow.");
		}
	}

	protected static File getRMIFile(String daemonName, String pid) {
		String name = ".rmi-server-" + daemonName + (null == pid ? "" : "-" + pid) + ".obj";
		return new File(System.getProperty("user.home"), name);
	}
	
	/**
	 * Rename RMI file to add PID if available, otherwise do nothing.
	 * @param daemonName  the name of this daemon
	 * @param pid  the PID to add to the RMI file name or null if unavailable
	 */
	protected static void renameRMIFile(String daemonName, String pid) {
		if (null != pid) {
			getRMIFile(daemonName, null).renameTo(getRMIFile(daemonName, pid));
		}
	}
	
	protected static File getRMITempFile(String daemonName) throws IOException {
		String prefix = ".rmi-server-" + daemonName;

		return File.createTempFile(prefix, null, new File(System.getProperty("user.home")));
	}
	
	protected void rmRMIFile() throws IOException {
		getRMIFile(_daemonName, SystemConfiguration.getPID()).delete();
	}

	protected static void runDaemon(Daemon daemon, String args[]) throws IOException {
		
		_daemon = daemon;
		Mode mode = Mode.MODE_UNKNOWN;
		String sigName = null;
		String targetPID = null;

		// Argument parsing ONLY here: don't do any execution yet
		if (0 == args.length) {
			mode = Mode.MODE_INTERACTIVE;
		} else if (args[0].equals("-start")) {
			mode = Mode.MODE_START;
		} else if (args[0].equals("-stop")) {
			mode = Mode.MODE_STOP;
			if (args.length < 2) {
				daemon.usage();
			}
			targetPID = args[1];
		} else if (args[0].equals("-daemon")) {
			mode = Mode.MODE_DAEMON;
		} else if (args[0].equals("-interactive")) {
			mode = Mode.MODE_INTERACTIVE;
		} else if (args[0].equals("-signal")) {
			mode = Mode.MODE_SIGNAL;
			if (args.length < 3) {
				daemon.usage();
			}
			sigName = args[1];
			targetPID = args[2];
		} 
		if ("0".equals(targetPID)) {
			// This is request to apply to the single instance on platforms where
			// PIDs are not available
			targetPID = null;
		}

		// Now proceed based on mode, catching all exceptions
		try {
			switch (mode) {
			  case MODE_INTERACTIVE:
				String pid = SystemConfiguration.getPID();
				daemon.setPid(pid);
				daemon.initialize(args, daemon);
				Log.info("Running " + daemon.daemonName() + " in the foreground." + (null == pid ? "" : " PID " + pid));
				WorkerThread wt = daemon.createWorkerThread();
				// Set up remote access when interactive also to enable signals
				setupRemoteAccess(daemon, wt);
				// In Interactive mode there is no startLoop() invocation to separate daemon
				// process, so just rename our RMI file immediately
				renameRMIFile(daemon.daemonName(), pid);
				wt.start();
				wt.join();
				System.exit(0);
			  case MODE_START:
				// Don't initialize since this process will not become
				// the daemon: this will start a new process
				startDaemon(daemon.daemonName(), daemon.getClass().getName(), args);
				System.exit(0);
			  case MODE_STOP:
				// Don't initialize since this process will never be the daemon
				// This will signal daemon to terminate
				stopDaemon(daemon, targetPID);
				System.exit(0);
			  case MODE_DAEMON:
				daemon.initialize(args, daemon);
				Log.info(daemon.daemonName() + " started in background " + new Date());
				// This will create daemon thread and RMI server to receive startLoop command 
				// from controller process that launched this process
				setupRemoteAccess(daemon, null);
				break;
			  case MODE_SIGNAL:
				  // This will signal daemon to do something specifically named
				  assert(null != sigName);
				  signalDaemon(daemon.daemonName(), sigName, targetPID);
				  break;
			  default:
				daemon.usage();
			}
			
		} catch (Exception e) {
			Log.warning(e.getClass().getName() + " in daemon startup: " + e.getMessage());
			Log.warningStackTrace(e);
			if (mode == Mode.MODE_DAEMON) {
				// Make sure to terminate if there is an uncaught
				// exception trying to run as daemon so that 
				// it is obvious that something failed because
				// process will have gone away despite whatever
				// threads may have got started.
				System.exit(1);
			}
		}							
		Log.info("Daemon runner finished.");
	}
	
	public void setInteractive() {
		_interactive = true;
	}

	/**
	 * Main entry point for command line invocation.
	 * @param args Arguments passed in from command line.
	 */
	public static void main(String[] args) {
		
		// Need to override in each subclass to make proper class.
		Daemon daemon = null;
		try {
			daemon = new Daemon();
			runDaemon(daemon, args);
		} catch (Exception e) {
			Log.warning("Error attempting to start daemon.");
			Log.warningStackTrace(e);
			System.err.println("Error attempting to start daemon.");
		}
	}


	/**
	 * Utility classes if your daemon requires a password.
	 * @param target
	 * @return
	 */
	protected static String getPassword(String target) {

		System.out.print("Password for " + target);

		String password = readOnePassword(); // in.readLine();

		return password;
	}


	protected static class Eraser extends Thread {
		PrintStream out;
		boolean finish = false;
		public Eraser(PrintStream out) {
			this.out = out;
		}
		public void run() {
			while (!finish) {
				out.print("\010 ");
				try {
					sleep(10);
				} catch (InterruptedException inte) {
					finish = true;
				}
			}
		}
	}
	
	public Object getStatus(String daemonName, String type) throws FileNotFoundException, IOException, ClassNotFoundException {
		if (!getRMIFile(daemonName, _pid).exists()) {
			System.out.println("Daemon " + daemonName + " does not appear to be running.");
			Log.info("Daemon " + daemonName + " does not appear to be running.");
			return null;
		}

		ObjectInputStream in = new ObjectInputStream(new FileInputStream(getRMIFile(daemonName, _pid)));

		DaemonListener l = (DaemonListener)in.readObject();		

		in.close();
		
		return l.status(type);
	}

	/**
	 * reads one password.
	 */
	public static String readOnePassword() {
		// System.out.print("\033[00;40;30m");

		Eraser eraser = new Eraser(System.out);
		eraser.start();

		BufferedReader in =
			new BufferedReader(new InputStreamReader(System.in));
		String password = "";

		try {
			password = in.readLine();
		} catch (IOException ioe) {
		}

		eraser.interrupt();
		try {
			Thread.sleep(100);
		} catch (InterruptedException inte) {
		}

		// System.out.print("\033[0m");

		return password;
	}

	/**
	 * reads a password, and then prompts to re-enter
	 * password. makes sure both entered passwords are the same
	 */
	public static String readNewPassword() {

		boolean newtry = false;
		boolean done = false;
		String password1, password2;

		do {		
			if(newtry) {
				System.out.print("Oops. The passwords didn't match. Type your password again: ");
			}
			password1 = readOnePassword();
			System.out.print("Reenter password: ");
			password2 = readOnePassword();
			if (password1.equals(password2)) {
				done = true;
			} else {
				newtry = true;
			}
		} while(!done);

		return password2;
	}
}
