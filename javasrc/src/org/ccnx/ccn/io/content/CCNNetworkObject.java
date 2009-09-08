package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.DataUtils.Tuple;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.CCNVersionedOutputStream;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Extends a NetworkObject to add specifics for using a CCN based backing store.
 *
 * Need to support four use models:
 * dimension 1: synchronous - ask for and block, the latest version or a specific version
 * dimension 2: asynchronous - ask for and get in the background, the latest version or a specific
 *   version
 * When possible, keep track of the latest version known so that the latest version queries
 * can attempt to do better than that. Start by using only in the background load case, as until
 * something comes back we can keep using the old one and the propensity for blocking is high.
 * 
 * Support for superclasses or users specifying different flow controllers with
 * different behavior. Build in support for either the simplest standard flow
 * controller, or a standard repo-backed flow controller.
 * 
 * @author smetters
 *
 * @param <E>
 */
public abstract class CCNNetworkObject<E> extends NetworkObject<E> implements CCNInterestListener {

	protected static boolean DEFAULT_RAW = true;
	protected static long DEFAULT_TIMEOUT = 3000; // msec
	
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
	
	protected PublisherPublicKeyDigest _currentPublisher;
	protected KeyLocator _currentPublisherKeyLocator;
	protected CCNHandle _library;
	protected CCNFlowControl _flowControl;
	protected boolean _disableFlowControlRequest = false;
	protected PublisherPublicKeyDigest _publisher; // publisher we write under, if null, use library defaults
	protected KeyLocator _keyLocator; // locator to find publisher key
	protected boolean _raw = DEFAULT_RAW; // what kind of flow controller to make if we don't have one
	
	// control ongoing update.
	ArrayList<byte[]> _excludeList = new ArrayList<byte[]>();
	Interest _currentInterest = null;
	boolean _continuousUpdates = false;
	
