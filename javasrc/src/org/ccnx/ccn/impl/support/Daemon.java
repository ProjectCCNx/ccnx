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
import java.lang.management.ManagementFactory;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ccnx.ccn.impl.CCNNetworkManager;


/**
 * Wrapper class for things that run as daemons. Based
 * on the CA issuer daemon.
 * @author smetters, rasmussen
 *
 */
public class Daemon {

	protected enum Mode {MODE_UNKNOWN, MODE_START, MODE_STOP, MODE_INTERACTIVE, MODE_DAEMON, MODE_SIGNAL};

	protected String _daemonName = null;
	protected static DaemonListenerClass _daemonListener = null;
	protected boolean _interactive = false;
	
	public static final String PROP_DAEMON_MEMORY = "ccn.daemon.memory";
	public static final String PROP_DAEMON_DEBUG_PORT = "ccn.daemon.debug";
	public static final String PROP_DAEMON_OUTPUT = "ccn.daemon.output";
	public static final String PROP_DAEMON_PROFILE = "ccn.daemon.profile";
	
	public static final int STOP_TIMEOUT = 30000;  // 30 seconds

	/**
	 * Interface describing the RMI server object sitting inside
	 * the daemon 
	 */
	public interface DaemonListener extends Remote {
		public String startLoop() throws RemoteException; // returns pid
		public void shutDown() throws RemoteException;
		public boolean signal(String name) throws RemoteException;
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

			getRMIFile(_daemonName, getPID()).delete();		
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

				String pid = getPID();
				if (null != getPID()) {
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
	}
	
	public Daemon() {_daemonName = "namelessDaemon";}
	
	public String daemonName() { return _daemonName; }
	
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
		// Try the JVM mgmt bean, reported to work on variety
		// of operating systems on the Sun JVM.
		try {
			String pid = null;
			String vmname = ManagementFactory.getRuntimeMXBean().getName();
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
	}

	private static void startDaemon(String daemonName, String daemonClass, String args[]) throws IOException, ClassNotFoundException {

		String mypid = getPID();
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
		
		String debugPort = System.getProperty(PROP_DAEMON_DEBUG_PORT);
		if (debugPort != null) {
			argList.add("-Xrunjdwp:transport=dt_socket,address=" + debugPort + ",server=y,suspend=n");
		}
		
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
		FileOutputStream fos = new FileOutputStream("daemon_cmd.txt");
		fos.write(cmd.getBytes());
		fos.flush();
		fos.close();
		Log.info("Starting daemon with command line: " + cmd);
		
		ProcessBuilder pb = new ProcessBuilder(argList);
		pb.redirectErrorStream(true);
		Process child = pb.start();
		
		String outputFile = System.getProperty(PROP_DAEMON_OUTPUT);
		if (outputFile != null) {
			new DaemonOutput(child.getInputStream(), outputFile);
		}
		
		// Initial RMI file never named with PID to permit
		// us to read it without knowing PID.  After 
		// daemon operation is started below the file 
		// will be renamed with PID if possible
		while (!getRMIFile(daemonName, null).exists()) {
			try {
				Thread.sleep(200);
				
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
		stopTimer.schedule(daemon.new StopTimer(daemonName, pid), STOP_TIMEOUT);

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
	 * @param PID  the PID to add to the RMI file name or null if unavailable
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

	protected static void runDaemon(Daemon daemon, String args[]) throws IOException {
		
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
				String pid = getPID();
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
