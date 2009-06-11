package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
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
					}
				}
				
				/*
				 * Compute new interest. Its similar to a "getNextBlock", but since we want to register it, we
				 * don't do a getNext here. Also we need to set the prefix 1 before the last component
				 * so we get all the blocks
				 */
				_sawBlock = true;
				ContentName nextName = new ContentName(co.name(), co.contentDigest());
				_interest = Interest.constructInterest(nextName,  _daemon.getExcludes(), 
							new Integer(Interest.ORDER_PREFERENCE_LEFT  | Interest.ORDER_PREFERENCE_ORDER_NAME), 
							co.name().count() - 1);
				_interest.additionalNameComponents(2);
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
