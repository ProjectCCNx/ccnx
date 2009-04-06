package com.parc.ccn.data.content;

import java.io.IOException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.util.EncodableObject;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.ccn.library.io.CCNOutputStream;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;

/**
 * Takes a class E, and backs it securely to CCN.
 * @author smetters
 *
 * @param <E>
 */
public class CCNEncodableObject<E extends GenericXMLEncodable> extends EncodableObject<E> {
	
	ContentName _currentName;
	CCNLibrary _library;

	public CCNEncodableObject(Class<E> type, CCNLibrary library) {
		super(type);
		_library = library;
	}
	
	public CCNEncodableObject(Class<E> type, E data, CCNLibrary library) {
		super(type, data);
		_library = library;
	}
	
	/**
	 * Construct an object from stored CCN data.
	 * @param type
	 * @param content The object to recover, or one of its fragments.
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNEncodableObject(Class<E> type, ContentObject content, CCNLibrary library) throws XMLStreamException, IOException {
		super(type);
		_library = library;
		CCNInputStream is = new CCNInputStream(content, library);
		is.seek(0); // In case we start with something other than the first fragment.
		update(is);
	}
	
	/**
	 * Ambiguous. Are we supposed to pull this object based on its name,
	 *   or merely attach the name to the object which we will then construct
	 *   and save. Let's assume the former, and allow the name to be specified
	 *   for save() for the latter.
	 * @param type
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNEncodableObject(Class<E> type, ContentName name, PublisherKeyID publisher, CCNLibrary library) throws XMLStreamException, IOException {
		super(type);
		_library = library;
		// DKS TODO need to use an input stream type that given a specific version
		// will pull it, but given an unversioned name will pull the latest version.
		CCNInputStream is = new CCNInputStream(name, publisher, library);
		update(is);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(type, name, null, library);
	}
	
	public void update() throws XMLStreamException, IOException {
		if (null == _currentName) {
			throw new IllegalStateException("Cannot retrieve an object without giving a name!");
		}
		// Look for latest version.
		update(VersioningProfile.versionRoot(_currentName));
	}
	
	/**
	 * Load data into object. If name is versioned, load that version. If
	 * name is not versioned, look for latest version. CCNInputStream doesn't
	 * have that property at the moment.
	 * @param name
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	public void update(ContentName name) throws XMLStreamException, IOException {
		// Either get the latest version name and call CCNInputStream, or
		// better yet, use the appropriate versioning stream.
		CCNInputStream is = new CCNInputStream(name, _library);
		update(is);
		_currentName = is.baseName();
	}
	
	public void update(ContentObject object) throws XMLStreamException, IOException {
		CCNInputStream is = new CCNInputStream(object, _library);
		update(is);
		_currentName = SegmentationProfile.segmentRoot(object.name());
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
	
	/**
	 * Save content to specific name. If versioned, assume that is the desired
	 * version. If not, add a version to it.
	 * @param name
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	public void save(ContentName name) throws XMLStreamException, IOException {
		// move object to this name
		// TODO
		// need to make sure we get back the actual name we're using,
		// even if output stream does automatic versioning
		// probably need to refactor save behavior -- right now, internalWriteObject
		// either writes the object or not; we need to only make a new name if we do
		// write the object, and figure out if that's happened. Also need to make
		// parent behavior just write, put the dirty check higher in the state.
		if (!isDirty()) { // Should we check potentially dirty?
			Library.logger().info("Object not dirty. Not saving.");
		}
		if (null == name) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		// CCNOutputStream will currently version an unversioned name, but dont'
		// expect it should continue doing that. If it gets a versioned name, will respect it.
		CCNOutputStream cos = new CCNOutputStream(name, _library);
		save(cos); // superclass stream save. calls flush and close on a wrapping
					// digest stream; want to make sure we end up with a single non-MHT signed
				    // block and no header on small objects
		cos.close();
		_currentName = name;
		setPotentiallyDirty(false);
	}
	
	public Timestamp getVersion() {
		if ((null == _currentName) || (null == _lastSaved)) {
			return null;
		}
		try {
			return VersioningProfile.getVersionAsTimestamp(_currentName);
		} catch (VersionMissingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}
}
