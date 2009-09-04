package org.ccnx.ccn.impl.repo;

import java.util.ArrayList;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.repo.Repository.NameEnumerationResponse;
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
	private RepositoryDaemon _daemon;
	private CCNHandle _library;
	
	public RepositoryInterestHandler(RepositoryDaemon daemon) {
		_daemon = daemon;
		_library = daemon.getLibrary();
	}

	public int handleInterests(ArrayList<Interest> interests) {
		for (Interest interest : interests) {
			try {
				if (SystemConfiguration.getLogging("repo"))
					Log.finer("Saw interest: " + interest.name());
				if (interest.name().contains(CommandMarkers.REPO_START_WRITE)) {
					startReadProcess(interest);
				} else if (interest.name().contains(CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION)) {
					nameEnumeratorResponse(interest);
				} else {
					ContentObject content = _daemon.getRepository().getContent(interest);
					if (content != null) {
						Log.finest("Satisfying interest: " + interest + " with content " + content.name());
						_library.put(content);
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
		for (RepositoryDataListener listener : _daemon.getDataListeners()) {
			if (listener.getOrigInterest().equals(interest)) {
				Log.info("Write request " + interest.name() + " is a duplicate, ignoring");
				return;
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
		if (_daemon.getPendingNameSpaceState()) {
			Log.info("Discarding write request " + interest.name() + " due to pending namespace change");
			return;
		}
		
		ContentName listeningName = new ContentName(interest.name().count() - 2, interest.name().components());
		try {
			Log.info("Processing write request for " + listeningName);
			Interest readInterest = Interest.constructInterest(listeningName, _daemon.getExcludes(), null);
			RepositoryDataListener listener = _daemon.addListener(interest, readInterest);
			_daemon.getWriter().put(interest.name(), _daemon.getRepository().getRepoInfo(null), null, null,
					_daemon.getFreshness());
			listener.getInterests().add(readInterest, null);
			_library.expressInterest(readInterest, listener);
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
	}
	
	public void nameEnumeratorResponse(Interest interest) {
		NameEnumerationResponse ner = _daemon.getRepository().getNamesWithPrefix(interest);

		if (ner!=null && ner.hasNames()) {
			_daemon.sendEnumerationResponse(ner);
			Log.fine("sending back name enumeration response "+ner.getPrefix());
		} else {
			Log.fine("we are not sending back a response to the name enumeration interest (interest.name() = "+interest.name()+")");
		}
	}
}
