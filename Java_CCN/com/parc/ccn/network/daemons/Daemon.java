package com.parc.ccn.network.daemons;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.Date;

import com.parc.ccn.Library;

/**
 * Wrapper class for things that run as daemons. Based
 * on the CA issuer daemon.
 * @author smetters
 *
 */
public class Daemon {

	protected enum Mode {MODE_UNKNOWN, MODE_START, MODE_STOP, MODE_INTERACTIVE, MODE_DAEMON};

	protected String _daemonName = null;
	protected static DaemonListenerClass _daemonListener = null;

	/**
	 * Interface describing the RMI server object sitting inside
	 * the daemon 
	 */
	public interface DaemonListener extends Remote {
		public boolean startLoop() throws RemoteException;
		public void shutDown() throws RemoteException;
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
			Library.logger().info("Initializing daemon thread " + new Date().toString() +".");

			initialize();
			
			System.out.println("Daemon thread started " + new Date().toString() +".");
			Library.logger().info("Daemon thread started " + new Date().toString() +".");

			do {

				try {
					work();
				} catch (Exception e) {
					Library.logger().warning("Error in daemon thread: " + e.getMessage());
					Library.warningStackTrace(e);
				}

				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
				}
			} while (_keepGoing);

			// ok, we were asked to shut down
			System.out.println("Shutting down the daemon.");	
			Library.logger().info("Shutting down the daemon.");	

			try {
				UnicastRemoteObject.unexportObject(_daemonListener, true);
			} catch (NoSuchObjectException e) {
			}

