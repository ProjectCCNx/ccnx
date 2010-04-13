/**
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

import java.util.logging.Level;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * So the main listener can output interests sooner, we do the data store work
 * in a separate thread.
 */

public class RepositoryDataHandler implements Runnable {
	private ContentObject _content;
	private RepositoryServer _server;
	
	public RepositoryDataHandler(ContentObject co, RepositoryServer server) {
		if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
			Log.info(Log.FAC_REPO, "Saw data: {0}", co.name());
		_content = co;
		_server = server;
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
		try {
			if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
				Log.finer(Log.FAC_REPO, "Saving content in: " + _content.name().toString());
			}
			
			NameEnumerationResponse ner = _server.getRepository().saveContent(_content);
			if (ner!=null && ner.hasNames()) {
				_server.sendEnumerationResponse(ner);
			}
		} catch (Exception e) {
			e.printStackTrace();
			Log.logStackTrace(Level.WARNING, e);
		}
	}
}
