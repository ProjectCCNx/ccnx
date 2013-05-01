/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.QueuedContentHandler;
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

public class RepositoryInterestHandler extends QueuedContentHandler<Interest> implements Runnable, CCNInterestHandler {
	private final RepositoryServer _server;
	private final CCNHandle _handle;
	private boolean _shutdown = false;

	public RepositoryInterestHandler(RepositoryServer server) {
		_server = server;
		_handle = server.getHandle();
	}

	public boolean handleInterest(Interest interest) {
		_server._stats.increment(RepositoryServer.StatsEnum.HandleInterest);
		if (Log.isLoggable(Log.FAC_REPO, Level.FINEST))
			Log.finest(Log.FAC_REPO, "Queueing interest: {0}", interest.name());
		add(interest);
		return true;		// In the repository we never want to service an interest again
	}

	/**
	 * Parse incoming interests for type and dispatch those dedicated to some special purpose.
	 * Interests can be to start a write or a name enumeration request.
	 * If the interest has no special purpose, its assumed that it's to actually read data from
	 * the repository and the request is sent to the RepositoryStore to be processed.
	 */
	@Override
	public void process(Interest interest) {

		if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
			Log.finer(Log.FAC_REPO, "Saw interest: {0}", interest.name());
		try {
			if (interest.name().componentStartsWith(CommandMarker.COMMAND_PREFIX)) {
				if (RepositoryOperations.isStartWriteOperation(interest)) {
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestCommands);
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestStartWriteReceived);
					if (allowGenerated(interest)) {
						startWrite(interest);
						_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestStartWriteProcessed);
					} else
						_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestStartWriteIgnored);
				} else if (RepositoryOperations.isNameEnumerationOperation(interest)) {
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestCommands);
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestNameEnumReceived);
					// Note - we are purposely allowing requests with allowGenerated turned off
					// for NE for now. Disallowing it potentially causes problems.
					nameEnumeratorResponse(interest);
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestNameEnumProcessed);
				} else if (RepositoryOperations.isCheckedWriteOperation(interest)) {
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestCommands);
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestCheckedWriteReceived);
					if (allowGenerated(interest)) {
						startWriteChecked(interest);
						_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestCheckedWriteProcessed);
					} else
						_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestCheckedWriteIgnored);
				} else if (RepositoryOperations.isBulkImportOperation(interest)) {
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestCommands);
					_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestBulkImportReceived);
					if (allowGenerated(interest)) {
						addBulkDataToRepo(interest);
						_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestBulkImportReceived);
					} else
						_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestBulkImportIgnored);
				}
			}
			_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestUncategorized);
			ContentObject content = _server.getRepository().getContent(interest);
			if (content != null) {
				if (Log.isLoggable(Log.FAC_REPO, Level.FINEST))
					Log.finest(Log.FAC_REPO, "Satisfying interest: {0} with content {1}", interest, content.name());
				_handle.put(content);
			} else {
				if (Log.isLoggable(Log.FAC_REPO, Level.FINE))
					Log.fine(Log.FAC_REPO, "Unsatisfied interest: {0}", interest);
			}
		} catch (Exception e) {
			_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestErrors);
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
	}

	protected boolean _checkShutdown() {
		return _shutdown;
	}

	protected boolean allowGenerated(Interest interest) {
		if (null != interest.answerOriginKind() && (interest.answerOriginKind() & Interest.ANSWER_GENERATED) == 0)
			return false;	// Request to not answer
		else
			return true;
	}

	/**
	 * Handle start write requests
	 *
	 * @param interest
	 */
	private void startWrite(Interest interest) {

		if (_server.isWriteSuspended(interest)) return;

		// Create the name for the initial interest to retrieve content from the client that it desires to
		// write.  Strip from the write request name (in the incoming Interest) the start write command component
		// and the nonce component to get the prefix for the content to be written.
		ContentName listeningName = interest.name().cut(interest.name().count() - 2);
		try {
			if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
				Log.info(Log.FAC_REPO, "Processing write request for {0}", listeningName);
			// Create the initial read interest.  Set maxSuffixComponents = 2 to get only content with one
			// component past the prefix, plus the implicit digest.  This is designed to retrieve segments
			// of a stream and avoid other content more levels below in the name space.  We do not ask for
			// a specific segment initially so as to support arbitrary starting segment numbers.
			// TODO use better exclude filters to ensure we're only getting segments.
			Interest readInterest = Interest.constructInterest(listeningName, _server.getExcludes(), null, 2, null, null);

			RepositoryInfoObject rio = _server.getRepository().getRepoInfo(interest.name(), null, null);
			if (null == rio)
				return;		// Should have logged an error in getRepoInfo
			// Hand the object the outstanding interest, so it can put its first block immediately.
			rio.save(interest);

			// Check for special case file written to repo
			ContentName globalPrefix = _server.getRepository().getGlobalPrefix();
			if (BasicPolicy.getPolicyName(globalPrefix).isPrefixOf(listeningName)) {
				_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestStartWritePolicyHandlers);
				new RepositoryPolicyHandler(interest, readInterest, _server);
				return;
			}

			RepositoryDataListener listener = null;

			synchronized (_server.getDataListeners()) {
				if (_server.isDuplicateRequest(interest)) return;

				listener = new RepositoryDataListener(interest, readInterest, _server);
				_server.addListener(listener);
				listener.getInterests().add(readInterest, null);
				_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestStartWriteExpressInterest);
				// Get the keys also
				_server.getDataHandler().addKeyCheck(readInterest.name());
			}
			_handle.expressInterest(readInterest, listener);

		} catch (Exception e) {
			_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestStartWriteErrors);
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
		if (_server.isDuplicateRequest(interest)) return;

		if (_server.isWriteSuspended(interest)) return;

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
			ContentName digestFreeTarget = target.parent();
			if (_server.getRepository().hasContent(target)) {
				unverifiedKeyLocator = _server.getKeyTarget(digestFreeTarget);
				if (null == unverifiedKeyLocator) {
					// First impl, no further checks, if we have first segment, assume we have (or are fetching)
					// the whole thing
					// TODO: add better verification:
					// 		find highest segment in the store (probably a new internal interest seeking rightmost)
					//      getContent(): need full object in this case, verify that last segment matches segment name => verified = true
					verified = true;
				} else {
					if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
						Log.info(Log.FAC_REPO, "Checked write not confirmed due to key {0} not saved", unverifiedKeyLocator);
					}
				}
			} else {
				if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
					Log.info(Log.FAC_REPO, "Checked write not confirmed due to original file {0} not saved", digestFreeTarget);
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
				Interest readInterest;
				// If we have an unverifiedKeyLocator here, we need to sync a key, but not the file itself
				// Otherwise we have to start by syncing the file and we will check the keys later (in the
				// DataHandler).
				if (null != unverifiedKeyLocator) {
					interest = Interest.constructInterest(target, _server.getExcludes(), null, 2, null, null);
					readInterest = interest;
				} else {
					// Create the initial read interest.  Set maxSuffixComponents = minSuffixComponents = 1
					// because in this SPECIAL CASE we have the complete name of the first segment.
					// Note: We could in theory just request the digest too, since we have it, but that can
					// confuse the DataHandler because in some cases it can confuse the digest with a segment ID.
					readInterest = Interest.constructInterest(digestFreeTarget, _server.getExcludes(), null, 1, 1, null);
				}
				_server.getDataHandler().addKeyCheck(digestFreeTarget);
				_server.doSync(interest, readInterest);
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
	 * Handle name enumeration requests.  NE responses can potentially take a long time so don't hog the queue - dispatch
	 * these separately.
	 *
	 * @param interest
	 */
	public void nameEnumeratorResponse(Interest interest) {
		SystemConfiguration._systemThreadpool.execute(new NEResponse(interest));
	}

	protected class NEResponse implements Runnable {
		protected Interest _interest;

		protected NEResponse(Interest interest) {
			_interest = interest;
		}

		public void run() {
			NameEnumerationResponse ner = _server.getRepository().getNamesWithPrefix(_interest, _server.getResponseName());

			if (ner!=null && ner.hasNames()) {
				_server.sendEnumerationResponse(ner);
				_server._stats.increment(RepositoryServer.StatsEnum.HandleInterestNameEnumResponses);
				if (Log.isLoggable(Log.FAC_REPO, Level.FINE))
					Log.fine(Log.FAC_REPO, "sending back name enumeration response {0}", ner.getPrefix());
			} else {
				if (Log.isLoggable(Log.FAC_REPO, Level.FINE))
					Log.fine(Log.FAC_REPO, "we are not sending back a response to the name enumeration interest (interest.name() = {0})", _interest.name());
			}
		}
	}

	public void shutdown() {
		_shutdown = true;
	}
}
