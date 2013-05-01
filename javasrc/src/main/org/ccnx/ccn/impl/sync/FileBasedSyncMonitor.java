/*
 * Part of the CCNx Java Library.
 * 
 * Copyright (C) 2012, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.sync;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;

import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class FileBasedSyncMonitor extends SyncMonitor implements Runnable{
	
	//this is a temporary implementation allowing the java library to fake handling of sync control traffic to glean new names from the repository.
	//This implementation temporarily will utilize bash commands to make and copy backend repository files
	//This implementation will use a thread from the library threadpool for processing when callbacks are registered.
	
	
	
	int runInterval = 2000;
	private static Object runningLock = new Object();
	private static Boolean isRunning = false;
	private static boolean shutDown = false;
		
	private static FileChannel fileChannel;
	
	private static String filename;

	public FileBasedSyncMonitor() throws ConfigurationException{

		synchronized (runningLock) {

			if (!isRunning) {
				//based on the env variable for running ccnr
				filename = System.getenv("CCNR_DIRECTORY");
				if (filename == null) {
					Log.severe("Please set CCNR_DIRECTORY environment variable before running!");
					throw new ConfigurationException("Please set CCNR_DIRECTORY environment variable before running!");
				}

				String configError = "Please install the CCNx installation or set your path to use the ccnnamelist command";
				try {
					Log.fine(Log.FAC_SYNC, "PATH: {0}", System.getenv("PATH"));
					ProcessBuilder pbCheck = new ProcessBuilder();
					pbCheck.command("/bin/sh", "-c", "command -v ccnnamelist");
					int exitStatus = pbCheck.start().waitFor();
					if (exitStatus == 0) {
						//the command is found!
						Log.fine(Log.FAC_SYNC, "ccnnamelist command was found in the path!");
					} else {
						Log.severe(configError);
						throw new ConfigurationException(configError);
					}
				} catch (IOException e2) {
					Log.severe("{0} Exception while checking for correct setup: {1}", configError, e2.getMessage());
					throw new ConfigurationException(configError);
				} catch (InterruptedException e) {
					Log.severe("{0} Exception while checking for correct setup: {1}", configError, e.getMessage());
					throw new ConfigurationException(configError);
				}
			} else {
				Log.fine(Log.FAC_SYNC, "no need to check config!  SyncMonitor is already running!");
			}
		}
		
	}

	/**
	 * Kick off the worker thread to monitor differences in the backend file
	 * 
	 * First check if there are even differences in the timestamps
	 */
	public void run() {
		boolean keepRunning = true;
		synchronized(runningLock) {
			isRunning = true;
		}
		
		File repoDir = new File(filename);
		Log.fine(Log.FAC_SYNC, "repoDir: "+repoDir);
		File repoFile = new File(filename+"/repoFile1");
		
		String commandCreateDiff= "ccnnamelist repoFile1 > names ;  sort names > newnames ; rm names ; :>> oldnames ; diff newnames oldnames > diffNames ; mv newnames oldnames";
		String commandCreateDiffFinal;
		
		long lastReadTime = -1;
		long repoFileTime;
		long lastModified = -1;
		
		FileLock fileLock = null;
		
		while (keepRunning) {
			
			try {
				fileChannel = new RandomAccessFile(filename+"/sync.lock", "rw").getChannel();
				fileLock = fileChannel.lock();
			} catch (FileNotFoundException e1) {
				Log.severe("CCNR_DIRECTORY setting = {0}: file not found.  Exiting.", filename);
				System.exit(1);
			} catch (IOException e) {
				try {
					fileLock.release();
				} catch (IOException e1) {
					//already in an error state...
					Log.warning("Error releasing lock for Sync API file processing: {0}", e1.getMessage());
				}
				Log.severe("Exception when trying to acquire lock to process new names for Sync API: {0}.  Exiting.", e.getMessage());
				System.exit(1);
			}

			
			repoFileTime = repoFile.lastModified();

			//System.out.println("on a new loop! repoFileTime = "+repoFileTime+" lastModified = "+lastModified+" lastReadTime = "+lastReadTime);
			
			//is the diff file new to me?
			for (String diffFiles : repoDir.list()) {
				if (diffFiles.startsWith("diffNames.")) {
					//this is a diff names to process
					Log.fine(Log.FAC_SYNC, "have a diffFile: "+diffFiles);
					if (lastModified < Long.parseLong(diffFiles.substring(10)) || lastModified == -1) {
						//we need to read in this file
						Log.fine(Log.FAC_SYNC, "this is a new diffFile!  reading it in!");
						lastModified = Long.parseLong(diffFiles.substring(10));
						
						//read in file here
						try {
							
							Log.fine(Log.FAC_SYNC, "going to read in: "+filename+"/"+diffFiles);
							
							BufferedReader buf = new BufferedReader(new FileReader(filename+"/"+diffFiles));
							String line = buf.readLine();
							while ( line != null ) {
								Log.fine(Log.FAC_SYNC, "reading in line!: {0}", line);
								processNewName(line);
								line = buf.readLine();
							}
							lastReadTime = lastModified;
						} catch (IOException e) {
							Log.warning("Error while executing bash commands to find diffs in repo files: {0}", e.getMessage());
						}
						lastReadTime = lastModified;
					} else {
						//System.out.println("i already read in this file.");
					}
				}
			}
				
			
			Log.fine(Log.FAC_SYNC, "checking if the repo backend is new.  last modified: {0} repoFileTime: {1} diff: {2} runInterval: {3}", lastModified, repoFileTime, (System.currentTimeMillis() - lastModified), runInterval);
			//now check if the file is even new...  might have more names to process
			if (lastModified == -1 || (repoFileTime > lastModified && System.currentTimeMillis() - lastModified > runInterval )) {
				Log.fine(Log.FAC_SYNC, "the repo has a new backend, and the last time the process was run at least one runInterval before");
				
				lastModified = repoFileTime;
				//there is something new in the backend file...  need to process
				Process pr;
				try {					
					commandCreateDiffFinal = commandCreateDiff.replace("diffNames", "diffNames."+repoFileTime);
					if (lastReadTime!=-1)
						commandCreateDiffFinal = commandCreateDiffFinal.concat(" ; rm diffNames."+lastReadTime);
					Log.fine(Log.FAC_SYNC, "executing command: "+commandCreateDiffFinal);
					
				    ProcessBuilder pb = new ProcessBuilder();
					pb.directory(repoDir);
					pb.redirectErrorStream(true);
					pb.command("/bin/sh", "-c", commandCreateDiffFinal);
					pr = pb.start();
					pr.waitFor();
					
					Log.fine(Log.FAC_SYNC, "done processing the backend, now reading in diffs: "+filename+"/diffNames."+repoFileTime);
					BufferedReader buf = new BufferedReader(new FileReader(filename+"/diffNames."+repoFileTime));
					String line = buf.readLine();
					while ( line != null ) {
						Log.fine(Log.FAC_SYNC, "reading in line!: {0}", line);
						processNewName(line);
						
						line = buf.readLine();
					}
					lastReadTime = lastModified;
				} catch (IOException e) {
					Log.warning("Error while executing bash commands to find diffs in repo files: {0}", e.getMessage());
				} catch (InterruptedException e) {
					Log.warning("Error while waiting for bash commands to find diffs in repo files: {0}", e.getMessage());
				} 
			} else {
				//there is nothing new, might as well sleep
				//System.out.println("nothing new at this time...  going back to sleep");
			}
			
			try {
				fileLock.release();
			} catch (IOException e) {
				Log.severe("Exception when trying to release lock to process new names for Sync API: {0}.  Exiting.", e.getMessage());
				System.exit(1);
			}
			
			try {
				Thread.sleep(runInterval);
			} catch (InterruptedException e) {
				Log.warning("FileBasedSyncMonitor:  error while sleeping... {0}", e.getMessage());
			}
			
			synchronized(runningLock) {
				if (checkShutdown()) {
					keepRunning = false;
					Log.fine(Log.FAC_SYNC, "isRunning was false, time to shut down");
					isRunning = false;
					shutDown = false;
				}
			}
		}
		return;
	}
	
	private void processNewName(String line) {
		line = line.replaceAll("< ", "");
		if (line.startsWith("ccnx:/")) {
			try {
				ContentName newName = ContentName.fromURI(line);
				synchronized(callbacks) {
					for (ConfigSlice cs : callbacks.keySet()) {
						if (cs.prefix.isPrefixOf(newName)) {
							for (CCNSyncHandler handler: callbacks.get(cs)) {
								handler.handleContentName(cs, newName);
							}
						}
					}
				}
			} catch (MalformedContentNameStringException e) {
				Log.warning("Error while processing new names from executed script: {0} {1}", line, e.getMessage());
			}
		}
	}
	
	private boolean checkShutdown() {
		synchronized(runningLock) {
			return shutDown;
		}
	}
	
	private void processCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		synchronized(callbacks) {
			Log.fine(Log.FAC_SYNC, "isRunning: {0}", isRunning);
			synchronized (runningLock) {
				if (!isRunning && !checkShutdown()) {
					//this means we do not have a thread...  and therefore are not watching the names now.
					Log.fine(Log.FAC_SYNC, "sync was not running, clearing callbacks and starting up sync thread");
					shutDown = false;
					callbacks.clear();
					//System.out.println("was not running...  starting up now!");
					SystemConfiguration._systemThreadpool.execute(this);
				} else if (isRunning && checkShutdown()) {
					//in case we thought we were done running, but really need to stay up.
					Log.fine(Log.FAC_SYNC, "sync still running, but was set to shutdown, cancel shutdown and clear callbacks");
					shutDown = false;
					callbacks.clear();
				} else if (isRunning && !checkShutdown())  {
					//normal operation...  good.
					Log.fine(Log.FAC_SYNC, "sync in normal operation during callback");
				}  else {
					Log.fine(Log.FAC_SYNC, "invalid run state: isRunning = {0} checkShutdown = {1}", isRunning, checkShutdown());
				}
			}
			//now we need to register the callback...  check the prefix
			registerCallbackInternal(syncHandler, slice);
		}
	}
	
	private void processRemoveCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		synchronized(callbacks) {
			removeCallbackInternal(syncHandler, slice);
			if (callbacks.isEmpty()) {
				Log.fine(Log.FAC_SYNC, "all callbacks are removed, shutting down sync");
				//we don't have any registered callbacks, we can go ahead and stop running.
				synchronized (runningLock) {
					shutDown = true;
				}
			}
		}
	}
	
	
	public void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice, byte[] startHash, ContentName startName) {
		//start monitoring the namespace, if not already monitored...  if so, add to the callback list
		processCallback(syncHandler, slice);
	}
	
	public void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		// remove callback from hashmap.  turn off thread if no longer needed
		processRemoveCallback(syncHandler, slice);
	}
	
	public void shutdown(ConfigSlice slice) {
		ArrayList<CCNSyncHandler> cb = callbacks.get(slice);
		
		// Will automatically shutdown when all are removed
		for (CCNSyncHandler syncHandler : cb)
			removeCallback(syncHandler, slice);		
	}

	public SyncNodeCache getNodeCache(ConfigSlice slice) {
		return null;
	}
}
