/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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
import java.util.ArrayList;
import java.util.logging.Level;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.repo.RepositoryInfo.RepositoryInfoObject;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.profiles.repo.RepositoryOperations;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;


/**
 * Handles interests matching the repository's namespace.
 * 
 * @see RepositoryServer
 * @see RepositoryFlowControl
 * @see RepositoryDataListener
 */

public class RepositoryInterestHandler implements CCNFilterListener {
	private RepositoryServer _server;
	private CCNHandle _handle;
	
	public RepositoryInterestHandler(RepositoryServer server) {
		_server = server;
		_handle = server.getHandle();
	}

	/**
	 * Parse incoming interests for type and dispatch those dedicated to some special purpose.
	 * Interests can be to start a write or a name enumeration request.
	 * If the interest has no special purpose, its assumed that it's to actually read data from
	 * the repository and the request is sent to the RepositoryStore to be processed.
	 */
	public boolean handleInterest(Interest interest) {
		if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
			Log.finer(Log.FAC_REPO, "Saw interest: {0}", interest.name());
		try {
			if (RepositoryOperations.isStartWriteOperation(interest)) {
				if (!allowGenerated(interest)) return true;
				startWrite(interest);
			} else if (RepositoryOperations.isNameEnumerationOperation(interest)) {
				if (!allowGenerated(interest)) return true;
				nameEnumeratorResponse(interest);
			} else if (RepositoryOperations.isCheckedWriteOperation(interest)) {
				if (!allowGenerated(interest)) return true;
				startWriteChecked(interest);				
			} else if (RepositoryOperations.isBulkImportOperation(interest)) {
				if (!allowGenerated(interest)) return true;
				addBulkDataToRepo(interest);				
			} else {
				ContentObject content = _server.getRepository().getContent(interest);
				if (content != null) {
					if (Log.isLoggable(Log.FAC_REPO, Level.FINEST))
						Log.finest(Log.FAC_REPO, "Satisfying interest: {0} with content {1}", interest, content.name());
					_handle.put(content);
				} else {
					if (Log.isLoggable(Log.FAC_REPO, Level.FINE))
						Log.fine(Log.FAC_REPO, "Unsatisfied interest: {0}", interest);
				}
			}
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
		return true;
	}

	protected boolean allowGenerated(Interest interest) {
		if (null != interest.answerOriginKind() && (interest.answerOriginKind() & Interest.ANSWER_GENERATED) == 0)
			return false;	// Request to not answer
		else
			return true;
	}

	/**
	 * Check for duplicate request, i.e. request already in process
	 * Logs the request if found to be a duplicate.
	 * @param interest the incoming interest containing the request command
	 * @return true if request is duplicate
	 */
	protected boolean isDuplicateRequest(Interest interest) {
		synchronized (_server.getDataListeners()) {
			for (RepositoryDataListener listener : _server.getDataListeners()) {
				if (listener.getOrigInterest().equals(interest)) {
					if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
						Log.info(Log.FAC_REPO, "Request {0} is a duplicate, ignoring", interest.name());
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Check whether new writes are allowed now
	 * Logs the discarded request if it cannot be processed
	 * @param interest the incoming interest containing the command
	 * @return true if writes are presently suspended
	 */
	protected boolean isWriteSuspended(Interest interest) {
		// For now we need to wait until all current sessions are complete before a namespace
		 // change which will reset the filters is allowed. So for now, we just don't allow any
		 // new sessions to start until a pending namespace change is complete to allow there to
		 // be space for this to actually happen. In theory we should probably figure out a way
		 // to allow new sessions that are within the new namespace to start but figuring out all
		 // the locking/startup issues surrounding this is complex so for now we just don't allow it.
		if (_server.getPendingNameSpaceState()) {
			if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
				Log.info(Log.FAC_REPO, "Discarding write request {0} due to pending namespace change", interest.name());
			return true;
		}
		return false;
	}

	/**
	 * Handle start write requests
	 * 
	 * @param interest
	 */
	private void startWrite(Interest interest) {
		if (isDuplicateRequest(interest)) return;
			
		if (isWriteSuspended(interest)) return;
		
		// Create the name for the initial interest to retrieve content from the client that it desires to 
		// write.  Strip from the write request name (in the incoming Interest) the start write command component
		// and the nonce component to get the prefix for the content to be written.
		ContentName listeningName = new ContentName(interest.name().count() - 2, interest.name().components());
		try {
			if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
				Log.info(Log.FAC_REPO, "Processing write request for {0}", listeningName);
			// Create the initial read interest.  Set maxSuffixComponents = 2 to get only content with one 
			// component past the prefix, plus the implicit digest.  This is designed to retrieve segments
			// of a stream and avoid other content more levels below in the name space.  We do not ask for 
			// a specific segment initially so as to support arbitrary starting segment numbers.
			// TODO use better exclude filters to ensure we're only getting segments.
			Interest readInterest = Interest.constructInterest(listeningName, _server.getExcludes(), null, 2, null, null);
			RepositoryDataListener listener;
			
			RepositoryInfoObject rio = _server.getRepository().getRepoInfo(interest.name(), null, null);
			if (null == rio)
				return;		// Should have logged an error in getRepoInfo
			// Hand the object the outstanding interest, so it can put its first block immediately.
			rio.save(interest);
			
			// Check for special case file written to repo
			ContentName globalPrefix = _server.getRepository().getGlobalPrefix();
			String localName = _server.getRepository().getLocalName();
			if (BasicPolicy.getPolicyName(globalPrefix, localName).isPrefixOf(listeningName)) {
				new RepositoryPolicyHandler(interest, readInterest, _server);
				return;
			}
			
			listener = new RepositoryDataListener(interest, readInterest, _server);
			_server.addListener(listener);
			listener.getInterests().add(readInterest, null);
			_handle.expressInterest(readInterest, listener);
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
	}

	/**
	 * Handle requests to check for specific stream content and read and store it if not
	 * already present.
	 * @param interest the interest containing the request
	 * @throws RepositoryException
	 * @throws ContentEncodingException
	 * @throws IOException
	 */
	private void startWriteChecked(Interest interest) {
		if (isDuplicateRequest(interest)) return;
		
		if (isWriteSuspended(interest)) return;

		try {
			if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
				Log.finer(Log.FAC_REPO, "Repo checked write request: {0}", interest.name());
			if (!RepositoryOperations.verifyCheckedWrite(interest)) {
				Log.warning(Log.FAC_REPO, "Repo checked write malformed request {0}", interest.name());
				return;
			}
			
			ContentName target = RepositoryOperations.getCheckedWriteTarget(interest);

			boolean verified = false;
			RepositoryInfoObject rio = null;
			ContentName unverifiedKeyLocator = null;
			if (_server.getRepository().hasContent(target)) {
				unverifiedKeyLocator = _server.getKeyTarget(target);
				if (null == unverifiedKeyLocator) {
					// First impl, no further checks, if we have first segment, assume we have (or are fetching) 
					// the whole thing
					// TODO: add better verification:
					// 		find highest segment in the store (probably a new internal interest seeking rightmost)
					//      getContent(): need full object in this case, verify that last segment matches segment name => verified = true
					verified = true;
				}		
			}
			if (verified) {
				// Send back a RepositoryInfoObject that contains a confirmation that content is already in repo
				if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
					Log.finer(Log.FAC_REPO, "Checked write confirmed");
				ArrayList<ContentName> target_names = new ArrayList<ContentName>();
				target_names.add(target);
				rio = _server.getRepository().getRepoInfo(interest.name(), null, target_names);
			} else {
				// Send back response that does not confirm content
				if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
					Log.finer(Log.FAC_REPO, "Checked write not confirmed");
				rio = _server.getRepository().getRepoInfo(interest.name(), null, null);
			}
			if (null == rio)
				return;		// Should have logged an error in getRepoInfo					
			// Hand the object the outstanding interest, so it can put its first block immediately.
			rio.save(interest);

			if (!verified) {
				_server.doSync(interest, target);
			} else {
				if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
					Log.finer(Log.FAC_REPO, "Repo checked write content verified for {0}", interest.name());
			}
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
	}
	
	/**
	 * Add to the repository via file based on interest request
	 * @param interest
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws RepositoryException
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 */
	private void addBulkDataToRepo(Interest interest) throws ContentEncodingException, IOException {
		int i = CommandMarker.COMMAND_MARKER_REPO_ADD_FILE.findMarker(interest.name());
		if (i >= 0) {
			String[] args = CommandMarker.getArguments(interest.name().component(i));
			String result = "OK";
			if (null != args && args.length > 0) {
				try {
					if (!_server.getRepository().bulkImport(args[0]))
						return;		// reexpression - ignore
				} catch (RepositoryException e) {
					Log.warning(Log.FAC_REPO, "Bulk import error : " + e.getMessage());
					result = e.getMessage();
				}
				RepositoryInfoObject rio = _server.getRepository().getRepoInfo(interest.name(), result, null);
				rio.save(interest);
			}
		}
	}
	
	/**
	 * Handle name enumeration requests
	 * 
	 * @param interest
	 */
	public void nameEnumeratorResponse(Interest interest) {
		NameEnumerationResponse ner = _server.getRepository().getNamesWithPrefix(interest, _server.getResponseName());

		if (ner!=null && ner.hasNames()) {
			_server.sendEnumerationResponse(ner);
			if (Log.isLoggable(Log.FAC_REPO, Level.FINE))
				Log.fine(Log.FAC_REPO, "sending back name enumeration response {0}", ner.getPrefix());
		} else {
			if (Log.isLoggable(Log.FAC_REPO, Level.FINE))
				Log.fine(Log.FAC_REPO, "we are not sending back a response to the name enumeration interest (interest.name() = {0})", interest.name());
		}
	}
}
