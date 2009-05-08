package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;

/**
 * Handle incoming data in the repository
 * @author rasmusse
 *
 */

public class RepositoryDataListener implements CCNInterestListener {
	private long _timer;
	private Interest _origInterest;
	private Interest _interest;
	private Interest _versionedInterest = null;
	private ArrayList<ContentObject> _unacked = new ArrayList<ContentObject>();
	private boolean _haveHeader = false;
	private boolean _sentHeaderInterest = false;
	private boolean _sawBlock = false;
	private ContentName _headerName = null;
	private Interest _headerInterest = null;
	private ArrayList<Interest> _ackRequests = new ArrayList<Interest>();
	private RepositoryDaemon _daemon;
	private CCNLibrary _library;
	
	public RepositoryDataListener(Interest origInterest, Interest interest, RepositoryDaemon daemon) {
		_origInterest = interest;
		_interest = interest;
		_daemon = daemon;
		_library = daemon.getLibrary();
		_headerName = _interest.name().clone();
		_timer = new Date().getTime();
		_headerInterest = new Interest(_headerName);
		_headerInterest.additionalNameComponents(1);
	}
	
	public Interest handleContent(ArrayList<ContentObject> results,
			Interest interest) {
		synchronized (this) {
			
			for (ContentObject co : results) {
				try {
					handleIncomingData(co);
				} catch (SignatureException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (RepositoryException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
		
				_timer = new Date().getTime();
				
				if (!_haveHeader) {
					/*
					 * Handle headers specifically. If we haven't seen one yet ask for it specifically
					 */
					if (co.name().equals(_headerName)) {
						_haveHeader = true;
						if (_sawBlock)
							return null;
						/*
						 * The first thing we saw was a header. So we don't know yet whether the file is
						 * versioned or not versioned. The returned interest that falls out of this will
						 * ask for data assuming that we are unversioned. But we don't know yet whether
						 * we are versioned or not so specifically try asking for versioned blocks here.
						 */
						_versionedInterest = new Interest(co.name());
						_versionedInterest.additionalNameComponents(2);
						try {
							_library.expressInterest(_versionedInterest, this);
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					} else {
						if (!_sentHeaderInterest) {
							try {
								_library.expressInterest(_headerInterest, this);
								_sentHeaderInterest = true;
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
					}
				} else {
					/*
					 * If we sent out a versioned interest we now know whether or not we are
					 * versioned. We also know that one of the 2 interests we sent out was
					 * answered and the other one wasn't. Rather than figure out which one
					 * was answered we can just cancel them both now. This shouldn't hurt
					 * anything.
					 */
					if (_versionedInterest != null) {
						_library.cancelInterest(_versionedInterest, this);
						_library.cancelInterest(_interest, this);
						_versionedInterest = null;
					}
				}
				
				/*
				 * Compute new interest. Its basically a next, but since we want to register it, we
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
			return null;
		}
	}
	
	private void handleIncomingData(ContentObject co) throws RepositoryException, IOException, SignatureException {
		Library.logger().info("Saw data: " + co.name());
			if (_daemon.getRepository().checkPolicyUpdate(co)) {
				_daemon.resetNameSpace();
			} else {
				Library.logger().finer("Saving content in: " + co.name().toString());
				_daemon.getRepository().saveContent(co);		
			}
			
			/*
			 * If an ack had already been requested answer it now.  Otherwise
			 * add to the unacked queue to get ready for later ack.
			 */
			Iterator<Interest> iterator = _ackRequests.iterator();
			boolean found = false;
			while (iterator.hasNext()) {
				// interest is the actual interest that came in to retrieve an ACK
				Interest ackRequest = iterator.next();
				// dataPrefix is the prefix of names of content objects for which ack response is required
				// so the request marker and nonce must be stripped off 
				ContentName dataPrefix = new ContentName(ackRequest.name().count() - 2, ackRequest.name().components());
				// ackMatch is an internal interest used for matching against individual content objects that we have
				// It is never supposed to be sent out
				Interest ackMatch = new Interest(dataPrefix);

				if (ackMatch.matches(co)) {
					Library.logger().finer("Found waiting ACK request for " + co.name() + " interest " + ackRequest.name());
					iterator.remove();
					if (!found) {
						ArrayList<ContentName> names = new ArrayList<ContentName>();
						names.add(co.name());
						//ContentName putName = new ContentName(data.name(), CCNBase.REPO_REQUEST_ACK);
						_daemon.getWriter().put(ackRequest.name(), _daemon.getRepository().getRepoInfo(names));
					}
					found = true;
				}
			}
			if (!found)
				_unacked.add(co);
	}
	
	public void cancelInterests() {
		_library.cancelInterest(_interest, this);
		_library.cancelInterest(_headerInterest, this);
		if (_versionedInterest != null)
			_library.cancelInterest(_versionedInterest, this);
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
	
	public ArrayList<ContentObject> getUnacked() {
		return _unacked;
	}
	
	public ArrayList<Interest> getAckRequests() {
		return _ackRequests;
	}
}
