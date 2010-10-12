/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * So the main listener can output interests sooner, we do the data store work
 * in a separate thread.
 */

public class RepositoryDataHandler implements Runnable {
	private RepositoryServer _server;
	private Queue<ContentObject> _queue = new ConcurrentLinkedQueue<ContentObject>();
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
				} catch (Exception e) {
					e.printStackTrace();
					Log.logStackTrace(Level.WARNING, e);
				}
			}
		}
	}
	
	public void shutdown() {
		_shutdown = true;
	}
}