			getRMIFile(_daemonName).delete();		
			System.exit(0);
		}			

		public void shutDown() {
			_keepGoing = false;
			interrupt();
		}
		
		/**
		 * Specialized by subclasses, called by worker thread.
		 *
		 */
		public void work() {
			Library.logger().info("Should not be here, in WorkerThread.work().");			
		}
		public void initialize() {
			Library.logger().info("Should not be here, in WorkerThread.initialize().");
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

		public boolean startLoop() throws RemoteException {

			System.out.println("Starting the daemon loop.");
			Library.logger().info("Starting the daemon loop.");

			try {
				_daemonThread.start();

				return true;

			} catch(Exception e) {
				throw new RemoteException(e.getMessage(), e);
			}
		}
	}
	
	public Daemon(String args[]) {_daemonName = "namelessDaemon";}
	
	public String daemonName() { return _daemonName; }
	
	/**
	 * Overridden by subclasses.
	 *
	 */
	protected void usage() {
		try {
			System.out.println("usage: " + this.getClass().getName() + " [-start | -stop | -interactive]");
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);
	}

	/**
	 * overridden by subclasses to make right type of thread.
	 * @return
	 */
	protected WorkerThread createWorkerThread() {
		return new WorkerThread(daemonName());
	}

	protected static void runAsDaemon(Daemon daemon) throws RemoteException, FileNotFoundException, IOException {
		Library.logger().info(daemon.daemonName() + " started in background " + new Date());

		_daemonListener = new DaemonListenerClass(daemon.createWorkerThread());

		Remote stub = RemoteObject.toStub(_daemonListener);		

		File tempFile = getRMITempFile(daemon.daemonName());

		ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(tempFile));

		out.writeObject(stub);
		out.flush();
		out.close();

		// this is atomic
		tempFile.renameTo(getRMIFile(daemon.daemonName()));
	}

	private static void startDaemon(String daemonName, String daemonClass, String args[]) throws IOException, ClassNotFoundException {

		if (getRMIFile(daemonName).exists()) {
			System.out.println("Daemon already running. Use '" + daemonName + " -stop' to kill the daemon.");
			return;
		}

		String cmd = "java ";
		cmd += "-cp " + System.getProperty("java.class.path") + " ";

		cmd += daemonClass + " ";
		cmd += "-daemon";
		for (int i=1; i < args.length; ++i) {
			cmd += " ";
			cmd += args[i];
		}
		Library.logger().info("Starting daemon with command line: " + cmd);

		// DKS -- for some reason the exec can't find the class...
		// almost certainly because rmic not running to build the
		// stub. Don't know best mech to implement this nicely
		// without going to ant.
		// Can start jackrabbit without this by adding a call
		// to startLoop inside runAsDaemon...
		Process child = Runtime.getRuntime().exec(cmd);

		while (!getRMIFile(daemonName).exists()) {
			try {
				Thread.sleep(200);
				
				// this should throw an exception
				try {
					int exitValue = child.exitValue();
					// if we get here, the child has exited
					Library.logger().warning("Could not launch daemon " + daemonName + ". Daemon exit value is " + exitValue + ".");
					System.err.println("Could not launch daemon " + daemonName + ". Daemon exit value is " + exitValue + ".");
					return;
				} catch (IllegalThreadStateException e) {
				}

			} catch (InterruptedException e) {
			}
		}

		ObjectInputStream in = new ObjectInputStream(new FileInputStream(getRMIFile(daemonName)));

		DaemonListener l = (DaemonListener)in.readObject();		

		boolean b = false;
		b = l.startLoop();
		System.out.println("Started daemon " + daemonName + ".");
		Library.logger().info("Started daemon " + daemonName + ".");
	}

	protected static void stopDaemon(String daemonName) throws FileNotFoundException, IOException, ClassNotFoundException {

		if (!getRMIFile(daemonName).exists()) {
			System.out.println("Daemon " + daemonName + " does not appear to be running.");
			Library.logger().info("Daemon " + daemonName + " does not appear to be running.");
			return;
		}

		ObjectInputStream in = new ObjectInputStream(new FileInputStream(getRMIFile(daemonName)));

		DaemonListener l = (DaemonListener)in.readObject();		

		in.close();

		try {
			l.shutDown();
			System.out.println("Daemon " + daemonName + " is shut down.");
			Library.logger().info("Daemon " + daemonName + " is shut down.");
		} catch(RemoteException e) {
			// looks like the RMI file is still here, but the daemon is gone. let's delete the file, then,
			System.out.println("Daemon " + daemonName + " seems to have died some other way, cleaning up state...");
			Library.logger().info("Daemon " + daemonName + " seems to have died some other way, cleaning up state...");
			getRMIFile(daemonName).delete();
		}

	}

	protected static File getRMIFile(String daemonName) {
		String name = ".rmi-server-" + daemonName + ".obj";
		return new File(System.getProperty("user.home"), name);
	}

	protected static File getRMITempFile(String daemonName) throws IOException {
		String prefix = ".rmi-server-" + daemonName;

		return File.createTempFile(prefix, null, new File(System.getProperty("user.home")));
	}

	protected static void runDaemon(Daemon daemon, String args[]) throws IOException {
		
		Mode mode = Mode.MODE_UNKNOWN;

		if (0 == args.length) { 
			mode = Mode.MODE_INTERACTIVE;
		} else if (args[0].equals("-start")) {
			mode = Mode.MODE_START;
		} else if (args[0].equals("-stop")) {
			mode = Mode.MODE_STOP;
		} else if (args[0].equals("-daemon")) {
			mode = Mode.MODE_DAEMON;
		} else if (args.length == 1) {
			mode = Mode.MODE_INTERACTIVE;
		} else {
			System.out.println("Unknown option " + args[1]);
			daemon.usage();
		}

		// enums are capable of very sophisticated behavior
		// unfortunately, straightforward switching is not among it...
		try {
			if (mode == Mode.MODE_INTERACTIVE) {
				Library.logger().info("Running " + daemon.daemonName() + " in the foreground.");
				WorkerThread wt = daemon.createWorkerThread();
				wt.start();
				wt.join();
				System.exit(0);
			} else if (mode == Mode.MODE_START) {
				startDaemon(daemon.daemonName(), daemon.getClass().getName(), args);
				System.exit(0);

			} else if (mode == Mode.MODE_STOP) {
				stopDaemon(daemon.daemonName());
				System.exit(0);

			} else if (mode == Mode.MODE_DAEMON) {
				// this will sit in a loop
				runAsDaemon(daemon);

			} else {
				daemon.usage();
			}
			
		} catch (Exception e) {
			Library.logger().warning(e.getClass().getName() + " in daemon startup: " + e.getMessage());
			Library.warningStackTrace(e);
		}							
		Library.logger().info("Daemon runner finished.");
	}

	public static void main(String[] args) {
		
		// Need to override in each subclass to make proper class.
		Daemon daemon = null;
		try {
			daemon = new Daemon(args);
			runDaemon(daemon, args);
		} catch (Exception e) {
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
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
