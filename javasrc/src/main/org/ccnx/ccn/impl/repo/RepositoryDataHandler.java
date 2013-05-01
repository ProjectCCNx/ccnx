/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.InterestTable;
import org.ccnx.ccn.impl.InterestTable.Entry;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * So the main listener can output interests sooner, we do the data store work
 * in a separate thread.
 */

public class RepositoryDataHandler implements Runnable {
	public static final int THROTTLE_TOP = 2000;
	public static final int THROTTLE_BOTTOM = 1800;

	private final RepositoryServer _server;
	private final Queue<ContentObject> _queue = new ConcurrentLinkedQueue<ContentObject>();
	private final InterestTable<ContentName> _pendingKeyChecks = new InterestTable<ContentName>();
	private boolean _shutdown = false;
	private boolean _shutdownComplete = false;
	protected int _currentQueueSize;
	protected boolean _throttled = false;

	public RepositoryDataHandler(RepositoryServer server) {
		_server = server;
	}

	public void add(ContentObject co) {
		_currentQueueSize++;
		if (!_throttled && _currentQueueSize > THROTTLE_TOP) {
			_throttled = true;
			_server.setThrottle(true);
		}
		_queue.add(co);
		_queue.notify();
	}

	public void addKeyCheck(ContentName target) {
		_pendingKeyChecks.add(new Interest(target), target);
	}

	/**
	 * The content listener runs this thread to store data using the content store.
	 * The thread also checks for policy updates which may reset the repository's
	 * namespace and sends "early" nameEnumerationResponses when requested by the
	 * store.
	 *
	 * @see RepositoryStore
	 */
	public void run() {
		while (!_shutdownComplete) {
			ContentObject co = null;
			do {
				co = _queue.poll();
				if (null == co) {
					if (_shutdown) {
						synchronized (this) {
							_shutdownComplete = true;
							notifyAll();
						}
						return;
					}
					try {
						_queue.wait(SystemConfiguration.MEDIUM_TIMEOUT);
					} catch (InterruptedException e) {}
				}
			} while (null == co);
			_currentQueueSize--;
			if (_throttled && _currentQueueSize < THROTTLE_BOTTOM) {
				_throttled = false;
				_server.setThrottle(false);
			}
			try {
				if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
					Log.finer(Log.FAC_REPO, "Saving content in: " + co.toString());
				}

				NameEnumerationResponse ner = _server.getRepository().saveContent(co);
				if (!_shutdown) {
					if (ner!=null && ner.hasNames()) {
						_server.sendEnumerationResponse(ner);
					}
				}

				// When a write or some syncs are first requested we don't know what key data
				// was being used because this is in the ContentObject which of course we didn't
				// have yet. Bbut we need this data to make sure the key is saved along with the file.
				// Now we can find the key data and check if we have it already or need to get it
				// too. Also the key locator that we dont have yet could have been a link. We
				// didn't know that either. If it was we have to get the data it points to.
				//
				// Also we have to check for more locators associated with our new object
				// and the objects pointed to by the links.
				Entry<ContentName> entry = _pendingKeyChecks.removeMatch(co);
				if (null != entry) {
					ContentName nameToCheck = entry.value();
					if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
						Log.finer(Log.FAC_REPO, "Processing key check entry: {0}", nameToCheck);
					}
					ContentName linkCheck = _server.getLinkedKeyTarget(co);
					if (null != linkCheck) {
						if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
							Log.finer(Log.FAC_REPO, "Processing key check entry for link: {0}", linkCheck);
						}
						Interest linkInterest = new Interest(linkCheck);
						_server.doSync(linkInterest, linkInterest);
						syncKeysForObject(co, linkCheck);
					}
					syncKeysForObject(co, nameToCheck);
				}
			} catch (Exception e) {
				e.printStackTrace();
				Log.logStackTrace(Level.WARNING, e);
			}
		}
	}

	private void syncKeysForObject(ContentObject co, ContentName name) throws RepositoryException, IOException {
		ContentName target = _server.getKeyTargetFromObject(co, name);
		if (null != target) {
			if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
				Log.finer(Log.FAC_REPO, "Fetching key from dataHandler: " + target);
			}
			Interest interest = Interest.constructInterest(target, _server.getExcludes(), 1, 3, null, null);
			addKeyCheck(target);
			_server.doSync(interest, interest);
		}
	}

	public void shutdown() {
		_shutdown = true;
		synchronized (this) {
			while (!_shutdownComplete) {
				try {
					wait(SystemConfiguration.LONG_TIMEOUT);
				} catch (InterruptedException e) {}
			}
		}
	}

	public int getCurrentQueueSize() {
		return _currentQueueSize;
	}
}
