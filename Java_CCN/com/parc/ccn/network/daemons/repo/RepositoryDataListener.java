package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;

/**
 * Handle incoming data in the repository
 * @author rasmusse
 *
 */

public class RepositoryDataListener implements CCNInterestListener {
	private long _timer;
	private Interest _origInterest;
	private Interest _interest;
	private boolean _haveHeader = false;
	private boolean _sentHeaderInterest = false;
	private boolean _sawBlock = false;
	private Interest _headerInterest = null;	
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
			Library.logger().info("Saw data: " + co.name());
			_content = co;
		}
	
		public void run() {
			try {
				Library.logger().finer("Saving content in: " + _content.name().toString());
				_daemon.getRepository().saveContent(_content);		
				if (_daemon.getRepository().checkPolicyUpdate(_content)) {
					_daemon.resetNameSpaceFromHandler();
				}
			} catch (RepositoryException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public RepositoryDataListener(Interest origInterest, Interest interest, RepositoryDaemon daemon) throws XMLStreamException, IOException {
		_origInterest = interest;
		_interest = interest;
		_daemon = daemon;
		_library = daemon.getLibrary();
		_timer = new Date().getTime();
	}
	
	public Interest handleContent(ArrayList<ContentObject> results,
			Interest interest) {
		
		_timer = new Date().getTime();
		
		for (ContentObject co : results) {
			_daemon.getThreadPool().execute(new DataHandler(co));
			
			synchronized (this) {
				if (!_haveHeader) {
					/*
					 * Handle headers specifically. If we haven't seen one yet ask for it specifically
					 */
					if (SegmentationProfile.isUnsegmented(co.name())) {
						_haveHeader = true;
						if (_sawBlock)
							return null;
					} else {
						if (!_sentHeaderInterest) {
							_headerInterest = new Interest(SegmentationProfile.segmentRoot(co.name()));
							_headerInterest.additionalNameComponents(1);
							try {
								_library.expressInterest(_headerInterest, this);
								_sentHeaderInterest = true;
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
						if (!_sawBlock) {
							if (SegmentationProfile.getSegmentNumber(co.name()) == 0)
								_currentBlock = 1;
						}
					}
				}
				
				/*
				 * Compute next interest to ask for. 
				 */
				_sawBlock = true;
				_interest = new Interest(SegmentationProfile.segmentName(co.name(), _currentBlock++));
				return _interest;
			}
		}
		return null;
	}
	
	public void cancelInterests() {
		_library.cancelInterest(_interest, this);
		_library.cancelInterest(_headerInterest, this);
	}
	
	public long getTimer() {
		return _timer;
	}
	
	public void setTimer(long time) {
		_timer = time;
	}
	
	public Interest getInterest() {
		return _interest;
	}
	
	public Interest getOrigInterest() {
		return _origInterest;
	}
	
	
}
