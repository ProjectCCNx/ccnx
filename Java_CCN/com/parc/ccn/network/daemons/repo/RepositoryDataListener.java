package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.TreeMap;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.network.daemons.repo.Repository.NameEnumerationResponse;

/**
 * Handle incoming data in the repository. Currently only handles
 * the stream "shape"
 * 
 * @author rasmusse
 *
 */

public class RepositoryDataListener implements CCNInterestListener {
	private long _timer;
	private Interest _origInterest;
	private ContentName _versionedName;
	private TreeMap<ContentName, Interest> _interests = new TreeMap<ContentName, Interest>();
	private RepositoryDaemon _daemon;
	private CCNLibrary _library;
	private long _currentBlock = 0;
	
	/**
	 * So the main listener can output interests sooner, we do the data creation work
	 * in a separate thread.
	 * 
	 * @author rasmusse
	 *
	 */
	private class DataHandler implements Runnable {
		private ContentObject _content;
		
		private DataHandler(ContentObject co) {
			if (SystemConfiguration.getLogging("repo"))
				Library.logger().info("Saw data: " + co.name());
			_content = co;
		}
	
		public void run() {
			try {
				if (SystemConfiguration.getLogging("repo"))
					Library.logger().finer("Saving content in: " + _content.name().toString());
				NameEnumerationResponse ner = _daemon.getRepository().saveContent(_content);		
				if (_daemon.getRepository().checkPolicyUpdate(_content)) {
					_daemon.resetNameSpaceFromHandler();
				}
				if(ner!=null && ner.names!=null){
					_daemon.sendEnumerationResponse(ner);
				}
			} catch (Exception e) {
				e.printStackTrace();
				Library.logStackTrace(Level.WARNING, e);
			}
		}
	}
	
	public RepositoryDataListener(Interest origInterest, Interest interest, RepositoryDaemon daemon) throws XMLStreamException, IOException {
		_origInterest = interest;
		_versionedName = interest.name();
		_daemon = daemon;
		_library = daemon.getLibrary();
		_timer = new Date().getTime();
		Library.logger().info("Starting up repository listener on original interest: " + origInterest + " interest " + interest);
	}
	
	public Interest handleContent(ArrayList<ContentObject> results,
			Interest interest) {
		
		_timer = new Date().getTime();
		
		for (ContentObject co : results) {
			_daemon.getThreadPool().execute(new DataHandler(co));
			
			if (VersioningProfile.hasTerminalVersion(co.name()) && !VersioningProfile.hasTerminalVersion(_versionedName)) {
				_versionedName = co.name().cut(VersioningProfile.findLastVersionComponent(co.name()) + 1);
			}
				
			if (SegmentationProfile.isSegment(co.name())) {
				long thisBlock = SegmentationProfile.getSegmentNumber(co.name());
				if (thisBlock >= _currentBlock)
					_currentBlock = thisBlock + 1;
				synchronized (_interests) {
					_interests.remove(co.name());
				}
			}
			
			/*
			 * Compute next interests to ask for and ask for them
			 */
			synchronized (_interests) {
				long firstInterestToRequest = _interests.size() > 0 
						? SegmentationProfile.getSegmentNumber(_interests.lastKey()) + 1
						: _currentBlock;
				if (_currentBlock > firstInterestToRequest) // Can happen if last requested interest precedes all others
															// out of order
					firstInterestToRequest = _currentBlock;
				int nOutput = _interests.size() >= _daemon.getWindowSize() ? 0 : _daemon.getWindowSize() - _interests.size();
	
				for (int i = 0; i < nOutput; i++) {
					ContentName name = SegmentationProfile.segmentName(co.name(), firstInterestToRequest + i);
					Interest newInterest = new Interest(name);
					try {
						_library.expressInterest(newInterest, this);
						_interests.put(name, newInterest);
					} catch (IOException e) {
						Library.logStackTrace(Level.WARNING, e);
						e.printStackTrace();
					}
				}
			}
		}
		return null;
	}
	
	public void cancelInterests() {
		for (ContentName name : _interests.keySet())
			_library.cancelInterest(_interests.get(name), this);
	}
	
	public long getTimer() {
		return _timer;
	}
	
	public void setTimer(long time) {
		_timer = time;
	}
	
	public Interest getOrigInterest() {
		return _origInterest;
	}
	
	public ContentName getVersionedName() {
		return _versionedName;
	}
}
