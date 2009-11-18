/**
 * A CCNx file proxy program.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.apps.ccnfileproxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileOutputStream;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * CCNFileProxy is a file system proxy that makes files on the local system
 * available over the CCNx network. It takes a directory from which to serve files,
 * which it treats as the root of its content tree, and an optional ccnx URI
 * to serve as the prefix for that file content as represented in CCNx.
 * 
 * For example, if you have a directory /foo in the file system, with the following
 * contents:
 * 	/foo/
 * 		bar.txt
 * 		baz/
 * 			box.txt
 * and you call CCNFileProxy /foo ccnx:/testprefix
 * 
 * then asking for ccnx:/testprefix/bar.txt would return the file bar.txt (segmented
 * appropriately), and asking for ccnx:/testprefix/baz/box.txt would return box.txt.
 * The version for each file is set using the last modified information available from
 * the file system for the real file (but the file is re-signed every time you ask
 * for it from this server, so will result in slightly different pieces of content
 * with different signatures). The default prefix is ccnx:/, which means asking
 * for ccnx:/bar.txt would get you bar.txt.
 * 
 * Future improvements: 
 * - cache the original signing information so even if the
 * data falls out of ccnd's cache, you get the same signature information back,
 * - implement a NE responder to list files. 
 * - signal handling
 * - logging level control from a command line argument
 * - move file writer to a separate thread
 */
public class CCNFileProxy implements CCNFilterListener {
	
	static String DEFAULT_URI = "ccnx:/";
	static int BUF_SIZE = 4096;
	
	protected boolean _finished = false;
	protected ContentName _prefix; 
	protected String _filePrefix;
	protected File _rootDirectory;
	protected CCNHandle _handle;
	
	public static void usage() {
		System.err.println("usage: CCNFileProxy <file path to serve> [<ccn prefix URI> default: ccn:/]");
	}

	public CCNFileProxy(String filePrefix, String ccnxURI) throws MalformedContentNameStringException, ConfigurationException, IOException {
		_prefix = ContentName.fromURI(ccnxURI);
		_filePrefix = filePrefix;
		_rootDirectory = new File(filePrefix);
		if (!_rootDirectory.exists()) {
			Log.severe("Cannot serve files from directory {0}: directory does not exist!", filePrefix);
			throw new IOException("Cannot serve files from directory " + filePrefix + ": directory does not exist!");
		}
		_handle = CCNHandle.open();
	}
	
	public void start() {
		Log.info("Starting file proxy for " + _filePrefix + " on CCNx namespace " + _prefix + "...");
		System.out.println("Starting file proxy for " + _filePrefix + " on CCNx namespace " + _prefix + "...");
		// All we have to do is say that we're listening on our main prefix.
		_handle.registerFilter(_prefix, this);
	}
	

	@Override
	public int handleInterests(ArrayList<Interest> interests) {
		// Alright, we've gotten an interest. Either it's an interest for a stream we're
		// already reading, or it's a request for a new stream.
		int count = 0;
		for (Interest interest : interests) {
			Log.info("CCNFileProxy main responder: got new interest: {0}", interest);
			
			// Test to see if we need to respond to it.
			if (!_prefix.isPrefixOf(interest.name())) {
				Log.info("Unexpected: got an interest not matching our prefix (which is {0})", _prefix);
				continue;
			}
			
			// We see interests for all our segments, and the header. We want to only
			// handle interests for the first segment of a file, and not the first segment
			// of the header. Order tests so most common one (segments other than first, non-header)
			// fails first.
			if (SegmentationProfile.isSegment(interest.name()) && !SegmentationProfile.isFirstSegment(interest.name())) {
				Log.info("Got an interest for something other than a first segment, ignoring {0}.", interest.name());
				continue;
			} else if (SegmentationProfile.isHeader(interest.name())) {
				Log.info("Got an interest for the first segment of the header, ignoring {0}.", interest.name());
				continue;
			} 

			// Write the file
			try {
				if (writeFile(interest)) {
					count++;
				}
			} catch (IOException e) {
				Log.warning("IOException writing file {0}: {1}: {2}", interest.name(), e.getClass().getName(), e.getMessage());
			}
		}
		return count;
	}
	
