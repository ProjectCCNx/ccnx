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

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.CCNVersionedOutputStream;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.LinkCycleException;
import org.ccnx.ccn.io.NoMatchingContentFoundException;
import org.ccnx.ccn.io.CCNAbstractInputStream.FlagTypes;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.versioning.VersionNumber;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Extends a NetworkObject to add specifics for using a CCN-based backing store. Each time
 * the object is saved creates a new CCN version. Readers can open a specific version or
 * not specify a version, in which case the latest available version is read. Defaults
 * allow for saving data to a repository or directly to the network.
 *
 * Need to support four use models:
 * dimension 1: synchronous - ask for and block, the latest version or a specific version
 * dimension 2: asynchronous - ask for and get in the background, the latest version or a specific
 *   version
 * When possible, keep track of the latest version known so that the latest version queries
 * can attempt to do better than that. Start by using only in the background load case, as until
 * something comes back we can keep using the old one and the propensity for blocking is high.
 *
 * Support for subclasses or users specifying different flow controllers with
 * different behavior. Build in support for either the simplest standard flow
 * controller, or a standard repository-backed flow controller.
 *
 * These objects attempt to maintain a CCN copy of the current state of their data. In descriptions
 * below, an object that is "dirty" is one whose data has been modified locally, but not yet
 * saved to the network.
 *
 * While CCNNetworkObject could be used directly, it almost never is; it is usually
 * more effective to define a subclass specialized to save/retrieve a specific object
 * type.
 *
 * Updates, 12/09: Move to creating a flow controller in the write constructor if
 * one isn't passed in. Read constructors still lazily create flow controllers on
 * first write (tradeoff); preemptive construction (and registering for interests)
 * can be achieved by calling the setupSave() method which creates a flow controller
 * if one hasn't been created already. Move to a strong default of saving
 * to a repository, unless overridden by the subclass itself. Change of repository/raw
 * nature can be made with the setRawSave() and setRepositorySave() methods.
 *
 * TODO: Note that the CCNNetworkObject class hierarchy currently has a plethora of constructors.
 * It is also missing some important functionality -- encryption, the ability to specify
 * freshness, and so on. Expect new constructors to deal with the latter deficiencies, and
 * a cleanup of the constructor architecture overall in the near term.
 */
public abstract class CCNNetworkObject<E> extends NetworkObject<E> implements CCNContentHandler {

	protected static final byte [] GONE_OUTPUT = "GONE".getBytes();

	/**
	 * Unversioned "base" name.
	 */
	protected ContentName _baseName;

	/**
	 * The most recent version we have read/written.
	 */
	protected byte [] _currentVersionComponent;

	/**
	 * Cached versioned name.
	 */
	protected ContentName _currentVersionName;

	/**
	 * Flag to indicate whether content has been explicitly marked as GONE
	 * in the latest version we know about. Use an explicit flag to separate from
	 * the option for valid null content, or content that has not yet been updated.
	 */
	protected boolean _isGone = false;

	/**
	 * The first segment for the stored data
	 */
	protected ContentObject _firstSegment = null;

	/**
	 * If the name we started with was actually a link, detect that, store the link,
	 * and dereference it to get the content. Call updateLink() to update the link
	 * itself, and if updated, to update the dereferenced value.
	 *
	 * If the initial link is a link, recursion should push that into the link of
	 * this LinkObject, and read its data. If that is a link, it should push again --
	 * this should chain through links till we reach an object of the desired type,
	 * or blow up. (It won't handle encrypted links, though; we may need to distinguish
	 * between ENCR and ENCRL. Having encrypted links would be handy, to send people
	 * off in random directions. But it matters a lot to be able to tell if the decryption
	 * is a LINK or not.)
	 *
	 * Writing linked objects is better done by separately writing the object and
	 * the link, as it gives you more control over what is happening. If you attempt
	 * to save this object, it may break the link (as the link may link to the particular
	 * version retrieved). You can use this inner link object to manually update the link
	 * to the target; but there are no good defaults about how to update the data. So
	 * you need to specify the new link value yourself. For now we don't prevent users
	 * from getting their data and their links de-syncrhonized.
	 */
	protected LinkObject _dereferencedLink;

	protected PublisherPublicKeyDigest _currentPublisher;
	protected KeyLocator _currentPublisherKeyLocator;
	protected CCNHandle _handle;

	/**
	 * We are not allowed to register or deregister prefixes for flow controllers we didn't
	 * create.
	 */
	protected CCNFlowControl _flowControl;
	protected boolean _FCIsOurs = false;
	protected boolean _disableFlowControlRequest = false;

	protected PublisherPublicKeyDigest _publisher; // publisher we write under, if null, use handle defaults
	protected KeyLocator _keyLocator; // locator to find publisher key
	protected SaveType _saveType = null; // what kind of flow controller to make if we don't have one
	protected Integer _freshnessSeconds = null; // if we want to set short freshness
	protected ContentKeys _keys;
	protected ContentVerifier _verifier;

	/**
	 *  Controls ongoing update.
	 */
	Interest _currentInterest = null;
	boolean _continuousUpdates = false;
	HashSet<UpdateListener> _updateListeners = null;

	protected Updater _updater = null;

	/**
	 * Basic write constructor. This will set the object's internal data but it will not save it
	 * until save() is called. Unless overridden by the subclass, will default to save to
	 * a repository. Can be changed to save directly to the network using setRawSave().
	 * If a subclass sets the default behavior to raw saves, this can be overridden on a
	 * specific instance using setRepositorySave().
	 * @param type Wrapped class type.
	 * @param contentIsMutable is the wrapped class type mutable or not
	 * @param name Name under which to save object.
	 * @param data Data to save.
	 * @param handle CCNHandle to use for network operations. If null, a new one is created using CCNHandle#open().
	 * @throws IOException If there is an error setting up network backing store.
	 */
	public CCNNetworkObject(Class<E> type, boolean contentIsMutable,
							ContentName name, E data, SaveType saveType, CCNHandle handle) throws IOException {
		this(type, contentIsMutable, name, data, saveType, null, null, handle);
	}

	/**
	 * Basic write constructor. This will set the object's internal data but it will not save it
	 * until save() is called. Unless overridden by the subclass, will default to save to
	 * a repository. Can be changed to save directly to the network using setRawSave().
	 * If a subclass sets the default behavior to raw saves, this can be overridden on a
	 * specific instance using setRepositorySave().
	 * @param type Wrapped class type.
	 * @param contentIsMutable is the wrapped class type mutable or not
	 * @param name Name under which to save object.
	 * @param data Data to save.
	 * @param raw If true, saves to network by default, if false, saves to repository by default.
	 * @param publisher The key to use to sign this data, or our default if null.
	 * @param locator The key locator to use to let others know where to get our key.
	 * @param handle CCNHandle to use for network operations. If null, a new one is created using CCNHandle#open().
	 * @throws IOException If there is an error setting up network backing store.
	 */
	public CCNNetworkObject(Class<E> type, boolean contentIsMutable,
							ContentName name, E data, SaveType saveType,
							PublisherPublicKeyDigest publisher, KeyLocator locator,
							CCNHandle handle) throws IOException {
		super(type, contentIsMutable, data);
		if (null == handle) {
			try {
				handle = CCNHandle.open();
			} catch (ConfigurationException e) {
				throw new IllegalArgumentException("handle null, and cannot create one: " + e.getMessage(), e);
			}
		}
		_updater = new Updater(this);
		_handle = handle;
		_verifier = handle.defaultVerifier();
		_baseName = name;
		_publisher = publisher;
		_keyLocator = locator;
		_saveType = saveType;
		// Make our flow controller and register interests for our base name, if we have one.
		// Otherwise, create flow controller when we need one.
		if (null != name) {
			createFlowController();
		}
	}

