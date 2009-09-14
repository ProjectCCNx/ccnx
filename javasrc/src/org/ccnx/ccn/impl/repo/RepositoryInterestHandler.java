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

import java.util.ArrayList;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.repo.RepositoryStore.NameEnumerationResponse;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.CommandMarkers;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;


/**
 * 
 * @author rasmusse
 *
 */

public class RepositoryInterestHandler implements CCNFilterListener {
	private RepositoryServer _server;
	private CCNHandle _handle;
	
	public RepositoryInterestHandler(RepositoryServer server) {
		_server = server;
		_handle = server.getHandle();
	}

	public int handleInterests(ArrayList<Interest> interests) {
		for (Interest interest : interests) {
			try {
				if (SystemConfiguration.getLogging("repo"))
					Log.finer("Saw interest: " + interest.name());
				if (interest.name().contains(CommandMarkers.COMMAND_MARKER_REPO_START_WRITE)) {
					startReadProcess(interest);
				} else if (interest.name().contains(CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION)) {
					nameEnumeratorResponse(interest);
				} else {
					ContentObject content = _server.getRepository().getContent(interest);
					if (content != null) {
						Log.finest("Satisfying interest: " + interest + " with content " + content.name());
						_handle.put(content);
					} else {
						Log.fine("Unsatisfied interest: " + interest);
					}
				}
			} catch (Exception e) {
				Log.logStackTrace(Level.WARNING, e);
				e.printStackTrace();
			}
		}
		return interests.size();
	}
	
	private void startReadProcess(Interest interest) throws XMLStreamException {
		synchronized (_server.getDataListeners()) {
			for (RepositoryDataListener listener : _server.getDataListeners()) {
				if (listener.getOrigInterest().equals(interest)) {
					Log.info("Write request " + interest.name() + " is a duplicate, ignoring");
					return;
				}
			}
		}
		
		/*
		 * For now we need to wait until all current sessions are complete before a namespace
		 * change which will reset the filters is allowed. So for now, we just don't allow any
		 * new sessions to start until a pending namespace change is complete to allow there to
		 * be space for this to actually happen. In theory we should probably figure out a way
		 * to allow new sessions that are within the new namespace to start but figuring out all
		 * the locking/startup issues surrounding this is complex so for now we just don't allow it.
		 */
		if (_server.getPendingNameSpaceState()) {
			Log.info("Discarding write request " + interest.name() + " due to pending namespace change");
			return;
		}
		
		ContentName listeningName = new ContentName(interest.name().count() - 2, interest.name().components());
		try {
			Log.info("Processing write request for " + listeningName);
			Interest readInterest = Interest.constructInterest(listeningName, _server.getExcludes(), null);
			RepositoryDataListener listener = _server.addListener(interest, readInterest);
			_server.getWriter().put(interest.name(), _server.getRepository().getRepoInfo(null), null, null,
					_server.getFreshness());
			listener.getInterests().add(readInterest, null);
			_handle.expressInterest(readInterest, listener);
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
	}
	
	public void nameEnumeratorResponse(Interest interest) {
		NameEnumerationResponse ner = _server.getRepository().getNamesWithPrefix(interest);

		if (ner!=null && ner.hasNames()) {
			_server.sendEnumerationResponse(ner);
			Log.fine("sending back name enumeration response "+ner.getPrefix());
		} else {
			Log.fine("we are not sending back a response to the name enumeration interest (interest.name() = "+interest.name()+")");
		}
	}
}
