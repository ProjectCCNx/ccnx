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

import static org.ccnx.ccn.profiles.CommandMarker.COMMAND_MARKER_REPO_START_WRITE;
import static org.ccnx.ccn.protocol.Component.NONCE;

import java.io.IOException;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.ConcurrencyUtils.Waiter;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Handle repo specialty start/end protocol
 *
 * Needs to be able to handle multiple clients. Currently due to limitations in close,
 * to do this requires that clients above close their streams in order when multiple
 * streams are using the same FC.
 *
 * @see CCNFlowControl
 * @see RepositoryInterestHandler
 */
public class RepositoryFlowControl extends CCNFlowControl implements CCNContentHandler {

	// Outstanding interests output by this FlowController. Currently includes only the original
	// start write request.
	protected HashSet<Interest> _writeInterests = new HashSet<Interest>();

	protected boolean localRepo = true;

	// Queue of current clients of this RepositoryFlowController
	// Implemented as a queue so we can decide which one to close on calls to beforeClose/afterClose
	protected Queue<Client> _clients = new ConcurrentLinkedQueue<Client>();

	/**
	 * Handles packets received from the repository after the start write request.  It's looking
	 * for a RepoInfo packet indicating a repository has responded.
	 */
	public Interest handleContent(ContentObject co,
			Interest interest) {

		if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
			Log.info(Log.FAC_REPO, "handleContent: got potential repo message: {0}", co.name());
		if (co.signedInfo().getType() != ContentType.DATA)
			return null;
		RepositoryInfo repoInfo = new RepositoryInfo();
		try {
			repoInfo.decode(co.content());
			switch (repoInfo.getType()) {
			case INFO:
				for (Client client : _clients) {
					if (client._name.isPrefixOf(co.name())) {
						if (Log.isLoggable(Log.FAC_REPO, Level.FINE))
							Log.fine(Log.FAC_REPO, "Marked client {0} initialized", client._name);
						synchronized (this) {
							client._initialized = true;
							notifyAll();
						}
					}
				}
				break;
			default:
				break;
			}
		} catch (ContentDecodingException e) {
			Log.info(Log.FAC_REPO, "ContentDecodingException parsing RepositoryInfo: {0} from content object {1}, skipping.",  e.getMessage(), co.name());
		}

		return null;
	}

	/**
	 * Preserves information about our clients
	 */
	protected class Client {
		protected ContentName _name;
		protected Shape _shape;
		protected boolean _initialized = false;

		public Client(ContentName name, Shape shape) {
			_name = name;
			_shape = shape;
		}

		public ContentName name() { return _name; }
		public Shape shape() { return _shape; }
	}

	/**
	 * @param handle a CCNHandle - if null one is created
	 * @throws IOException if library is null and a new CCNHandle can't be created
	 */
	public RepositoryFlowControl(CCNHandle handle) throws IOException {
		super(handle);
	}

	/**
	 * constructor to allow the repo flow controller to set the scope for the start write interest
	 *
	 * @param handle a CCNHandle - if null, one is created
	 * @param local boolean to determine if a general start write, or one with the scope set to one.
	 * 			A scope set to one will limit the write to a repo on the local device
	 * @throws IOException
	 */
	public RepositoryFlowControl(CCNHandle handle, boolean local) throws IOException {
		super(handle);
		localRepo = local;
	}

	/**
	 * @param name		an initial namespace for this stream
	 * @param handle	a CCNHandle - if null one is created
	 * @throws IOException if handle is null and a new CCNHandle can't be created
	 */
	public RepositoryFlowControl(ContentName name, CCNHandle handle) throws IOException {
		super(name, handle);
	}

	/**
	 * @param name		an initial namespace for this stream
	 * @param handle	a CCNHandle - if null one is created
	 * @param local boolean to determine if a general start write, or one with the scope set to one.
	 * 			A scope set to one will limit the write to a repo on the local device
	 * @throws IOException if handle is null and a new CCNHandle can't be created
	 */
	public RepositoryFlowControl(ContentName name, CCNHandle handle, boolean local) throws IOException {
		super(name, handle);
		localRepo = local;
	}