	/**
	 * Specialized constructor, allowing subclasses to override default flow controller
	 * (and hence backing store) behavior.
	 * @param type Wrapped class type.
	 * @param contentIsMutable is the wrapped class type mutable or not
	 * @param name Name under which to save object.
	 * @param data Data to save.
	 * @param publisher The key to use to sign this data, or our default if null.
	 * @param locator The key locator to use to let others know where to get our key.
	 * @param flowControl Flow controller to use. A single flow controller object
	 *   is used for all this instance's writes, we use underlying streams to call
	 *   CCNFlowControl#startWrite(ContentName, Shape) on each save. Calls to
	 *   setRawSave() and setRepositorySave() will replace this flow controller
	 *   with a raw or repository flow controller, and should not be used with
	 *   this type of object (which obviously cares about what flow controller to use).
	 * @throws IOException If there is an error setting up network backing store.
	 */
	protected CCNNetworkObject(Class<E> type, boolean contentIsMutable,
								ContentName name, E data,
								PublisherPublicKeyDigest publisher,
								KeyLocator locator,
								CCNFlowControl flowControl) throws IOException {
		super(type, contentIsMutable, data);
		_baseName = name;
		_publisher = publisher;
		_keyLocator = locator;
		if (null == flowControl) {
			throw new IOException("FlowControl cannot be null!");
		}
		_updater = new Updater(this);
		_flowControl = flowControl;
		_handle = _flowControl.getHandle();
		_saveType = _flowControl.saveType();
		_verifier = _handle.defaultVerifier();
	}

	/**
	 * Read constructor. Will try to pull latest version of this object, or a specific
	 * named version if specified in the name. If read times out, will leave object in
	 * its uninitialized state.
	 * @param type Wrapped class type.
	 * @param contentIsMutable is the wrapped class type mutable or not
	 * @param name Name from which to read the object. If versioned, will read that specific
	 * 	version. If unversioned, will attempt to read the latest version available.
	 * @param handle CCNHandle to use for network operations. If null, a new one is created using CCNHandle#open().
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	public CCNNetworkObject(Class<E> type, boolean contentIsMutable,
							ContentName name, CCNHandle handle)
				throws ContentDecodingException, IOException {
		this(type, contentIsMutable, name,
			(PublisherPublicKeyDigest)null, handle);
	}

	/**
	 * Read constructor. Will try to pull latest version of this object, or a specific
	 * named version if specified in the name. If read times out, will leave object in
	 * its uninitialized state.
	 *
	 * @param type Wrapped class type.
	 * @param contentIsMutable is the wrapped class type mutable or not
	 * @param name Name from which to read the object. If versioned, will read that specific
	 * 	version. If unversioned, will attempt to read the latest version available.
	 * @param publisher Particular publisher we require to have signed the content, or null for any publisher.
	 * @param flowControl Flow controller to use. A single flow controller object
	 *   is used for all this instance's writes, we use underlying streams to call
	 *   CCNFlowControl#startWrite(ContentName, Shape) on each save.
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	protected CCNNetworkObject(Class<E> type, boolean contentIsMutable,
							  ContentName name,
							  PublisherPublicKeyDigest publisher,
							  CCNFlowControl flowControl)
				throws ContentDecodingException, IOException {
		super(type, contentIsMutable);
		if (null == flowControl) {
			throw new IOException("FlowControl cannot be null!");
		}
		_updater = new Updater(this);
		_flowControl = flowControl;
		_handle = _flowControl.getHandle();
		_saveType = _flowControl.saveType();
		_verifier = _handle.defaultVerifier();
		update(name, publisher);
	}

	/**
	 * Read constructor. Will try to pull latest version of this object, or a specific
	 * named version if specified in the name. If read times out, will leave object in
	 * its uninitialized state.
	 *
	 * @param type Wrapped class type.
	 * @param contentIsMutable is the wrapped class type mutable or not
	 * @param name Name from which to read the object. If versioned, will read that specific
	 * 	version. If unversioned, will attempt to read the latest version available.
	 * @param publisher Particular publisher we require to have signed the content, or null for any publisher.
	 * @param handle CCNHandle to use for network operations. If null, a new one is created using CCNHandle#open().
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	public CCNNetworkObject(Class<E> type, boolean contentIsMutable,
							ContentName name, PublisherPublicKeyDigest publisher,
							CCNHandle handle)
			throws ContentDecodingException, IOException {
		super(type, contentIsMutable);
		if (null == handle) {
			try {
				handle = CCNHandle.open();
			} catch (ConfigurationException e) {
				throw new IllegalArgumentException("handle null, and cannot create one: " + e.getMessage(), e);
			}
		}
		_updater = new Updater(this);
		_handle = handle;
		_verifier = handle.defaultVerifier();
		_baseName = name;
		update(name, publisher);
	}

	/**
	 * Read constructor if you already have a segment of the object. Used by streams.
	 * @param type Wrapped class type.
	 * @param contentIsMutable is the wrapped class type mutable or not
	 * @param firstSegment First segment of the object, retrieved by other means.
	 * @param raw If true, defaults to raw network writes, if false, repository writes.
	 * @param handle CCNHandle to use for network operations. If null, a new one is created using CCNHandle#open().
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	public CCNNetworkObject(Class<E> type, boolean contentIsMutable,
							ContentObject firstSegment, CCNHandle handle)
			throws ContentDecodingException, IOException {
		super(type, contentIsMutable);
		if (null == handle) {
			try {
				handle = CCNHandle.open();
			} catch (ConfigurationException e) {
				throw new IllegalArgumentException("handle null, and cannot create one: " + e.getMessage(), e);
			}
		}
		_updater = new Updater(this);
		_handle = handle;
		_verifier = handle.defaultVerifier();
		update(firstSegment);
	}

	/**
	 * Read constructor if you already have a segment of the object. Used by streams.
	 * @param type Wrapped class type.
	 * @param contentIsMutable is the wrapped class type mutable or not
	 * @param firstSegment First segment of the object, retrieved by other means.
	 * @param flowControl Flow controller to use. A single flow controller object
	 *   is used for all this instance's writes, we use underlying streams to call
	 *   CCNFlowControl#startWrite(ContentName, Shape) on each save.
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	protected CCNNetworkObject(Class<E> type, boolean contentIsMutable,
							   ContentObject firstSegment,
							   CCNFlowControl flowControl)
					throws ContentDecodingException, IOException {
		super(type, contentIsMutable);
		if (null == flowControl)
			throw new IllegalArgumentException("flowControl cannot be null!");
		_updater = new Updater(this);
		_flowControl = flowControl;
		_handle = _flowControl.getHandle();
		_saveType = _flowControl.saveType();
		_verifier = _handle.defaultVerifier();
		update(firstSegment);
	}

	/**
	 * Copy constructor. Handle it piece by piece, though it means
	 * updating this whenever the structure changes (rare).
	 */
	protected CCNNetworkObject(Class<E> type, CCNNetworkObject<? extends E> other) {
		super(type, other);
		_baseName = other._baseName;
		_currentVersionComponent = other._currentVersionComponent;
		_currentVersionName = other._currentVersionName;
		_isGone = other._isGone;
		_currentPublisher = other._currentPublisher;
		_currentPublisherKeyLocator = other._currentPublisherKeyLocator;
		_handle = other._handle;
		_flowControl = other._flowControl;
		_disableFlowControlRequest = other._disableFlowControlRequest;
		_publisher = other._publisher;
		_keyLocator = other._keyLocator;
		_saveType = other._saveType;
		_keys = (null != other._keys) ? other._keys.clone() : null;
		_firstSegment = other._firstSegment;
		_verifier = other._verifier;
		_updater = new Updater(this);
		// Do not copy update behavior. Even if other one is updating, we won't
		// pick that up. Have to kick off manually
	}

