package com.parc.ccn.data.util;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.ccn.library.io.CCNVersionedInputStream;
import com.parc.ccn.library.io.CCNVersionedOutputStream;
import com.parc.ccn.library.io.repo.RepositoryFlowControl;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;

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

	protected ContentName _currentName;
	protected CCNLibrary _library;
	protected CCNFlowControl _flowControl;
	protected boolean _isGone = false;
	// control ongoing update.
	ArrayList<byte[]> _excludeList = new ArrayList<byte[]>();
	Interest _currentInterest = null;
	boolean _continuousUpdates = false;
	
	protected static boolean DEFAULT_RAW = true;
	
	/**
	 * Basic write constructor.
	 * Setting true or false in this constructor determines default -- repo or raw objects.
	 * @param type
	 * @param name
	 * @param data
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public CCNNetworkObject(Class<E> type, ContentName name, E data, CCNLibrary library) throws IOException {
		this(type, name, data, DEFAULT_RAW, library);
	}
		
	/**
	 * A raw object uses a raw flow controller. A non-raw object uses a repository backend.
	 * repo flow controller.
	 * @param type
	 * @param name
	 * @param data
	 * @param raw
	 * @param library
	 * @throws IOException
	 */
	public CCNNetworkObject(Class<E> type, ContentName name, E data, boolean raw, CCNLibrary library) throws IOException {
		// Don't start pulling a namespace till we actually write something. We may never write
		// anything on this object.
		this(type, name, data, 
			(raw ? new CCNFlowControl(library) : new RepositoryFlowControl(library)));
	}

	/**
	 * Write constructors. This allows subclasses or users to pass in new forms of flow controller.
	 * You should only use this one if you really know what you are doing.
	 * 
	 * For now, flowControl assumed to already be handling namespace name.
	 * @param type
	 * @param name
	 * @param data
	 * @param flowControl
	 * @throws IOException
	 */
	protected CCNNetworkObject(Class<E> type, ContentName name, E data, CCNFlowControl flowControl) throws IOException {
		super(type, data);
		_flowControl = flowControl;
		_library = flowControl.getLibrary();
		_currentName = name;
	}

	/**
	 * Read constructors. Will try to pull latest version of this object, or a specific
	 * named version. Flow controller assumed to already be set to handle this namespace.
	 * @param type
	 * @param name
	 * @param publisher
	 * @param library
	 * @throws ConfigurationException
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	public CCNNetworkObject(Class<E> type, ContentName name, 
			CCNLibrary library) throws IOException, XMLStreamException {
		this(type, name, (PublisherPublicKeyDigest)null, library);
	}

	public CCNNetworkObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws IOException, XMLStreamException {
		this(type, name, publisher, DEFAULT_RAW, library);
	}

	protected CCNNetworkObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNFlowControl flowControl) throws IOException, XMLStreamException {
		super(type);
		_flowControl = flowControl;
		_library = flowControl.getLibrary();
		update(name, publisher);
	}

	public CCNNetworkObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			boolean raw, CCNLibrary library) throws IOException, XMLStreamException {
		this(type, name, publisher, 
				(raw ? new CCNFlowControl(library) : new RepositoryFlowControl(library)));
	}

	/**
	 * Read constructors if you already have a block of the object. Used by streams.
	 * @param type
	 * @param firstBlock
	 * @param library
	 * @throws ConfigurationException
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	public CCNNetworkObject(Class<E> type, ContentObject firstBlock, CCNLibrary library) throws IOException, XMLStreamException {
		this(type, firstBlock, DEFAULT_RAW, library);
	}
	
	public CCNNetworkObject(Class<E> type, ContentObject firstBlock,
			boolean raw, CCNLibrary library) throws IOException, XMLStreamException {
		this(type, firstBlock, 
				(raw ? new CCNFlowControl(library) : 
					   new RepositoryFlowControl(library)));
	}

	protected CCNNetworkObject(Class<E> type, ContentObject firstBlock,
			CCNFlowControl flowControl) throws IOException, XMLStreamException {
		super(type);
		_flowControl = flowControl;
		_library = flowControl.getLibrary();
		update(firstBlock);
	}
	
	public void update() throws XMLStreamException, IOException {
		if (null == _currentName) {
			throw new IllegalStateException("Cannot retrieve an object without giving a name!");
		}
		// Look for latest version.
		update(VersioningProfile.versionRoot(_currentName), null);
	}

	/**
	 * Load data into object. If name is versioned, load that version. If
	 * name is not versioned, look for latest version. CCNInputStream doesn't
	 * have that property at the moment.
	 * @param name
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws ClassNotFoundException 
	 */
	public void update(ContentName name, PublisherPublicKeyDigest publisher) throws XMLStreamException, IOException {
		Library.logger().info("Updating object to " + name);
		CCNVersionedInputStream is = new CCNVersionedInputStream(name, publisher, _library);
		update(is);
	}

	public void update(ContentObject object) throws XMLStreamException, IOException {
		CCNInputStream is = new CCNInputStream(object, _library);
		is.seek(0); // in case it wasn't the first block
		update(is);
	}

	public void update(CCNInputStream inputStream) throws IOException, XMLStreamException {
		if (inputStream.isGone()) {
			_isGone = true;
			_currentName = inputStream.deletionInformation().name();
		} else {
			_isGone = false;
			super.update(inputStream);
			_currentName = inputStream.baseName();
		}
	}
	
	public void updateInBackground() throws IOException {
		updateInBackground(false);
	}
	
	public void updateInBackground(boolean continuousUpdates) throws IOException {
		if (null == _currentName) {
			throw new IllegalStateException("Cannot retrieve an object without giving a name!");
		}
		// Look for latest version.
		updateInBackground(_currentName, continuousUpdates);
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
		
		Library.logger().info("getFirstBlock: getting latest version after " + latestVersionKnown + " in background.");
		if (!VersioningProfile.isVersioned(latestVersionKnown)) {
			latestVersionKnown = VersioningProfile.versionName(latestVersionKnown, VersioningProfile.baseVersion());
		}
		// DKS TODO locking?
		cancelInterest();
		// express this
		_continuousUpdates = continuousUpdates;
		_currentInterest = Interest.last(latestVersionKnown, null, null);
		_library.expressInterest(_currentInterest, this);
	}
	
	public void cancelInterest() {
		_continuousUpdates = false;
		if (null != _currentInterest) {
			_library.cancelInterest(_currentInterest, this);
		}
		_excludeList.clear();
	}

	/**
	 * Save to existing name, if content is dirty. Update version.
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	public void save() throws XMLStreamException, IOException {
		if (null == _currentName) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		save(VersioningProfile.versionName(_currentName));
	}

	@Override
	public void setData(E data) {
		super.setData(data);
		_isGone = false;
	}

	/**
	 * Save content to specific name. If versioned, assume that is the desired
	 * version. If not, add a version to it.
	 * @param name
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	public void save(ContentName name) throws XMLStreamException, IOException {
		// move object to this name
		// need to make sure we get back the actual name we're using,
		// even if output stream does automatic versioning
		// probably need to refactor save behavior -- right now, internalWriteObject
		// either writes the object or not; we need to only make a new name if we do
		// write the object, and figure out if that's happened. Also need to make
		// parent behavior just write, put the dirty check higher in the state.

		if (!isDirty()) { // Should we check potentially dirty?
			Library.logger().info("Object not dirty. Not saving.");
			return;
		}
		if (null == name) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		// Have to register the version root. If we just register this specific version, we won't
		// see any shorter interests -- i.e. for get latest version.
		_flowControl.addNameSpace(VersioningProfile.versionRoot(name));
		// CCNVersionedOutputStream will version an unversioned name. 
		// If it gets a versioned name, will respect it.
		CCNVersionedOutputStream cos = new CCNVersionedOutputStream(name, null, null, _flowControl);
		save(cos); // superclass stream save. calls flush but not close on a wrapping
		// digest stream; want to make sure we end up with a single non-MHT signed
		// block and no header on small objects
		cos.close();
		_currentName = cos.getBaseName();
		setPotentiallyDirty(false);
	}
	
	public void save(E data) throws XMLStreamException, IOException {
		setData(data);
		save();
	}
	
	public void save(ContentName name, E data) throws XMLStreamException, IOException {
		setData(data);
		save(name);
	}

	/**
	 * Save this object as GONE. Intended to mark the latest version, rather
	 * than a specific version as GONE. So for now, require that name handed in
	 * is *not* already versioned; throw an IOException if it is.
	 * @param name
	 * @throws IOException
	 */
	public void saveAsGone(ContentName name) throws IOException {
		
		if (VersioningProfile.isVersioned(name)) {
			throw new IOException("Cannot save past versions as gone!");
		}
		name = VersioningProfile.versionName(name);
		name = SegmentationProfile.segmentName(name, SegmentationProfile.BASE_SEGMENT );

		byte [] empty = { };
		ContentObject goneObject = ContentObject.buildContentObject(name, ContentType.GONE, empty);
		// Have to register the version root. If we just register this specific version, we won't
		// see any shorter interests -- i.e. for get latest version.
		_flowControl.addNameSpace(VersioningProfile.versionRoot(name));
		_flowControl.put(goneObject);
		_currentName = name;
		_data = null;
		_isGone = true;
		setPotentiallyDirty(false);
	}
	
	public void saveAsGone() throws IOException {
		if (null == _currentName) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		saveAsGone(VersioningProfile.versionRoot(_currentName));
	}

	public Timestamp getVersion() {
		if ((null == _currentName) || (null == _lastSaved)) {
			return null;
		}
		try {
			return VersioningProfile.getVersionAsTimestamp(_currentName);
		} catch (VersionMissingException e) {
			return null;
		}
	}

	public ContentName getName() {
		return _currentName;
	}
	
	protected void newVersionAvailable() {
		// by default signal all waiters
		this.notifyAll();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result
		+ ((_currentName == null) ? 0 : _currentName.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		CCNNetworkObject<?> other = (CCNNetworkObject<?>) obj;
		if (_currentName == null) {
			if (other._currentName != null)
				return false;
		} else if (!_currentName.equals(other._currentName))
			return false;
		return true;
	}

	public boolean isGone() {
		return _isGone;
	}
	
	public boolean ready() {
		if (super.ready() || isGone())
			return true;
		return false;
	}

	public Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
    	// Do we have a version?
    	// DKS note -- this code from getVersionInternal in CCNLibrary. It doesn't actually
    	// confirm that the result is versioned.
    	// DKS TODO timeout?
    	for (ContentObject co : results) {
    		// This test may not be correct.
    		if (VersioningProfile.versionRoot(co.name()).equals(VersioningProfile.versionRoot(_currentInterest.name()))) {
    			// OK, we have something that is a later version of our desired object.
    			// We're not sure it's actually the first content block.
    			try {
    				if (SegmentationProfile.isFirstSegment(co.name())) {
    					update(co);
    				} else {
    					// Have a later segment. Caching problem. Go back for first segment.
    					update(SegmentationProfile.segmentRoot(co.name()), co.signedInfo().getPublisherKeyID());
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
   			} catch (IOException ex) {
    				Library.logger().info("Exception " + ex.getClass().getName() + ": " + ex.getMessage() + " attempting to update based on object : " + co.name());
    				// alright, that one didn't work, try to go on.    				
    			} catch (XMLStreamException ex) {
       				Library.logger().info("Exception " + ex.getClass().getName() + ": " + ex.getMessage() + " attempting to update based on object : " + co.name());
        			// alright, that one didn't work, try to go on.
    			}
    		}
    		_excludeList.add(co.name().component(_currentInterest.name().count() - 1));  
    		Library.logger().info("handleContent: got content for " + _currentInterest.name() + " that doesn't match: " + co.name());
    	}
   		byte [][] excludes = new byte[_excludeList.size()][];
		_excludeList.toArray(excludes);
 		_currentInterest = Interest.last(_currentInterest.name(), excludes, null);
 		return _currentInterest;
	}
}