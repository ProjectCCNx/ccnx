/*
 * A CCNx repository.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.impl.repo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.security.InvalidParameterException;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.Daemon;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.test.BitBucketRepository;

/**
 * Daemon for stand-alone repository on persistent storage in the filesystem.
 */
public class RepositoryDaemon extends Daemon {
	RepositoryServer _server;
	RepositoryStore _repo;
	String _repositoryRoot;
	
	public final static String REPO_STATS = "stats";
	public final static String REPO_CLEAR_STATS = "clearstats";
	public final static String DEBUG_STATS_FILE = "stats.txt";
	
	protected class RepositoryWorkerThread extends Daemon.WorkerThread {

		private static final long serialVersionUID = -6093561895394961537L;
		
		protected RepositoryWorkerThread(String daemonName) {
			super(daemonName);
		}
		
		public void work() {
			synchronized(this) {
				try {
					wait();
				} catch (InterruptedException e) {}	// OK to swallow interrupted exception because it will
													// cause us to exit which is what we want
			}
		}
		
		public void initialize() {
			_server.start();
		}
		
		public void finish() {
			_server.shutDown();
			synchronized (this) {
				notifyAll(); // notifyAll ensures shutdown in interactive case when main thread is join()'ing
			}
		}
		
		public void waitForStart() {
			_server.waitForStart();
		}
		
		public boolean signal(String name) {
			if( REPO_STATS.equalsIgnoreCase(name) ) {
				dumpStats();
				return true;
			}
			if( REPO_CLEAR_STATS.equalsIgnoreCase(name) ) {
				_server.getStats().clearCounters();
				return true;
			}
			return _repo.diagnostic(name);
		}
		
		public Object status(String type) {
			return "running";
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
	
	/**
	 * Parse arguments specific to the Repository
	 * 
	 * Current arguments are:<p>
	 * 
	 * -root <directory> sets the root of the repository (this argument is required).
	 * 
	 * The following arguments are optional:<p>
	 * <ul>
	 * <li>-log <level> enable logging and set the logging level to <level>
	 * <li>-policy <file> use the policy file to set initial policy for the repo
	 * <li>-local <path> set the local name for this repository
	 * <li>-global <path> set the global prefix for this repository
	 * </ul>
	 */
	public void initialize(String[] args, Daemon daemon) {
		Log.info("Starting " + _daemonName + "...");				
		Log.setLevel(Level.INFO);
		try {
			Log.setDefaultLevel(Level.SEVERE);	// turn off all but severe errors
			String repositoryRoot = null;
			File policyFile = null;
			String localName = null;
			String globalPrefix = null;
			String nameSpace = null;
			for (int i = 0; i < args.length; i++) {
				if (args[i].equals("-log")) {
					if (args.length < i + 2) {
						usage();
						return;
					}
					try {
						Level level = Level.parse(args[i + 1]);
						Log.setLevel(Log.FAC_ALL, level);
					} catch (IllegalArgumentException iae) {
						usage();
						return;
					}
					i++;
				} else if (args[i].equals("-repoLog")) {
					if (args.length < i + 2) {
						usage();
						return;
					}
					try {
						Level level = Level.parse(args[i + 1]);
						Log.setLevel(Log.FAC_REPO, level);
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
				} else if (args[i].equals("-prefix")) {
					if (args.length < i + 2)
						throw new InvalidParameterException();
					nameSpace = args[i + 1];
					if (!nameSpace.startsWith("/"))
						nameSpace = "/" + nameSpace;
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

			if (_repo == null)	// default lower half
				_repo = new LogStructRepoStore();
			
			_repositoryRoot = repositoryRoot;
			_repo.initialize(repositoryRoot, policyFile, localName, globalPrefix, nameSpace, null);
			_server = new RepositoryServer(_repo);
			
			Log.info(Log.FAC_REPO, "started repo with response name: "+_server.getResponseName());

			
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
			" [-log <level>] [-repoLog <level>] [-policy <policy_file>] [-local <local_name>] [-global <global_prefix>] [-bb]";
			System.out.println(msg);
			Log.severe(Log.FAC_REPO, msg);
		} catch (Exception e) {
			e.printStackTrace();
			Log.logStackTrace(Level.SEVERE, e);
		}
		System.exit(1);
	}

	protected WorkerThread createWorkerThread() {
		return new RepositoryWorkerThread(daemonName());
	}
	
	/**
	 * Start a new repository daemon
	 * @param args
	 */
	public static void main(String[] args) {
		Daemon daemon = null;
		try {
			daemon = new RepositoryDaemon();
			runDaemon(daemon, args);
			
		} catch (Exception e) {
			System.err.println("Error attempting to start daemon.");
			Log.warning(Log.FAC_REPO, "Error attempting to start daemon.");
			Log.warningStackTrace(e);
		}
	}
	
	protected void dumpStats() {
		// Debug: dump names tree to file
		File statsFile = new File(_repositoryRoot, DEBUG_STATS_FILE);
		PrintStream statsOut = null;
		try {
			if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
				Log.info(Log.FAC_REPO, "Dumping stats to " + statsFile.getAbsolutePath());
			}
			statsOut = new PrintStream(statsFile);
			statsOut.println(_server.getStats().toString());
		} catch (FileNotFoundException ex) {
			Log.warning(Log.FAC_REPO, "Unable to dump stats to " + statsFile.getAbsolutePath());
		} finally {
			if( null != statsOut )
				statsOut.close();
		}
	}

}
