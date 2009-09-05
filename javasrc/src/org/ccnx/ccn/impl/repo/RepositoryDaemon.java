package org.ccnx.ccn.impl.repo;

import java.io.File;
import java.io.FileInputStream;
import java.security.InvalidParameterException;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Daemon;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.test.BitBucketRepository;

/**
 * Daemon for stand-alone repository on persistent storage in the filesystem.
 * @author jthornto
 *
 */
public class RepositoryDaemon extends Daemon {
	RepositoryServer _server;
	Repository _repo;
	CCNHandle _handle;
	
	protected class RepositoryWorkerThread extends Daemon.WorkerThread {

		private static final long serialVersionUID = -6093561895394961537L;
		
		protected RepositoryWorkerThread(String daemonName) {
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
				} while (interrupted);
			}
		}
		
		public void initialize() {
			_server.start();
		}
		
		public void finish() {
			_server.shutDown();
			_repo.shutDown();
			synchronized (this) {
				notifyAll(); // notifyAll ensures shutdown in interactive case when main thread is join()'ing
			}
		}
		
		public boolean signal(String name) {
			return _repo.diagnostic(name);
		}
	}
	
	public RepositoryDaemon() {
		super();
		// This is a daemon: it should not do anything in the
		// constructor but everything in the initialize() method
		// which will be run in the process that will finally 
		// execute as the daemon, rather than in the launching
		// and stopping processes also.
		_daemonName = "repository";
	}
	
	public void initialize(String[] args, Daemon daemon) {
		Log.info("Starting " + _daemonName + "...");				
		Log.setLevel(Level.INFO);
		boolean useLogging = false;
		try {
			_handle = CCNHandle.open();

			SystemConfiguration.setLogging("repo", false);
			String repositoryRoot = null;
			File policyFile = null;
			String localName = null;
			String globalPrefix = null;
			for (int i = 0; i < args.length; i++) {
				if (args[i].equals("-log")) {
					if (args.length < i + 2) {
						usage();
						return;
					}
					try {
						SystemConfiguration.setLogging("repo", true);
						Level level = Level.parse(args[i + 1]);
						Log.setLevel(level);
						useLogging = level.intValue() < Level.INFO.intValue();
					} catch (IllegalArgumentException iae) {
						usage();
						return;
					}
					i++;
				} else if (args[i].equals("-root")) {
					if (args.length < i + 2)
						throw new InvalidParameterException();
					repositoryRoot = args[i + 1];
					i++;
				} else if (args[i].equals("-policy")) {
					if (args.length < i + 2)
						throw new InvalidParameterException();
					policyFile = new File(args[i + 1]);
					i++;
				} else if (args[i].equals("-local")) {
					if (args.length < i + 2)
						throw new InvalidParameterException();
					localName = args[i + 1];
					i++;
				} else if (args[i].equals("-global")) {
					if (args.length < i + 2)
						throw new InvalidParameterException();
					globalPrefix = args[i + 1];
					if (!globalPrefix.startsWith("/"))
						globalPrefix = "/" + globalPrefix;
					i++;
				} else if (args[i].equals("-bb")) {
					// Following is for upper half performance testing for writes
					_repo = new BitBucketRepository();
				} else if(args[i].equals("-singlefile")) {
					// This is a reference to an old repo type that no longer exists
					System.out.println("-singlefile no longer supported");
					throw new InvalidParameterException();
				}
			}

			if (!useLogging)
				SystemConfiguration.setLogging("repo", false);
			
			if (_repo == null)	// default lower half
				_repo = new RFSLogImpl();
			
			_repo.initialize(_handle, repositoryRoot, policyFile, localName, globalPrefix);
			_server = new RepositoryServer(_handle, _repo);
			
		} catch (InvalidParameterException ipe) {
			usage();
		} catch (Exception e) {
			e.printStackTrace();
			Log.logStackTrace(Level.SEVERE, e);
			System.exit(1);
		}
	}
	
	protected void usage() {
		try {
			// Without parsing args, we don't know which repo impl we will get, so show the default 
			// impl usage and allow for differences 
			String msg = "usage: " + this.getClass().getName() + " -start -root <repository_root> | -stop <pid> | -interactive | -signal <signal> <pid>" +
			" [-log <level>] [-policy <policy_file>] [-local <local_name>] [-global <global_prefix>] [-bb]";
			System.out.println(msg);
			Log.severe(msg);
		} catch (Exception e) {
			e.printStackTrace();
			Log.logStackTrace(Level.SEVERE, e);
		}
		System.exit(1);
	}

	protected WorkerThread createWorkerThread() {
		return new RepositoryWorkerThread(daemonName());
	}
	
	
	public static void main(String[] args) {
		Daemon daemon = null;
		try {
			daemon = new RepositoryDaemon();
			runDaemon(daemon, args);
			
		} catch (Exception e) {
			System.err.println("Error attempting to start daemon.");
			Log.warning("Error attempting to start daemon.");
			Log.warningStackTrace(e);
		}
	}
}