	/**
	 * Basic write constructor.
	 * Setting true or false in this constructor determines default -- repo or raw objects.
	 * @param type
	 * @param name
	 * @param data
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public CCNNetworkObject(Class<E> type, ContentName name, E data, CCNHandle library) throws IOException {
		this(type, name, data, DEFAULT_RAW, null, null, library);
	}
		
	/**
	 * Allow publisher control.
	 * @param type
	 * @param name
	 * @param data
	 * @param publisher which publisher key to sign this content with, or library defaults if null
	 * @param library
	 * @throws IOException
	 */
	public CCNNetworkObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher, KeyLocator locator, CCNHandle library) throws IOException {
		this(type, name, data, DEFAULT_RAW, publisher, locator, library);
	}
		
	/**
	 * A raw object uses a raw flow controller. A non-raw object uses a repository backend.
	 * repo flow controller.
	 * @param type
	 * @param name
	 * @param data
	 * @param raw
	 * @param publisher which publisher key to sign this content with, or library defaults if null
	 * @param library
	 * @throws IOException
	 */
	public CCNNetworkObject(Class<E> type, ContentName name, E data, boolean raw, 
							PublisherPublicKeyDigest publisher, KeyLocator locator,
							CCNHandle library) throws IOException {
		// Don't start pulling a namespace till we actually write something. We may never write
		// anything on this object. In fact, don't make a flow controller at all till we need one.
		super(type, data);
		if (null == library) {
			try {
				library = CCNHandle.open();
			} catch (ConfigurationException e) {
				throw new IllegalArgumentException("Library null, and cannot create one: " + e.getMessage(), e);
			}
		}
		_library = library;
		_baseName = name;
		_publisher = publisher;
		_keyLocator = locator;
		_raw = raw;
	}

	/**
	 * Write constructors. This allows subclasses or users to pass in new forms of flow controller.
	 * You should only use this one if you really know what you are doing.
	 * 
	 * @param type
	 * @param name
	 * @param data
	 * @param publisher which publisher key to sign this content with, or library defaults if null
	 * @param flowControl
	 * @throws IOException
	 */
	protected CCNNetworkObject(Class<E> type, ContentName name, E data, 
								PublisherPublicKeyDigest publisher, 
								KeyLocator locator,
								CCNFlowControl flowControl) throws IOException {
		this(type, name, data, publisher, locator, flowControl.getLibrary());
		_flowControl = flowControl;
	}

	/**
	 * Read constructors. Will try to pull latest version of this object, or a specific
	 * named version. Flow controller assumed to already be set to handle this namespace.
	 * @param type
	 * @param name
	 * @param publisher Who must have signed the data we want. TODO should be PublisherID.
	 * @param library
	 * @throws ConfigurationException
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	public CCNNetworkObject(Class<E> type, ContentName name, 
			CCNHandle library) throws IOException, XMLStreamException {
		this(type, name, (PublisherPublicKeyDigest)null, library);
	}

	public CCNNetworkObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws IOException, XMLStreamException {
		this(type, name, publisher, DEFAULT_RAW, library);
	}

	/**
	 * Updates to either a particular named version, or if no version given on name,
	 * the latest version. 
	 * Currently will time out and be unhappy if no such version exists.
	 * 
	 * Need a way to differentiate whether to read a specific
	 * version or to read the latest version after a given one.
	 * @param type
	 * @param name
	 * @param publisher
	 * @param flowControl
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	protected CCNNetworkObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNFlowControl flowControl) throws IOException, XMLStreamException {
		super(type);
		_flowControl = flowControl;
		_library = flowControl.getLibrary();
		update(name, publisher);
	}

	public CCNNetworkObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			boolean raw, CCNHandle library) throws IOException, XMLStreamException {
		super(type);
		if (null == library) {
			try {
				library = CCNHandle.open();
			} catch (ConfigurationException e) {
				throw new IllegalArgumentException("Library null, and cannot create one: " + e.getMessage(), e);
			}
		}
		_library = library;
		_baseName = name;
		update(name, publisher);
	}

	/**
	 * Read constructors if you already have a segment of the object. Used by streams.
	 * @param type
	 * @param firstSegment
	 * @param library
	 * @throws ConfigurationException
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	public CCNNetworkObject(Class<E> type, ContentObject firstSegment, CCNHandle library) throws IOException, XMLStreamException {
		this(type, firstSegment, DEFAULT_RAW, library);
	}
	
	public CCNNetworkObject(Class<E> type, ContentObject firstSegment, boolean raw, CCNHandle library) throws IOException, XMLStreamException {
		super(type);
		if (null == library) {
			try {
				library = CCNHandle.open();
			} catch (ConfigurationException e) {
				throw new IllegalArgumentException("Library null, and cannot create one: " + e.getMessage(), e);
			}
		}
		_library = library;
		update(firstSegment);
	}

	protected CCNNetworkObject(Class<E> type, ContentObject firstSegment, CCNFlowControl flowControl) throws IOException, XMLStreamException {
		super(type);
		if (null == flowControl)
			throw new IllegalArgumentException("flowControl cannot be null!");
		_flowControl = flowControl;
		_library = flowControl.getLibrary();
		update(firstSegment);
	}
	
	/**
	 * Maximize laziness of flow controller creation, to make it easiest for client code to
	 * decide how to store this object.
	 * When we create the flow controller, we add the base name namespace, so it will respond
	 * to requests for latest version.
	 * 
	 * Expects to be called with the write lock held.
	 * @return
	 * @throws IOException 
	 */
	protected synchronized void createFlowController() throws IOException {
		if (null == _flowControl) {
			_flowControl = (_raw ? new CCNFlowControl(_library) : 
								   new RepositoryFlowControl(_library));
			if (_disableFlowControlRequest)
				_flowControl.disable();
			// Have to register the version root. If we just register this specific version, we won't
			// see any shorter interests -- i.e. for get latest version.
			_flowControl.addNameSpace(_baseName);
		}
	}

	/**
	 * Attempts to find a version after the latest one we have, or times out. If
	 * it times out, it simply leaves the object unchanged.
	 * @return returns true if it found an update, false if not
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public boolean update(long timeout) throws XMLStreamException, IOException {
		try {
			_lock.writeLock().lock();

			if (null == _baseName) {
				throw new IllegalStateException("Cannot retrieve an object without giving a name!");
			}
			// Look for first segment of version after ours, or first version if we have none.
			ContentObject firstSegment = 
				VersioningProfile.getFirstBlockOfLatestVersion(getVersionedName(), null, null, timeout, 
						_library.defaultVerifier(), _library);
			if (null != firstSegment) {
				return update(firstSegment);
			}
			return false;
		} finally {
			_lock.writeLock().unlock();
		}
	}

	public boolean update() throws XMLStreamException, IOException {
		return update(DEFAULT_TIMEOUT);
	}
	
	/**
	 * Load data into object. If name is versioned, load that version. If
	 * name is not versioned, look for latest version. 
	 * @param name
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws ClassNotFoundException 
	 */
	public boolean update(ContentName name, PublisherPublicKeyDigest publisher) throws XMLStreamException, IOException {
		try {
			_lock.writeLock().lock();
			Log.info("Updating object to {0}.", name);
			CCNVersionedInputStream is = new CCNVersionedInputStream(name, publisher, _library);
			return update(is);
		} finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * Load a stream starting with a specific object.
	 * @param object
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public boolean update(ContentObject object) throws XMLStreamException, IOException {
		try {
			_lock.writeLock().lock();
			CCNInputStream is = new CCNInputStream(object, _library);
			is.seek(0); // in case it wasn't the first segment
			return update(is);
		} finally {
			_lock.writeLock().unlock();
		}
	}

	public boolean update(CCNInputStream inputStream) throws IOException, XMLStreamException {
		try {
			_lock.writeLock().lock();
			Tuple<ContentName, byte []> nameAndVersion = null;
			if (inputStream.isGone()) {
				_data = null;

				// This will have a final version and a segment
				nameAndVersion = VersioningProfile.cutTerminalVersion(inputStream.deletionInformation().name());
				_currentPublisher = inputStream.deletionInformation().signedInfo().getPublisherKeyID();
				_currentPublisherKeyLocator = inputStream.deletionInformation().signedInfo().getKeyLocator();
				_available = true;
				_isGone = true;
			} else {
				super.update(inputStream);

				nameAndVersion = VersioningProfile.cutTerminalVersion(inputStream.baseName());
				_currentPublisher = inputStream.publisher();
				_currentPublisherKeyLocator = inputStream.publisherKeyLocator();
				_isGone = false;
			}
			_baseName = nameAndVersion.first();
			_currentVersionComponent = nameAndVersion.second();
			_currentVersionName = null; // cached if used

		} finally {
			_lock.writeLock().unlock();
		}
		
		// Signal readers.
		newVersionAvailable();
		return true;
	}
	
	public void updateInBackground() throws IOException {
		updateInBackground(false);
	}
	
	public void updateInBackground(boolean continuousUpdates) throws IOException {
		if (null == _baseName) {
			throw new IllegalStateException("Cannot retrieve an object without giving a name!");
		}
		// Look for latest version.
		updateInBackground(getVersionedName(), continuousUpdates);
	}

	/**
	 * Tries to find a version after this one.
	 * @param latestVersionKnown the name of the latest version we know of, or an unversioned
	 *    name if no version known
	 * @param continuousUpdates do this once, or keep going -- produce a dynamically updated object
	 *   DKS TODO look at locking of updates
	 * @throws IOException 
	 */
	public void updateInBackground(ContentName latestVersionKnown, boolean continuousUpdates) throws IOException {
		
		try {
			_lock.writeLock().lock();
			Log.info("updateInBackground: getting latest version after {0} in background.", latestVersionKnown);
			// DKS TODO locking?
			cancelInterest();
			// express this
			// DKS TODO better versioned interests, a la library.getlatestVersion
			_continuousUpdates = continuousUpdates;
			_currentInterest = VersioningProfile.firstBlockLatestVersionInterest(latestVersionKnown, null);
			Log.info("UpdateInBackground: interest: {0}", _currentInterest);
			_library.expressInterest(_currentInterest, this);
		} finally {
			_lock.writeLock().lock();
		}
	}
	
	public void cancelInterest() {
		try {
			_lock.writeLock().lock();
			_continuousUpdates = false;
			if (null != _currentInterest) {
				_library.cancelInterest(_currentInterest, this);
			}
			_excludeList.clear();
		} finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * Save to existing name, if content is dirty. Update version.
	 * Saves according to flow controller in force, or creates one according to
	 * the value of raw specified.
	 * @throws IOException 
	 */
	public boolean save() throws IOException {
		if (null == _baseName) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		return saveInternal(null, false);
	}

	/**
	 * Save to existing name, if content is dirty. Update version.
	 * Saves according to flow controller in force, or creates one according to
	 * the value of raw specified.
	 * @throws IOException 
	 */
	public boolean save(CCNTime version) throws IOException {
		if (null == _baseName) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		return saveInternal(version, false);
	}

	/**
	 * Save content to specific version. If version is non-null, assume that is the desired
	 * version. If not, set version based on current time.
	 * @param name
	 * @param return Returns true if it saved data, false if it thought data was stale and didn't
	 * 		save. (DKS TODO: add force write flag if you need to update version. Also allow specification of freshness.)
	 * @throws IOException 
	 */
	public boolean saveInternal(CCNTime version, boolean gone) throws IOException {
		
		try {
			_lock.writeLock().lock();

			// move object to this name
			// need to make sure we get back the actual name we're using,
			// even if output stream does automatic versioning
			// probably need to refactor save behavior -- right now, internalWriteObject
			// either writes the object or not; we need to only make a new name if we do
			// write the object, and figure out if that's happened. Also need to make
			// parent behavior just write, put the dirty check higher in the state.

			if (!gone && !isDirty()) { 
				Log.info("Object not dirty. Not saving.");
				return false;
			}

			if (!gone && (null == _data)) {
				// skip some of the prep steps that have side effects rather than getting this exception later from superclass
				throw new InvalidObjectException("No data to save!");
			}

			if (null == _baseName) {
				throw new IllegalStateException("Cannot save an object without giving it a name!");
			}

			// Create the flow controller, if we haven't already.
			createFlowController();

			// Handle versioning ourselves to make name handling easier. VOS should respect it.
			ContentName name = _baseName;
			if (null != version) {
				name = VersioningProfile.addVersion(_baseName, version);
			} else {
				name = VersioningProfile.addVersion(_baseName);
			}
			// DKS if we add the versioned name, we don't handle get latest version.
			// We re-add the baseName here in case an update has changed it.
			// TODO -- perhaps disallow updates for unrelated names.
			_flowControl.addNameSpace(_baseName);

			if (!gone) {
				// CCNVersionedOutputStream will version an unversioned name. 
				// If it gets a versioned name, will respect it. 
				// This will call startWrite on the flow controller.
				CCNVersionedOutputStream cos = new CCNVersionedOutputStream(name, _keyLocator, _publisher, contentType(), _flowControl);
				save(cos); // superclass stream save. calls flush but not close on a wrapping
				// digest stream; want to make sure we end up with a single non-MHT signed
				// segment and no header on small objects
				cos.close();
				_currentPublisher = (_publisher == null) ? _flowControl.getLibrary().getDefaultPublisher() : _publisher; // TODO DKS -- is this always correct?
				_currentPublisherKeyLocator = (_keyLocator == null) ? 
						_flowControl.getLibrary().keyManager().getKeyLocator(_publisher) : _keyLocator;
			} else {
				// saving object as gone, currently this is always one empty segment so we don't use an OutputStream
				ContentName segmentedName = SegmentationProfile.segmentName(name, SegmentationProfile.BASE_SEGMENT );
				byte [] empty = new byte[0];
				ContentObject goneObject = 
					ContentObject.buildContentObject(segmentedName, ContentType.GONE, empty, _publisher, _keyLocator, null, null);

				// The segmenter in the stream does an addNameSpace of the versioned name. Right now
				// this not only adds the prefix (ignored) but triggers the repo start write.
				_flowControl.addNameSpace(name);
				_flowControl.startWrite(name, Shape.STREAM); // Streams take care of this for the non-gone case.
				_flowControl.put(goneObject);
				_flowControl.beforeClose();
				_flowControl.afterClose();
				_currentPublisher = goneObject.signedInfo().getPublisherKeyID();
				_currentPublisherKeyLocator = goneObject.signedInfo().getKeyLocator();
			}
			_currentVersionComponent = name.lastComponent();
			_currentVersionName = null;
			setDirty(false);
			_available = true;

		} finally {
			_lock.writeLock().unlock();
		}
		return true;
	}
	
	public boolean save(E data) throws XMLStreamException, IOException {
		return save(null, data);
	}
	
	public boolean save(CCNTime version, E data) throws IOException {
		try {
			_lock.writeLock().lock();
			setData(data);
			return save(version);
		} finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * For repeatability, Timestamp should be quantized using methods in DataUtils class.
	 * If raw=true or DEFAULT_RAW=true specified, this must be the first call to save made
	 * for this object.
	 */
	public boolean saveToRepository(CCNTime version) throws IOException {
		try {
			_lock.writeLock().lock();

			if (null == _baseName) {
				throw new IllegalStateException("Cannot save an object without giving it a name!");
			}
			if ((null != _flowControl) && !(_flowControl instanceof RepositoryFlowControl)) {
				throw new IOException("Cannot call saveToRepository on raw object!");
			}
			_raw = false; // control what flow controller will be made
			return save(version);
		} finally {
			_lock.writeLock().unlock();
		}
	}

	public boolean saveToRepository() throws IOException {		
		return saveToRepository((CCNTime)null);
	}
	
	public boolean saveToRepository(E data) throws IOException {
		return saveToRepository(null, data);
	}
	
	public boolean saveToRepository(CCNTime version, E data) throws IOException {
		try {
			_lock.writeLock().lock();
			setData(data);
			return saveToRepository(version);
		} finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * Save this object as GONE. Intended to mark the latest version, rather
	 * than a specific version as GONE. So for now, require that name handed in
	 * is *not* already versioned; throw an IOException if it is.
	 * @param name
	 * @throws IOException
	 */
	public boolean saveAsGone() throws IOException {	
		try {
			_lock.writeLock().lock();
			if (null == _baseName) {
				throw new IllegalStateException("Cannot save an object without giving it a name!");
			}
			_data = null;
			_isGone = true;
			setDirty(true);
			return saveInternal(null, true);
		} finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * If raw=true or DEFAULT_RAW=true specified, this must be the first call to save made
	 * for this object.
	 */
	public boolean saveToRepositoryAsGone() throws XMLStreamException, IOException {
		try {
			_lock.writeLock().lock();
			if ((null != _flowControl) && !(_flowControl instanceof RepositoryFlowControl)) {
				throw new IOException("Cannot call saveToRepository on raw object!");
			}
			_raw = false; // control what flow controller will be made
			return saveAsGone();
		} finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * Warning - calling this risks packet drops. It should only
	 * be used for tests or other special circumstances in which
	 * you "know what you are doing".
	 */
	public void disableFlowControl() {
		try {
			_lock.writeLock().lock();
			if (null != _flowControl)
				_flowControl.disable();
			_disableFlowControlRequest = true;
		} finally {
			_lock.writeLock().unlock();
		}
	}
	
	protected synchronized void newVersionAvailable() {
		// by default signal all waiters
		this.notifyAll();
	}
	
	/**
	 * Will return immediately if this object already has data, otherwise
	 * will wait for timeout msec for new data to appear.
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
	 * Will wait for timeout msec for data to arrive. Callers should use
	 * available() to determine whether data has arrived or not.
	 * If data already available, will return immediately.
	 * @param timeout If 0, will wait forever (if data does not arrive).
	 */
	public void waitForData(long timeout) {
		
		if (available())
			return;
		synchronized (this) {
			try {
				wait(timeout);
			} catch (InterruptedException e) {
			}
		}
	}

	public boolean isGone() {
		return _isGone;
	}
	
	@Override
	protected E data() throws ContentNotReadyException, ContentGoneException { 
		try {
			_lock.readLock().lock();
			if (isGone()) {
				throw new ContentGoneException("Content is gone!");
			}
			return super.data();
		} finally {
			_lock.readLock().unlock();
		}
	}
	
	@Override
	public void setData(E newData) {
		try {
			_lock.writeLock().lock();

			_isGone = false; // clear gone, even if we're setting to null; only saveAsGone can set as gone
			super.setData(newData);
		} finally {
			_lock.writeLock().unlock();
		}
	}
	
	public CCNTime getVersion() {
		try {
			_lock.readLock().lock();
			if (isSaved())
				return VersioningProfile.getVersionComponentAsTimestamp(getVersionComponent());
			return null;
		} finally {
			_lock.readLock().unlock();
		}
	}

	public ContentName getBaseName() {
		try {
			_lock.readLock().lock();
			return _baseName;
		} finally {
			_lock.readLock().unlock();
		}
	}
	
	public byte [] getVersionComponent() {
		try {
			_lock.readLock().lock();
			if (isSaved())
				return _currentVersionComponent;
			return null;
		} finally {
			_lock.readLock().unlock();
		}
	}
	
	/**
	 * If the object has been saved or read from the network, returns the (cached) versioned
	 * name. Otherwise returns the base name.
	 * @return
	 */
	public ContentName getVersionedName() {
		try {
			_lock.readLock().lock();

			if (isSaved()) {
				if (null == _currentVersionName) // cache; only read lock necessary
					_currentVersionName =  new ContentName(_baseName, _currentVersionComponent);
				return _currentVersionName;
			}
			return getBaseName();
		} finally {
			_lock.readLock().unlock();
		}
	}

	public PublisherPublicKeyDigest getContentPublisher() {
		try {
			_lock.readLock().lock();
			if (isSaved())
				return _currentPublisher;
			return null;
		} finally {
			_lock.readLock().unlock();
		}
	}
	
	public KeyLocator getPublisherKeyLocator() {
		try {
			_lock.readLock().lock();
			if (isSaved())
				return _currentPublisherKeyLocator;		
			return null;
		} finally {
			_lock.readLock().unlock();
		}
	}

	public Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
		try {
			_lock.writeLock().lock();
			for (ContentObject co : results) {
				try {
					Log.info("handleContent: " + _currentInterest + " retrieved " + co.name());
					if (VersioningProfile.startsWithLaterVersionOf(co.name(), _currentInterest.name())) {
						// OK, we have something that is a later version of our desired object.
						// We're not sure it's actually the first content segment.
						if (VersioningProfile.isVersionedFirstSegment(_currentInterest.name(), co, null)) {
							Log.info("Background updating of {0}, got first segment: {1}", getVersionedName(), co.name());
							update(co);
						} else {
							// Have something that is not the first segment, like a repo write or a later segment. Go back
							// for first segment.
							ContentName latestVersionName = co.name().cut(_currentInterest.name().count() + 1);
							Log.info("handleContent (network object): Have version information, now querying first segment of {0}", latestVersionName);
							update(latestVersionName, co.signedInfo().getPublisherKeyID());
						}
						_excludeList.clear();
						_currentInterest = null;
						newVersionAvailable(); // notify that a new version is available; perhaps move to real notify()
						if (_continuousUpdates) {
							// DKS TODO -- order with respect to newVersionAvailable and locking...
							updateInBackground(true);
						} else {
							_continuousUpdates = false;
						}
						return null; // implicit cancel of interest
					}
				} catch (IOException ex) {
					Log.info("Exception {0}: {1}  attempting to update based on object : {2}", ex.getClass().getName(), ex.getMessage(), co.name());
					// alright, that one didn't work, try to go on.    				
				} catch (XMLStreamException ex) {
					Log.info("Exception {0}: {1}  attempting to update based on object : {2}", ex.getClass().getName(), ex.getMessage(), co.name());
					// alright, that one didn't work, try to go on.
				} 

				_excludeList.add(co.name().component(_currentInterest.name().count() - 1));  
				Log.info("handleContent: got content for {0} that doesn't match: {1}", _currentInterest.name(), co.name());
			}
			byte [][] excludes = new byte[_excludeList.size()][];
			_excludeList.toArray(excludes);
			_currentInterest = Interest.last(_currentInterest.name(), excludes, null);
			return _currentInterest;
		} finally {
			_lock.writeLock().unlock();
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
				return getVersionedName() + ": " + (isGone() ? "GONE" : data());
			} else if (available()) {
				return getBaseName() + " (unsaved): " + data();
			} else {
				return getBaseName() + " (unsaved, no data)";	
			}
		} catch (IOException e) {
			Log.info("Unexpected exception retrieving object information: {0}", e);
			return getBaseName() + ": unexpected exception " + e;
		} 	
	}
}