	/**
	 * @param name		an initial namespace for this stream
	 * @param handle	a CCNHandle - if null one is created
	 * @param shape		shapes are not currently implemented and may be deprecated. The only currently defined
	 * 					shape is "Shape.STREAM"
	 * @throws IOException	if handle is null and a new CCNHandle can't be created
	 * @see	CCNFlowControl
	 */
	public RepositoryFlowControl(ContentName name, CCNHandle handle, Shape shape) throws IOException {
		super(name, handle);
	}

	/**
	 * @param name		an initial namespace for this stream
	 * @param handle	a CCNHandle - if null one is created
	 * @param shape		shapes are not currently implemented and may be deprecated. The only currently defined
	 * 					shape is "Shape.STREAM"
	 * @param local boolean to determine if a general start write, or one with the scope set to one.
	 * 			A scope set to one will limit the write to a repo on the local device
	 * @throws IOException	if handle is null and a new CCNHandle can't be created
	 * @see	CCNFlowControl
	 */
	public RepositoryFlowControl(ContentName name, CCNHandle handle, Shape shape, boolean local) throws IOException {
		super(name, handle);
		localRepo = local;
	}

	@Override
	/**
	 * Send out a start write request to any listening repositories and wait for a response.
	 *
	 * @param name	the basename of the stream to start
	 * @param shape currently ignored - can only be Shape.STREAM
	 * @throws IOException if there is no response from a repository
	 */
	public void startWrite(ContentName name, Shape shape) throws IOException {

		if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
			Log.info(Log.FAC_REPO, "RepositoryFlowControl.startWrite called for name {0}, shape {1}", name, shape);
		Client client = new Client(name, shape);
		_clients.add(client);

		// A nonce is used because if we tried to write data with the same name more than once, we could retrieve the
		// the previous answer from the cache, and the repo would never be informed of our start write.
		ContentName repoWriteName = new ContentName(name, COMMAND_MARKER_REPO_START_WRITE, NONCE);
		Interest writeInterest = new Interest(repoWriteName);
		if (localRepo || SystemConfiguration.FC_LOCALREPOSITORY) {
			//this is meant to be written to a local repository, not any/multiple connected repos
			writeInterest.scope(1);
		}

		_handle.expressInterest(writeInterest, this);

		synchronized (this) {
			_writeInterests.add(writeInterest);
		}

		//Wait for information to be returned from a repo
		try {
			new Waiter(getTimeout()) {
				@Override
				protected boolean check(Object o, Object check) throws Exception {
					return ((Client)check)._initialized;
				}
			}.wait(this, client);
		} catch (Exception e) {
			Log.warning(Log.FAC_REPO, e.getClass() + " : " + e.getMessage());
		}

		synchronized (this) {
			if (!client._initialized) {
				_clients.remove();
				Log.warning(Log.FAC_REPO, "No response from a repository, cannot add name space : " + name);
				throw new IOException("No response from a repository for " + name);
			}
		}
	}

	/**
	 * Called after close has completed a flush
	 */
	@Override
	public void afterClose() throws IOException {
		try {
			_clients.remove();
		} catch (NoSuchElementException nse) {}

		// super.afterClose() calls waitForPutDrain.
		super.afterClose();
		// DKS don't actually want to cancel all the interests, only the
		// ones relevant to the data we've finished writing.
		cancelInterests();
	}

	/**
	 * Cancel any outstanding interests on close.
	 * TODO - since the flow controller may be used by multiple streams we probably want to use Clients to decide
	 * what interests to cancel.
	 */
	public void cancelInterests() {
		for (Interest writeInterest : _writeInterests){
			_handle.cancelInterest(writeInterest, this);
		}
	}

	/**
	 * Help users determine what type of flow controller this is.
	 */
	@Override
	public SaveType saveType() {
		//if the library is overridden with the property or environment variable
		//for writing to a local repo, need to return LocalRepo save type
		if (SystemConfiguration.FC_LOCALREPOSITORY)
			return SaveType.LOCALREPOSITORY;

		if (localRepo)
			return SaveType.LOCALREPOSITORY;
		else
			return SaveType.REPOSITORY;
	}
}