	/**
	 * Actually write the file; should probably run in a separate thread.
	 * @param fileNamePostfix
	 * @throws IOException 
	 */
	protected boolean writeFile(Interest outstandingInterest) throws IOException {
		ContentName fileNamePostfix = outstandingInterest.name().postfix(_prefix);
		if (null == fileNamePostfix) {
			// Only happens if interest.name() is not a prefix of _prefix.
			Log.info("Unexpected: got an interest not matching our prefix (which is {0})", _prefix);
			return false;
		}
		Log.info("writing file for interest {0}, postfix {1}", outstandingInterest, fileNamePostfix);

		File fileToWrite = new File(_rootDirectory, fileNamePostfix.toString());
		Log.info("CCNFileProxy: extracted request for file: " + fileToWrite.getAbsolutePath() + " exists? ", fileToWrite.exists());
		if (!fileToWrite.exists()) {
			Log.warning("File {0} does not exist. Ignoring request.", fileToWrite.getAbsoluteFile());
			return false;
		}
		
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(fileToWrite);
		} catch (FileNotFoundException fnf) {
			Log.warning("Unexpected: file we expected to exist doesn't exist: {0}!", fileToWrite.getAbsolutePath());
			return false;
		}
		
		// Set the version of the CCN content to be the last modification time of the file.
		CCNTime modificationTime = new CCNTime(fileToWrite.lastModified());
		ContentName versionedName = VersioningProfile.addVersion(new ContentName(_prefix, fileNamePostfix.components()), modificationTime);

		// CCNFileOutputStream will use the version on a name you hand it (or if the name
		// is unversioned, it will version it).
		CCNFileOutputStream ccnout = new CCNFileOutputStream(versionedName, _handle);
		
		// We have an interest already, register it so we can write immediately.
		ccnout.addOutstandingInterest(outstandingInterest);
		
		byte [] buffer = new byte[BUF_SIZE];
		
		int read = fis.read(buffer);
		while (read >= 0) {
			ccnout.write(buffer, 0, read);
			read = fis.read(buffer);
		} 
		fis.close();
		ccnout.close(); // will flush
		
		return true;
	}

    /**
     * Turn off everything.
     * @throws IOException 
     */
	public void shutdown() throws IOException {
		if (null != _handle) {
			_handle.unregisterFilter(_prefix, this);
			Log.info("Shutting down file proxy for " + _filePrefix + " on CCNx namespace " + _prefix + "...");
			System.out.println("Shutting down file proxy for " + _filePrefix + " on CCNx namespace " + _prefix + "...");
		}
		_finished = true;
	}
	
	public boolean finished() { return _finished; }

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		if (args.length < 1) {
			usage();
			return;
		}
		
		String filePrefix = args[0];
		String ccnURI = (args.length > 1) ? args[1] : DEFAULT_URI;
		
		try {
			CCNFileProxy proxy = new CCNFileProxy(filePrefix, ccnURI);
			
			// All we need to do now is wait until interrupted.
			proxy.start();
			
			while (!proxy.finished()) {
				// we really want to wait until someone ^C's us.
				try {
					Thread.sleep(100000);
				} catch (InterruptedException e) {
					// do nothing
				}
			}
		} catch (Exception e) {
			Log.warning("Exception in ccnFileProxy: type: " + e.getClass().getName() + ", message:  "+ e.getMessage());
			Log.warningStackTrace(e);
			System.err.println("Exception in ccnFileProxy: type: " + e.getClass().getName() + ", message:  "+ e.getMessage());
			e.printStackTrace();
		}
	}
}