	/**
	 * Maximize laziness of flow controller creation, to make it easiest for client code to
	 * decide how to store this object.
	 * When we create the flow controller, we add the base name namespace, so it will respond
	 * to requests for latest version. Create them immediately in write constructors,
	 * when we have a strong expectation that we will save data, if we have a namespace
	 * to start listening on. Otherwise wait till we are going to write.
	 * @return
	 * @throws IOException
	 */
	protected synchronized void createFlowController() throws IOException {
		if (null == _flowControl) {
			if (null == _saveType) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINER))
					Log.finer(Log.FAC_IO, "Not creating flow controller yet, no saveType set.");
				return;
			}
			switch (_saveType) {
			case RAW:
				_flowControl = new CCNFlowControl(_handle);
				break;
			case REPOSITORY:
				_flowControl = new RepositoryFlowControl(_handle, false);
				break;
			case LOCALREPOSITORY:
				_flowControl = new RepositoryFlowControl(_handle, true);
				break;
			default:
				throw new IOException("Unknown save type: " + _saveType);
			}
			_FCIsOurs = true;

			if (_disableFlowControlRequest)
				_flowControl.disable();
			// Have to register the version root. If we just register this specific version, we won't
			// see any shorter interests -- i.e. for get latest version.
			//_flowControl.addNameSpace(_baseName);
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "Created " + _saveType + " flow controller, for prefix {0}, save type " + _flowControl.saveType(), _baseName);
		}
	}

	/**
	 * Get the flow controller associated with this object
	 * @return the flow controller or null if not assigned
	 */
	public CCNFlowControl getFlowControl() {
		return _flowControl;
	}

	/**
	 * Get timeout associated with this object
	 * @return
	 */
	public long getTimeout() {
		return _flowControl.getTimeout();
	}

	/**
	 * Start listening to interests on our base name, if we aren't already.
	 * @throws IOException
	 */
	public synchronized void setupSave(SaveType saveType) throws IOException {
		setSaveType(saveType);
		setupSave();
	}

	public synchronized void setupSave() throws IOException {
		if (null != _flowControl) {
			return;
		}
		createFlowController();
	}

	/**
	 * Finalizer. Somewhat dangerous, but currently best way to close
	 * lingering open registrations. Can't close the handle, till we ref count.
	 */
	@Override
	protected void finalize() throws Throwable {
		try {
			close();        // close the object, canceling interests and listeners.
		} finally {
			super.finalize();
		}
	}

	/**
	 * Close flow controller, remove listeners. Have to call setupSave to save with this object again,
	 * re-add listeners.
	 * @return
	 */
	public synchronized void close() {
		cancelInterest();
		clearListeners();
		if (null != _flowControl) {
			_flowControl.close();
		}
	}

	public SaveType saveType() { return _saveType; }

	/**
	 * Used by subclasses to specify a mandatory save type in
	 * read constructors. Only works on objects whose flow
	 * controller has not yet been set, to not override
	 * manually-set FC's.
	 */
	protected void setSaveType(SaveType saveType) throws IOException {
		if (null == _flowControl) {
			_saveType = saveType;
		} else if (saveType != _saveType){
			throw new IOException("Cannot change save type, flow controller already set!");
		}
	}

	/**
	 * If you want to set the lifetime of objects saved with this instance.
	 * @param freshnessSeconds If null, will unset any freshness seconds (will
	 * write objects that stay in cache till forced out); if a value will constrain
	 * how long objects will stay in cache.
	 */
	public void setFreshnessSeconds(Integer freshnessSeconds) {
		_freshnessSeconds = freshnessSeconds;
	}

	/**
	 * Override point where subclasses can modify each input stream before
	 * it is read. Subclasses should at least set the flags using getInputStreamFlags,
	 * or call super.setInputStreamProperties.
	 */
	protected void setInputStreamProperties(CCNInputStream inputStream) {
		// default -- just set any flags
		inputStream.setFlags(getInputStreamFlags());
	}

	/**
	 * Override point where subclasses can specify set of flags on input stream
	 * at point it is read or where necessary created.
	 * @return
	 */
	protected EnumSet<FlagTypes> getInputStreamFlags() {
		return null;
	}

	/**
	 * Allow verifier to be specified. Could put this in the constructors; though they
	 * are already complicated enough. If not set, the default verifier for the key manager
	 * used by the object's handle is used.
	 * @param verifier the verifier to use. Cannot be null.
	 */
	public void setVerifier(ContentVerifier verifier) {
		if (null != verifier)
			_verifier = verifier;
	}

	/**
	 * Attempts to find a version after the latest one we have, or times out. If
	 * it times out, it simply leaves the object unchanged.
	 * @return returns true if it found an update, false if not
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	public boolean update(long timeout) throws ContentDecodingException, IOException {
		if (null == _baseName) {
			throw new IllegalStateException("Cannot retrieve an object without giving a name!");
		}
		// Look for first segment of version after ours, or first version if we have none.
		ContentObject firstSegment =
			VersioningProfile.getFirstBlockOfLatestVersion(getVersionedName(), null, null, timeout,
					_handle.defaultVerifier(), _handle);
		if (null != firstSegment) {
			return update(firstSegment);
		}
		return false;
	}

	/**
	 * The regular update does a call to do multi-hop get latest version -- i.e. it will try
	 * multiple times to find the latest version of a piece of content, even if interposed caches
	 * have something older. While that's great when you really need the latest, sometimes you are
	 * happy with the latest available version available in your local ccnd cache; or you really
	 * know there is only one version available and you don't want to try multiple times (and incur
	 * a timeout) in an attempt to get a later version that does not exist. This call, updateAny,
	 * claims to get "any" version available. In reality, it will do a single-hop latest version;
	 * i.e. if there are two versions say in your local ccnd cache (or repo with nothing in the ccnd
	 * cache), it will pull the later one. But
	 * it won't move beyond those to find a newer version available at a writer, or to find a later
	 * version in the repo than one in the ccnd cache. Use this if you know there is only one version
	 * of something, or you want a fast path to the latest version where it really doesn't have to
	 * be the "absolute" latest.
	 *
	 * Like all update methods, it will start from the version you've got -- so it is guaranteed to find
	 * something after the current version this object knows about (if it has already found something),
	 * and to time out and return false if there isn't anything later.
	 */
	public boolean updateAny(long timeout) throws ContentDecodingException, IOException {
		if (null == _baseName) {
			throw new IllegalStateException("Cannot retrieve an object without giving a name!");
		}
		// Look for first segment of version after ours, or first version if we have none.
		ContentObject firstSegment =
			VersioningProfile.getFirstBlockOfAnyLaterVersion(getVersionedName(), null, null, timeout,
					_verifier, _handle);
		if (null != firstSegment) {
			return update(firstSegment);
		}
		return false;
	}

	public boolean updateAny() throws ContentDecodingException, IOException {
		return updateAny(SystemConfiguration.getDefaultTimeout());
	}

	/**
	 * Calls update(long) with the default timeout SystemConfiguration.getDefaultTimeout().
	 * @return see update(long).
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	public boolean update() throws ContentDecodingException, IOException {
		return update(SystemConfiguration.getDefaultTimeout());
	}

	/**
	 * Load data into object. If name is versioned, load that version. If
	 * name is not versioned, look for latest version.
	 * @param name Name of object to read.
	 * @param publisher Desired publisher, or null for any.
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	public boolean update(ContentName name, PublisherPublicKeyDigest publisher) throws ContentDecodingException, IOException {
		if (Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "Updating object to {0}.", name);
		CCNVersionedInputStream is = new CCNVersionedInputStream(name, publisher, _handle);
		return update(is);
	}

	/**
	 * Load a stream starting with a specific object.
	 * @param object
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	public boolean update(ContentObject object) throws ContentDecodingException, IOException {
		CCNInputStream is = new CCNInputStream(object, getInputStreamFlags(), _handle);
		setInputStreamProperties(is);
		is.seek(0); // in case it wasn't the first segment
		return update(is);
	}

	/**
	 * Updates the object from a CCNInputStream or one of its subclasses. Used predominantly
	 * by internal methods, most clients should use update() or update(long). Exposed for
	 * special-purpose use and experimentation.
	 * @param inputStream Stream to read object from.
	 * @return true if an update found, false if not.
	 * @throws ContentDecodingException if there is a problem decoding the object.
	 * @throws IOException if there is an error setting up network backing store.
	 */
	public synchronized boolean update(CCNInputStream inputStream) throws ContentDecodingException, IOException {

		// Allow subclasses to modify input stream processing prior to first read.
		setInputStreamProperties(inputStream);

		Tuple<ContentName, byte []> nameAndVersion = null;
		try {
			if (inputStream.isGone()) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINE))
					Log.fine(Log.FAC_IO, "Reading from GONE stream: {0}", inputStream.getBaseName());
				_data = null;

				// This will have a final version and a segment
				nameAndVersion = VersioningProfile.cutTerminalVersion(inputStream.deletionInformation().name());
				_currentPublisher = inputStream.deletionInformation().signedInfo().getPublisherKeyID();
				_currentPublisherKeyLocator = inputStream.deletionInformation().signedInfo().getKeyLocator();
				_available = true;
				_isGone = true;
				_isDirty = false;
				_lastSaved = digestContent();
			} else {
				super.update(inputStream);

				nameAndVersion = VersioningProfile.cutTerminalVersion(inputStream.getBaseName());
				_currentPublisher = inputStream.publisher();
				_currentPublisherKeyLocator = inputStream.publisherKeyLocator();
				_isGone = false;
			}
			_firstSegment = inputStream.getFirstSegment();  // preserve first segment
		} catch (NoMatchingContentFoundException nme) {
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "NoMatchingContentFoundException in update from input stream {0}, timed out before data was available.", inputStream.getBaseName());
			nameAndVersion = VersioningProfile.cutTerminalVersion(inputStream.getBaseName());
			_baseName = nameAndVersion.first();

			// used to fire off an updateInBackground here, to hopefully get a second
			// chance on scooping up the content. But that seemed likely to confuse
			// people and leave the object in an undetermined state. So allow caller
			// to manage that themselves.

			// not an error state, merely a not ready state.
			return false;
		} catch (LinkCycleException lce) {
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "Link cycle exception: {0}", lce.getMessage());
			setError(lce);
			throw lce;
		}

		_baseName = nameAndVersion.first();
		_currentVersionComponent = nameAndVersion.second();
		_currentVersionName = null; // cached if used
		_dereferencedLink = inputStream.getDereferencedLink(); // gets stack of links used, if any
		clearError();

		// Signal readers.
		newVersionAvailable(false);
		return true;
	}

	/**
	 * Update this object in the background -- asynchronously. This call updates the
	 * object a single time, after the first update (the requested version or the
	 * latest version), the object will not self-update again unless requested.
	 * To use, create an object using a write constructor, setting the data field
	 * to null. Then call updateInBackground() to retrieve the object's data asynchronously.
	 * To wait on data arrival, call either waitForData() or wait() on the object itself.
	 * @throws IOException
	 */
	public void updateInBackground() throws IOException {
		updateInBackground(false);
	}

	/**
	 * Update this object in the background -- asynchronously.
	 * To use, create an object using a write constructor, setting the data field
	 * to null. Then call updateInBackground() to retrieve the object's data asynchronously.
	 * To wait for an update to arrive, call wait() on this object itself.
	 * @param continuousUpdates If true, updates the
	 * object continuously to the latest version available, a single time if it is false.
	 * @throws IOException
	 */
	public void updateInBackground(boolean continuousUpdates) throws IOException {
		if (null == _baseName) {
			throw new IllegalStateException("Cannot retrieve an object without giving a name!");
		}
		// Look for latest version.
		updateInBackground(getVersionedName(), continuousUpdates, null);
	}

	public void updateInBackground(ContentName latestVersionKnown, boolean continuousUpdates) throws IOException {
		updateInBackground(latestVersionKnown, continuousUpdates, null);
	}

	public void updateInBackground(boolean continuousUpdates, UpdateListener listener) throws IOException {
		updateInBackground(getVersionedName(), continuousUpdates, listener);
	}

	/**
	 * Update this object in the background -- asynchronously.
	 * To use, create an object using a write constructor, setting the data field
	 * to null. Then call updateInBackground() to retrieve the object's data asynchronously.
	 * To wait for an update to arrive, call wait() on this object itself.
	 * @param latestVersionKnown the name of the latest version we know of, or an unversioned
	 *    name if no version known
	 * @param continuousUpdates If true, updates the
	 * object continuously to the latest version available, a single time if it is false.
	 * @throws IOException
	 */
	public synchronized void updateInBackground(ContentName latestVersionKnown, boolean continuousUpdates, UpdateListener listener) throws IOException {
		if (Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "updateInBackground: getting latest version after {0} in background.", latestVersionKnown);
		cancelInterest();
		if (null != listener) {
			addListener(listener);
		}
		synchronized (_updater) {
			_continuousUpdates = continuousUpdates;
		}
		Interest currentInterest = VersioningProfile.firstBlockLatestVersionInterest(latestVersionKnown, null);
		synchronized (_updater) {
			_currentInterest = currentInterest;
		}
		if (Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "updateInBackground: initial interest: {0}", _currentInterest);
		_handle.expressInterest(_currentInterest, this);
	}

	/**
	 * Cancel an outstanding updateInBackground().
	 */
	public synchronized void cancelInterest() {
		synchronized (_updater) {
			_continuousUpdates = false;

			if (null != _currentInterest) {
				_handle.cancelInterest(_currentInterest, this);
			}
		}
	}

	public synchronized void addListener(UpdateListener listener) {
		if (null == _updateListeners) {
			_updateListeners = new HashSet<UpdateListener>();
		} else if (_updateListeners.contains(listener)) {
			return; // don't re-add
		}
		_updateListeners.add(listener);
	}

	/**
	 * Does this object already have this listener. Uses Object.equals
	 * for comparison; so will only say yes if it has this *exact* listener
	 * instance already registered.
	 * @param listener
	 * @return
	 */
	public synchronized boolean hasListener(UpdateListener listener) {
		if (null == _updateListeners) {
			return false;
		}
		return (_updateListeners.contains(listener));
	}

	public void removeListener(UpdateListener listener) {
		if (null == _updateListeners)
			return;
		synchronized (this) {
			_updateListeners.remove(listener);
		}
	}

	public void clearListeners() {
		if (null == _updateListeners)
			return;
		synchronized(_updateListeners) {
			_updateListeners.clear();
		}
	}
	/**
	 * Save to existing name, if content is dirty. Update version.
	 * This is the default form of save -- if the object has been told to use
	 * a repository backing store, by either giving it a repository flow controller,
	 * calling saveToRepository() on it for its first save, or specifying false
	 * to a constructor that allows a raw argument, it will save to a repository.
	 * Otherwise will perform a raw save.
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	public boolean save() throws ContentEncodingException, IOException {
		return saveInternal(null, false, null);
	}

	/**
	 * Method for CCNFilterListeners to save an object in response to an Interest
	 * callback. An Interest has already been received, so the object can output
	 * one ContentObject as soon as one is ready. Ideally this Interest will have
	 * been received on the CCNHandle the object is using for output. If the object
	 * is not dirty, it will not be saved, and the Interest will not be consumed.
	 * If the Interest does not match this object, the Interest will not be consumed;
	 * it is up to the caller to ensure that the Interest would be matched by writing
	 * this object. (If the Interest doesn't match, no initial block will be output
	 * even if the object is saved; the object will wait for matching Interests prior
	 * to writing its blocks.)
	 */
	public boolean save(Interest outstandingInterest) throws ContentEncodingException, IOException {
		return saveInternal(null, false, outstandingInterest);
	}

	/**
	 * Save to existing name, if content is dirty. Saves to specified version.
	 * This is the default form of save -- if the object has been told to use
	 * a repository backing store, by either giving it a repository flow controller,
	 * calling saveToRepository() on it for its first save, or specifying false
	 * to a constructor that allows a raw argument, it will save to a repository.
	 * Otherwise will perform a raw save.
	 * @param version Version to save to.
	 * @return true if object was saved, false if it was not (if it was not dirty).
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	public boolean save(CCNTime version) throws ContentEncodingException, IOException {
		return saveInternal(version, false, null);
	}

	/**
	 * Save to existing name, if content is dirty. Saves to specified version.
	 * Method for CCNFilterListeners to save an object in response to an Interest
	 * callback. An Interest has already been received, so the object can output
	 * one ContentObject as soon as one is ready. Ideally this Interest will have
	 * been received on the CCNHandle the object is using for output. If the object
	 * is not dirty, it will not be saved, and the Interest will not be consumed.
	 * If the Interest does not match this object, the Interest will not be consumed;
	 * it is up to the caller to ensure that the Interest would be matched by writing
	 * this object. (If the Interest doesn't match, no initial block will be output
	 * even if the object is saved; the object will wait for matching Interests prior
	 * to writing its blocks.)
	 */
	public boolean save(CCNTime version, Interest outstandingInterest)
				throws ContentEncodingException, IOException {
		return saveInternal(version, false, outstandingInterest);
	}

	/**
	 * Save content to specific version. Internal form that performs actual save.
	 * @param version If version is non-null, assume that is the desired
	 * version. If not, set version based on current time.
	 * @param gone Are we saving this content as gone or not.
	 * @return return Returns true if it saved data, false if it thought data was not dirty and didn't
	 * 		save.
	 * TODO allow freshness specification
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	protected synchronized boolean saveInternal(CCNTime version, boolean gone, Interest outstandingInterest)
				throws ContentEncodingException, IOException {

		if (null == _baseName) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}

		// move object to this name
		// need to make sure we get back the actual name we're using,
		// even if output stream does automatic versioning
		// probably need to refactor save behavior -- right now, internalWriteObject
		// either writes the object or not; we need to only make a new name if we do
		// write the object, and figure out if that's happened. Also need to make
		// parent behavior just write, put the dirty check higher in the state.

		if (!gone && !isDirty()) {
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "Object not dirty. Not saving.");
			return false;
		}

		if (!gone && (null == _data)) {
			// skip some of the prep steps that have side effects rather than getting this exception later from superclass
			throw new InvalidObjectException("No data to save!");
		}

		// Create the flow controller, if we haven't already.
		createFlowController();

		// This is the point at which we care if we don't have a flow controller
		if (null == _flowControl) {
			throw new IOException("Cannot create flow controller! Specified save type is " + _saveType + "!");
		}

		// Handle versioning ourselves to make name handling easier. VOS should respect it.
		// We might have been handed a _baseName that was versioned. For most general behavior,
		// have to treat it as a normal name and that we are supposed to put our own version
		// underneath it. To save as a specific version, need to use save(version).
		ContentName name = new ContentName(_baseName, version == null ? CCNTime.now() : version);

		// DKS if we add the versioned name, we don't handle get latest version.
		// We re-add the baseName here in case an update has changed it.
		// TODO -- perhaps disallow updates for unrelated names.
		if (_FCIsOurs)
			_flowControl.addNameSpace(_baseName);

		if (!gone) {
			// CCNVersionedOutputStream will version an unversioned name.
			// If it gets a versioned name, will respect it.
			// This will call startWrite on the flow controller.
			//
			// Note that we must use the flow controller given to us as opposed to letting
			// the OutputStream create its own. This is because there may be dependencies from the
			// caller on the specific flow controller - the known case is the flow controller is a
			// CCNFlowServer which requires that the flow controller retain its objects after writing
			// them. A standard FC would cause the objects to be lost.
			CCNVersionedOutputStream cos = new CCNVersionedOutputStream(name, _keyLocator, _publisher, contentType(), _keys, _flowControl);
			cos.setFreshnessSeconds(_freshnessSeconds);
			if (null != outstandingInterest) {
				cos.addOutstandingInterest(outstandingInterest);
			}
			save(cos); // superclass stream save. calls flush but not close on a wrapping
			// digest stream; want to make sure we end up with a single non-MHT signed
			// segment and no header on small objects
			cos.close();
			// Grab digest and segment number after close because for short objects there may not be
			// a segment generated until the close
			_firstSegment = cos.getFirstSegment();
		} else {
			// saving object as gone, currently this is always one empty segment so we don't use an OutputStream
			ContentName segmentedName = SegmentationProfile.segmentName(name, SegmentationProfile.BASE_SEGMENT );
			byte [] empty = new byte[0];
			byte [] finalBlockID = SegmentationProfile.getSegmentNumberNameComponent(SegmentationProfile.BASE_SEGMENT);
			ContentObject goneObject =
				ContentObject.buildContentObject(segmentedName, ContentType.GONE, empty, _publisher, _keyLocator, null, finalBlockID);

			// The segmenter in the stream does an addNameSpace of the versioned name. Right now
			// this not only adds the prefix (ignored) but triggers the repo start write.
			_flowControl.addNameSpace(name);
			_flowControl.startWrite(name, Shape.STREAM); // Streams take care of this for the non-gone case.
			_flowControl.put(goneObject);
			_firstSegment = goneObject;
			_flowControl.beforeClose();
			_flowControl.afterClose();
			_lastSaved = GONE_OUTPUT;
		}
		_currentPublisher = _firstSegment.signedInfo().getPublisherKeyID();
		_currentPublisherKeyLocator = _firstSegment.signedInfo().getKeyLocator();
		_currentVersionComponent = name.lastComponent();
		_currentVersionName = name;
		setDirty(false);
		_available = true;

		// We have completed our save and don't know when or if another save may occur so don't keep
		// ourselves registered with ccnd. That could cause interests to be unnecessarily or incorrectly
		// forwarded to us during the dormant period.
		if (_FCIsOurs)
			_flowControl.removeNameSpace(_baseName);

		newVersionAvailable(true);
		if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
			Log.finest(Log.FAC_IO, "Saved object {0} publisher {1} key locator {2}", name, _currentPublisher, _currentPublisherKeyLocator);
		return true;
	}

	/**
	 * Convenience method to the data and save it in a single operation.
	 * @param data new data for object, set with setData
	 * @return
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	public boolean save(E data) throws ContentEncodingException, IOException {
		return save(null, data);
	}

	/**
	 * Convenience method to the data and save it as a particular version in a single operation.
	 * @param version the desired version
	 * @param data new data for object, set with setData
	 * @return
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	public synchronized boolean save(CCNTime version, E data) throws ContentEncodingException, IOException {
		setData(data);
		return save(version);
	}

	/*
	 * Methods to save from handlers. We probably don't want to wait in general to do a save from a handler
	 * but if the save is to a respository (and we really can't tell) we definitely can't do that because we
	 * have to wait for a response from the repo which only the handler can receive.
	 */

	/**
	 * Do a #save from a callback
	 */
	public void saveLater() {
		doSave(null, false, null, false);
	}

	/**
	 * Do a #save from a callback
	 * @param outstandingInterest
	 */
	public void saveLater(Interest outstandingInterest) {
		doSave(null, false, null, false);
	}

	/**
	 * Do a #save followed by a close of the network object from a callback
	 */
	public void saveLaterWithClose() {
		doSave(null, false, null, true);
	}

	/**
	 * Do a #save followed by a close of the network object from a callback
	 * @param outstandingInterest
	 */
	public void saveLaterWithClose(Interest outstandingInterest) {
		doSave(null, false, outstandingInterest, true);
	}

	/**
	 * Deprecated; use either object defaults or setRepositorySave() to indicate writes
	 * should go to a repository, then call save() to write.
	 * If raw=true or DEFAULT_RAW=true specified, this must be the first call to save made
	 * for this object to force repository storage (overriding default).
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	@Deprecated
	public synchronized boolean saveToRepository(CCNTime version) throws ContentEncodingException, IOException {
		if (null == _baseName) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		setSaveType(SaveType.REPOSITORY);
		return save(version);
	}

	/**
	 * Deprecated; use either object defaults or setRepositorySave() to indicate writes
	 * should go to a repository, then call save() to write.
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	@Deprecated
	public boolean saveToRepository() throws ContentEncodingException, IOException {
		return saveToRepository((CCNTime)null);
	}

	/**
	 * Deprecated; use either object defaults or setRepositorySave() to indicate writes
	 * should go to a repository, then call save() to write.
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	@Deprecated
	public boolean saveToRepository(E data) throws ContentEncodingException, IOException {
		return saveToRepository(null, data);
	}

	/**
	 * Deprecated; use either object defaults or setRepositorySave() to indicate writes
	 * should go to a repository, then call save() to write.
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	@Deprecated
	public synchronized boolean saveToRepository(CCNTime version, E data) throws ContentEncodingException, IOException {
		setData(data);
		return saveToRepository(version);
	}

	/**
	 * Save this object as GONE. Intended to mark the latest version, rather
	 * than a specific version as GONE. So for now, require that name handed in
	 * is *not* already versioned; throw an IOException if it is.
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	public synchronized boolean saveAsGone() throws ContentEncodingException, IOException {
		return saveAsGone(null);
	}

	/**
	 * For use by CCNFilterListeners, saves a GONE object and emits an initial
	 * block in response to an already-received Interest.
	 * Save this object as GONE. Intended to mark the latest version, rather
	 * than a specific version as GONE. So for now, require that name handed in
	 * is *not* already versioned; throw an IOException if it is.
	 * @throws IOException
	 */
	public synchronized boolean saveAsGone(Interest outstandingInterest)
					throws ContentEncodingException, IOException {
		if (null == _baseName) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		_data = null;
		_isGone = true;
		setDirty(true);
		return saveInternal(null, true, outstandingInterest);
	}

	/**
	 * Deprecated; use either object defaults or setRepositorySave() to indicate writes
	 * should go to a repository, then call save() to write.
	 * If raw=true or DEFAULT_RAW=true specified, this must be the first call to save made
	 * for this object.
	 * @throws ContentEncodingException if there is an error encoding the content
	 * @throws IOException if there is an error reading the content from the network
	 */
	@Deprecated
	public synchronized boolean saveToRepositoryAsGone() throws ContentEncodingException, IOException {
		setSaveType(SaveType.REPOSITORY);
		return saveAsGone();
	}

	/**
	 * Turn off flow control for this object. Warning - calling this risks packet drops. It should only
	 * be used for tests or other special circumstances in which
	 * you "know what you are doing".
	 */
	public synchronized void disableFlowControl() {
		if (null != _flowControl)
			_flowControl.disable();
		_disableFlowControlRequest = true;
	}

	/**
	 * Used to signal waiters and listeners that a new version is available.
	 * @param wasSave is a new version available because we were saved, or because
	 *   we found a new version on the network?
	 */
	protected void newVersionAvailable(boolean wasSave) {
		if (Log.isLoggable(Log.FAC_IO, Level.FINER)) {
			Log.finer(Log.FAC_IO, "newVersionAvailable: New version of object available: {0}", getVersionedName());
		}
		// by default signal all waiters
		this.notifyAll();

		// and any registered listeners
		if (null != _updateListeners) {
			for (UpdateListener listener : _updateListeners) {
				listener.newVersionAvailable(this, wasSave);
			}
		}
	}

	/**
	 * Will return immediately if this object already has data, otherwise
	 * will wait indefinitely for the initial data to appear.
	 */
	public void waitForData() {
		if (available())
			return;
		synchronized (this) {
			while (!available()) {
				try {
					wait();
				} catch (InterruptedException e) {
				}
			}
		}
	}

	/**
	 * Will wait for data to arrive. Callers should use
	 * available() to determine whether data has arrived or not.
	 * If data already available, will return immediately (in other
	 * words, this is only useful to wait for the first update to
	 * an object, or to ensure that it has data). To wait for later
	 * updates, call wait() on the object itself.
	 * @param timeout In milliseconds. If 0, will wait forever (if data does not arrive).
	 */
	public void waitForData(long timeout) {
		if (available())
			return;
		synchronized (this) {
			long startTime = System.currentTimeMillis();
			boolean keepTrying = true;
			while (!available() && keepTrying) {
				// deal with spontaneous returns from wait()
				try {
					long waitTime = timeout - (System.currentTimeMillis() - startTime);
					if (waitTime > 0)
						wait(waitTime);
					else
						keepTrying = false;
				} catch (InterruptedException ie) {}
			}
		}
	}

	public boolean isGone() {
		return _isGone;
	}

	@Override
	protected byte [] digestContent() throws IOException {
		if (isGone()) {
			return GONE_OUTPUT;
		}
		return super.digestContent();
	}

	@Override
	protected synchronized E data() throws ContentNotReadyException, ContentGoneException, ErrorStateException {
		if (isGone()) {
			throw new ContentGoneException("Content is gone!");
		}
		return super.data();
	}

	@Override
	public synchronized void setData(E newData) {

		_isGone = false; // clear gone, even if we're setting to null; only saveAsGone can set as gone
		super.setData(newData);
	}

	public synchronized CCNTime getVersion() throws IOException {
		if (isSaved())
			return VersioningProfile.getVersionComponentAsTimestamp(getVersionComponent());
		return null;
	}

	public synchronized VersionNumber getVersionNumber() throws IOException {
		if (isSaved())
			return new VersionNumber(getVersionComponent());
		return null;
	}

	public synchronized ContentName getBaseName() {
		return _baseName;
	}

	public CCNHandle getHandle() {
		return _handle;
	}

	public synchronized byte [] getVersionComponent() throws IOException {
		if (isSaved())
			return _currentVersionComponent;
		return null;
	}

	/**
	 * Returns the first segment number for this object.
	 * @return The index of the first segment of stream data or null if no segments generated yet.
	 */
	public Long firstSegmentNumber() {
		if (null != _firstSegment) {
			return SegmentationProfile.getSegmentNumber(_firstSegment.name());
		} else {
			return null;
		}
	}

	/**
	 * Returns the digest of the first segment of this object which may be used
	 * to help identify object instance unambiguously.
	 *
	 * @return The digest of the first segment of this object if available, null otherwise
	 */
	public byte[] getFirstDigest() {
		// Do not attempt to force update here to leave control over whether reading
		// or writing with the object creator.  The return value may be null if the
		// object is not in a state of having a first segment
		if (null != _firstSegment) {
			return _firstSegment.digest();
		} else {
			return null;
		}
	}

	/**
	 * Returns the first segment of this object.
	 */
	public ContentObject getFirstSegment() {
		return _firstSegment;
	}

	/**
	 * If we traversed a link to get this object, make it available.
	 */
	public synchronized LinkObject getDereferencedLink() { return _dereferencedLink; }

	/**
	 * Use only if you know what you are doing.
	 */
	public synchronized void setDereferencedLink(LinkObject dereferencedLink) { _dereferencedLink = dereferencedLink; }

	/**
	 * Add a LinkObject to the stack we had to dereference to get here.
	 */
	public synchronized void pushDereferencedLink(LinkObject dereferencedLink) {
		if (null == dereferencedLink) {
			return;
		}
		if (null != _dereferencedLink) {
			if (null != dereferencedLink.getDereferencedLink()) {
				if (Log.isLoggable(Log.FAC_IO, Level.WARNING)) {
					Log.warning(Log.FAC_IO, "Merging two link stacks -- {0} already has a dereferenced link from {1}. Behavior unpredictable.",
							dereferencedLink.getVersionedName(), dereferencedLink.getDereferencedLink().getVersionedName());
				}
			}
			dereferencedLink.pushDereferencedLink(_dereferencedLink);
		}
		setDereferencedLink(dereferencedLink);
	}

	/**
	 * If the object has been saved or read from the network, returns the (cached) versioned
	 * name. Otherwise returns the base name.
	 * @return
	 */
	public synchronized ContentName getVersionedName()  {
		try {
			if (isSaved()) {
				if ((null == _currentVersionName) && (null != _currentVersionComponent)) // cache; only read lock necessary
					_currentVersionName =  new ContentName(_baseName, _currentVersionComponent);
				return _currentVersionName;
			}
			return getBaseName();
		} catch (IOException e) {
			if (Log.isLoggable(Log.FAC_IO, Level.WARNING))
				Log.warning(Log.FAC_IO, "Invalid state for object {0}, cannot get current version name: {1}", getBaseName(), e);
			return getBaseName();
		}
	}

	public synchronized PublisherPublicKeyDigest getContentPublisher() throws IOException {
		if (isSaved())
			return _currentPublisher;
		return null;
	}

	public synchronized KeyLocator getPublisherKeyLocator() throws IOException  {
		if (isSaved())
			return _currentPublisherKeyLocator;
		return null;
	}

	/**
	 * Change the publisher information we use when we sign commits to this object.
	 * Takes effect on the next save(). Useful for objects created with a read constructor,
	 * but who want to override default publisher information.
	 * @param signingKey indicates the identity we want to use to sign future writes to this
	 * 	object. If null, will default to key manager's (user's) default key.
	 * @param locator the key locator (key lookup location) information to attach to future
	 * 	writes to this object. If null, will be the default value associated with the
	 * 	chosen signing key.
	 */
	public synchronized void setOurPublisherInformation(PublisherPublicKeyDigest publisherIdentity, KeyLocator keyLocator) {
		_publisher = publisherIdentity;
		_keyLocator = keyLocator;
	}

	public void setTimeout(int timeout) {
		try {
			createFlowController();
			getFlowControl().setTimeout(timeout);
		} catch (IOException e) {}
	}

	public synchronized Interest handleContent(ContentObject co, Interest interest) {
		_updater.add(co);
		return null;
	}


	/**
	 * Do queued updates in background
	 */
	protected class Updater {
		protected Queue<ContentObject> _queue = new ConcurrentLinkedQueue<ContentObject>();
		protected CCNContentHandler _handler = null;
		protected boolean _isRunning = false;

		protected Updater(CCNContentHandler handler) {
			_handler = handler;
		}

		/**
		 * Add a content object to the queue for processing. If we aren't running a processing
		 * thread right now, start one.
		 *
		 * @param co
		 */
		protected void add(ContentObject co) {
			_queue.add(co);
			if (!_isRunning) {
				_isRunning = true;
				SystemConfiguration._systemThreadpool.execute(new BackgroundUpdater());
			}
		}

		protected class BackgroundUpdater implements Runnable {
			public void run() {
				while (true) {
					try {
						boolean hasNewVersion = false;
						byte [][] excludes = null;

						ContentObject co = null;
						co = _queue.poll();
						if (null == co) {
							_isRunning = false;
							return;
						}

						try {
							if (Log.isLoggable(Log.FAC_IO, Level.INFO))
								Log.info(Log.FAC_IO, "updateInBackground: handleContent: " + _currentInterest + " retrieved " + co.name());
							if (VersioningProfile.startsWithLaterVersionOf(co.name(), _currentInterest.name())) {
								// OK, we have something that is a later version of our desired object.
								// We're not sure it's actually the first content segment.
								hasNewVersion = true;

								if (VersioningProfile.isVersionedFirstSegment(_currentInterest.name(), co, null)) {
									if (Log.isLoggable(Log.FAC_IO, Level.INFO))
										Log.info(Log.FAC_IO, "updateInBackground: Background updating of {0}, got first segment: {1}", getVersionedName(), co.name());

									// Streams assume caller has verified. So we verify here.
									// TODO add support for settable verifiers
									if (!_verifier.verify(co)) {
										if (Log.isLoggable(Log.FAC_SIGNING, Level.WARNING)) {
											Log.warning(Log.FAC_SIGNING, "CCNNetworkObject: content object received from background update did not verify! Ignoring object: {0}", co.fullName());
										}
										hasNewVersion = false;

										// TODO -- exclude this one by digest, otherwise we're going
										// to get it back! For now, just copy the top-level part of GLV
										// behavior and exclude this version component. This isn't the right
										// answer, malicious objects can exclude new versions. But it's not clear
										// if the right answer is to do full gLV here and let that machinery
										// handle things, pulling potentially multiple objects in a callback,
										// or we just have to wait for issue #100011, and the ability to selectively
										// exclude content digests.
										excludes = new byte [][]{co.name().component(_currentInterest.name().count())};
										if (Log.isLoggable(Log.FAC_IO, Level.INFO))
											Log.info(Log.FAC_IO, "updateInBackground: handleContent: got content for {0} that doesn't verify ({1}), excluding bogus version {2} as temporary workaround FIX WHEN POSSIBLE",
													_currentInterest.name(), co.fullName(), Component.printURI(excludes[0]));

									} else {
										update(co);
									}
								} else {
									// Have something that is not the first segment, like a repo write or a later segment. Go back
									// for first segment.
									ContentName latestVersionName = co.name().cut(_currentInterest.name().count() + 1);
									if (Log.isLoggable(Log.FAC_IO, Level.INFO))
										Log.info(Log.FAC_IO, "updateInBackground: handleContent (network object): Have version information, now querying first segment of {0}", latestVersionName);
									// This should verify the first segment when we get it.
									update(latestVersionName, co.signedInfo().getPublisherKeyID());
								}

							} else {
								excludes = new byte [][]{co.name().component(_currentInterest.name().count() - 1)};
								if (Log.isLoggable(Log.FAC_IO, Level.INFO))
									Log.info(Log.FAC_IO, "updateInBackground: handleContent: got content for {0} that doesn't match: {1}", _currentInterest.name(), co.name());
							}
						} catch (IOException ex) {
							if (Log.isLoggable(Log.FAC_IO, Level.INFO))
								Log.info(Log.FAC_IO, "updateInBackground: Exception {0}: {1}  attempting to update based on object : {2}", ex.getClass().getName(), ex.getMessage(), co.name());
							if (Log.isLoggable(Log.FAC_IO, Level.FINE)) {
								Log.logStackTrace(Log.FAC_IO, Level.FINE, ex);
							}
							// alright, that one didn't work, try to go on.
						}

						if (hasNewVersion) {
							boolean continuousUpdates = false;
							synchronized (_updater) {
								continuousUpdates = _continuousUpdates;
							}
							if (continuousUpdates) {
								if (Log.isLoggable(Log.FAC_IO, Level.INFO))
									Log.info(Log.FAC_IO, "updateInBackground: handleContent: got a new version, continuous updates, calling updateInBackground recursively then returning null.");
								updateInBackground(true);
							} else {
								if (Log.isLoggable(Log.FAC_IO, Level.INFO))
									Log.info(Log.FAC_IO, "updateInBackground: handleContent: got a new version, not continuous updates, returning null.");
							}
							// the updates above call newVersionAvailable
						} else {
							synchronized (_updater) {
								if (null != excludes) {
									_currentInterest.exclude().add(excludes);
								}
								if (Log.isLoggable(Log.FAC_IO, Level.INFO))
									Log.info(Log.FAC_IO, "updateInBackground: handleContent: no new version, returning new interest for expression: {0}", _currentInterest);
								_handle.expressInterest (_currentInterest, _handler);
							}
						}
					} catch (IOException ex) {
						if (Log.isLoggable(Log.FAC_IO, Level.INFO))
							Log.info(Log.FAC_IO, "updateInBackground: handleContent: Exception {0}: {1}  attempting to request further updates : {2}", ex.getClass().getName(), ex.getMessage(), _currentInterest);
						if (Log.isLoggable(Log.FAC_IO, Level.FINE)) {
							Log.logStackTrace(Log.FAC_IO, Level.FINE, ex);
						}
					}
				}
			}
		}
	}

	/**
	 * Do saveInternal in background - used to implement saveLater...
	 */
	protected void doSave(CCNTime version, boolean gone, Interest outstandingInterest, boolean doClose) {
		SystemConfiguration._systemThreadpool.execute(new BackgroundSaver(version, gone, outstandingInterest, doClose));
	}

	protected class BackgroundSaver implements Runnable {
		protected boolean _gone = false;
		protected CCNTime _version = null;
		protected Interest _outstandingInterest = null;
		protected boolean _doClose = false;

		public BackgroundSaver(CCNTime version, boolean gone, Interest outstandingInterest, boolean doClose) {
			_version = version;
			_gone = gone;
			_outstandingInterest = outstandingInterest;
			_doClose = doClose;
		}

		public void run() {
			try {
				saveInternal(_version, _gone, _outstandingInterest);
				if (_doClose)
					close();
			} catch (Exception e) {
				Log.logStackTrace(Log.FAC_IO, Level.WARNING, e);
			}
		}
	}

	/**
	 * Subclasses that need to write an object of a particular type can override.
	 * DKS TODO -- verify type on read, modulo that ENCR overrides everything.
	 * @return
	 */
	public ContentType contentType() { return ContentType.DATA; }

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result
				+ ((_baseName == null) ? 0 : _baseName.hashCode());
		result = prime
				* result
				+ ((_currentPublisher == null) ? 0 : _currentPublisher
						.hashCode());
		result = prime * result + Arrays.hashCode(_currentVersionComponent);
		return result;
	}

	@SuppressWarnings("unchecked") // cast to obj<E>
	@Override
	public boolean equals(Object obj) {
		// should hold read lock?
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		CCNNetworkObject<E> other = (CCNNetworkObject<E>) obj;
		if (_baseName == null) {
			if (other._baseName != null)
				return false;
		} else if (!_baseName.equals(other._baseName))
			return false;
		if (_currentPublisher == null) {
			if (other._currentPublisher != null)
				return false;
		} else if (!_currentPublisher.equals(other._currentPublisher))
			return false;
		if (!Arrays.equals(_currentVersionComponent,
				other._currentVersionComponent))
			return false;
		return true;
	}

	@Override
	public String toString() {
		try {
			if (isSaved()) {
				return getVersionedName() + ": " + (isGone() ? "GONE" : "\nData:" + data()) + "\n	Publisher: " +
						getContentPublisher() + "\n		Publisher KeyLocator: " + getPublisherKeyLocator() + "\n";
			} else if (available()) {
				return getBaseName() + " (unsaved): " + data();
			} else {
				return getBaseName() + " (unsaved, no data)";
			}
		} catch (IOException e) {
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "Unexpected exception retrieving object information: {0}", e);
			return getBaseName() + ": unexpected exception " + e;
		}
	}
}
