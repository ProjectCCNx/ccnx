/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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
	private RepositoryServer _server;
	private Queue<ContentObject> _queue = new ConcurrentLinkedQueue<ContentObject>();
	private InterestTable<ContentName> _pendingSyncs = new InterestTable<ContentName>();
	private boolean _shutdown = false;
	
	public RepositoryDataHandler(RepositoryServer server) {
		_server = server;
	}
	
	public void add(ContentObject co) {
		synchronized(_queue) {
			_queue.add(co);
			_queue.notify();
		}
	}
	
	public void addSync(ContentName target) {
		_pendingSyncs.add(new Interest(target), target);
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
		while (!_shutdown) {
			ContentObject co = null;
			synchronized (_queue) {
				do {
					co = _queue.poll();
					if (null == co)
						try {
							_queue.wait(SystemConfiguration.MEDIUM_TIMEOUT);
						} catch (InterruptedException e) {}
					if (_shutdown)
						break;
				} while (null == co);
			}
			if (! _shutdown) {
				try {
					if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
						Log.finer(Log.FAC_REPO, "Saving content in: " + co.toString());
					}
					
					NameEnumerationResponse ner = _server.getRepository().saveContent(co);
					if (ner!=null && ner.hasNames()) {
						_server.sendEnumerationResponse(ner);
					}
					
					// When a sync is incomplete, we may not know yet whether it has keys that
					// need syncing. Now we can find this out. Also the key locator that we hadn't
					// synced yet could have been a link. We didn't know that either. If it was we
					// have to sync the data it points to.
					//
					// Also we have to check for more unsynced locators associated with our new object 
					// and the objects pointed to by the links.
					Entry<ContentName> entry = _pendingSyncs.removeMatch(co);
					if (null != entry) {
						ContentName nameToCheck = entry.value();
						if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
							Log.finer(Log.FAC_REPO, "Processing sync entry: {0}", nameToCheck);
						}
						ContentName linkCheck = _server.getLinkedKeyTarget(co);
						if (null != linkCheck) {
							if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
								Log.finer(Log.FAC_REPO, "Processing sync entry for link: {0}", linkCheck);
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
	}
	
	private void syncKeysForObject(ContentObject co, ContentName name) throws RepositoryException, IOException {
		ContentName target = _server.getKeyTargetFromObject(co, name);
		if (null != target) {
			if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
				Log.finer(Log.FAC_REPO, "Fetching key from dataHandler: " + target);
			}
			Interest interest = Interest.constructInterest(target, _server.getExcludes(), 1, 3, null, null);
			addSync(target);
			_server.doSync(interest, interest);
		}
	}
	
	public void shutdown() {
		_shutdown = true;
	}
}
