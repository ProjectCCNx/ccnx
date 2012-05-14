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
import java.util.HashMap;

import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class FileBasedSyncMonitor implements SyncMonitor, Runnable{
	
	//this is a temporary implementation allowing the java library to fake handling of sync control traffic to glean new names from the repository.
	//This implementation temporarily will utilize bash commands to make and copy backend repository files
	//This implementation will use a thread from the library threadpool for processing when callbacks are registered.
	
	
	
	int runInterval = 2000;
	private static Boolean isRunning = false;
	
	private static HashMap<ConfigSlice, ArrayList<CCNSyncHandler>> callbacks = new HashMap<ConfigSlice, ArrayList<CCNSyncHandler>>();
	
	private static FileChannel fileChannel;
	
	private static String filename;

	public FileBasedSyncMonitor() throws ConfigurationException{

		synchronized (isRunning) {

			if (!isRunning) {
				//based on the env variable for running ccnr
				filename = System.getenv("CCNR_DIRECTORY");
				if (filename == null) {
					Log.severe("Please set CCNR_DIRECTORY environment variable before running!");
					throw new ConfigurationException("Please set CCNR_DIRECTORY environment variable before running!");
				}

				String configError = "Please install the CCNx installation or set your path to use the ccnnamelist command";
				try {
					System.out.println("PATH: "+System.getenv("PATH"));
					ProcessBuilder pbCheck = new ProcessBuilder();
					pbCheck.command("/bin/sh", "-c", "command -v ccnnamelist");
					int exitStatus = pbCheck.start().waitFor();
					System.out.println("the exitValue = "+exitStatus);
					if (exitStatus == 0) {
						//the command is found!
						Log.fine(Log.FAC_REPO, "ccnnamelist command was found in the path!");
						System.out.println("ccnnamelist command was found in the path!");
					} else {
						System.out.println(configError);
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
				System.out.println("no need to check config!  SyncMonitor is already running!");
			}
		}
		
	}

	/**
	 * Kick off the worker thread to monitor differences in the backend file
	 * 
	 * First check if there are even differences in the timestamps
	 */
	public void run() {
		
		File repoDir = new File(filename);
		System.out.println("repoDir: "+repoDir);
		File repoFile = new File(filename+"/repoFile1");
		
		String commandCreateDiff= "ccnnamelist repoFile1 > names ;  sort names > newnames ; rm names ; diff -N newnames oldnames > diffNames ; mv newnames oldnames";
		String commandReadDiff = "cat diffNames";
		String commandCreateDiffFinal;
		
		long lastReadTime = -1;
		long repoFileTime;
		long lastModified = -1;
		
		FileLock fileLock = null;
		
		while (!checkShutdown()) {
			
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
					System.out.println("have a diffFile: "+diffFiles);
					if (lastModified < Long.parseLong(diffFiles.substring(10)) || lastModified == -1) {
						//we need to read in this file
						System.out.println("this is a new diffFile!  reading it in!");
						lastModified = Long.parseLong(diffFiles.substring(10));
						
						//read in file here
						try {
							
							System.out.println("going to read in: "+commandReadDiff);
							
							BufferedReader buf = new BufferedReader(new FileReader(filename+"/"+diffFiles));
							String line = buf.readLine();
							while ( line != null ) {
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
				
			//now check if the file is even new...  might have more names to process
			if (lastModified == -1 || (repoFileTime > lastModified && System.currentTimeMillis() - lastModified > runInterval )) {
				System.out.println("the repo has a new backend, and the last time the process was run at least one runInterval before");
				
				lastModified = repoFileTime;
				//there is something new in the backend file...  need to process
				Process pr;
				try {					
					commandCreateDiffFinal = commandCreateDiff.replace("diffNames", "diffNames."+repoFileTime);
					if (lastReadTime!=-1)
						commandCreateDiffFinal = commandCreateDiffFinal.concat(" ; rm diffNames."+lastReadTime);
					System.out.println("executing command: "+commandCreateDiffFinal);
					
				    ProcessBuilder pb = new ProcessBuilder();
					pb.directory(repoDir);
					pb.redirectErrorStream(true);
					pb.command("/bin/sh", "-c", commandCreateDiffFinal);
					pr = pb.start();
					pr.waitFor();
					
					System.out.println("done processing the backend, now reading in diffs: "+filename+"/diffNames."+repoFileTime);
					BufferedReader buf = new BufferedReader(new FileReader(filename+"/diffNames."+repoFileTime));
					String line = buf.readLine();
					while ( line != null ) {
						System.out.println("reading in line!: "+line);
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
		}
		synchronized(isRunning) {
			System.out.println("isRunning was false, time to shut down");
			isRunning = false;
		}
		return;
	}
	
	private void processNewName(String line) {
		line = line.replaceAll("< ", "");
		if (line.startsWith("ccnx:/")) {
			//System.out.println(line);
			try {
				ContentName newName = ContentName.fromURI(line);
				for (ConfigSlice cs : callbacks.keySet()) {
					//System.out.println("checking name: "+newName+" prefix: "+cs.prefix);
					if (cs.prefix.isPrefixOf(newName)) {
						//System.out.println("found a match!!!  doing callbacks!  there are "+callbacks.get(cs).size());
						for (CCNSyncHandler handler: callbacks.get(cs)) {
							//System.out.println("callback!");
							handler.handleContentName(cs, newName);
						}
					} else {
						//System.out.println("not a match to this prefix");
					}
				}
			} catch (MalformedContentNameStringException e) {
				Log.warning("Error while processing new names from executed script: {0} {1}", line, e.getMessage());
			}
		}
	}
	
	private boolean checkShutdown() {
		//System.out.println("checking shutdown: "+ (!isRunning));
		synchronized(callbacks) {
			return !isRunning;
		}
	}
	
	private void processCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		synchronized(callbacks) {
			System.out.println("isRunning: "+isRunning);
			synchronized (isRunning) {
				if (!isRunning) {
					//this means we do not have a thread...  and therefore are not watching the names now.
					isRunning = true;
					callbacks.clear();
					//System.out.println("was not running...  starting up now!");
					SystemConfiguration._systemThreadpool.execute(this);
				}
			}
			//now we need to register the callback...  check the prefix
			ArrayList<CCNSyncHandler> cb = callbacks.get(slice);
			if (cb != null) {
				//the slice is already registered...  add handler if not there
				if (cb.contains(syncHandler)) {
					System.out.println("the handler is already registered!");
				} else {
					cb.add(syncHandler);
					System.out.println("the handler has been added!");
				}
			} else {
				//the slice is not there...  adding now!
				cb = new ArrayList<CCNSyncHandler>();
				cb.add(syncHandler);
				callbacks.put(slice, cb);
			}
		}
	}
	
	private void processRemoveCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		synchronized(callbacks) {
			ArrayList<CCNSyncHandler> cb = callbacks.get(slice);
			if (cb.contains(syncHandler)) {
				System.out.println("found the callback to remove");
				cb.remove(syncHandler);
				if (cb.isEmpty()) {
					//no callbacks left for the slice, go ahead and remove it.
					callbacks.remove(slice);
				}
			}
			if (callbacks.isEmpty()) {
				//we don't have any registered callbacks, we can go ahead and stop running.
				synchronized (isRunning) {
					isRunning = false;
				}
			}
		}
	}
	
	@Override
	public void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		//start monitoring the namespace, if not already monitored...  if so, add to the callback list
		processCallback(syncHandler, slice);
	}
	@Override
	public void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		// remove callback from hashmap.  turn off thread if no longer needed
		processRemoveCallback(syncHandler, slice);
	}

}
