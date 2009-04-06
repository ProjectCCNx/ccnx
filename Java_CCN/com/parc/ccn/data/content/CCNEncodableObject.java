package com.parc.ccn.data.content;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.util.EncodableObject;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
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
	
	public void update() {
		// TODO
	}
	
	public void update(ContentName name) {
		// TODO
	}
	
	public void save() {
		if (null == _currentName) {
			throw new IllegalStateException("Cannot save an object without giving it a name!");
		}
		// TODO
		// need to make sure we get back the actual name we're using,
		// even if output stream does automatic versioning
		// probably need to refactor save behavior -- right now, internalWriteObject
		// either writes the object or not; we need to only make a new name if we do
		// write the object, and figure out if that's happened. Also need to make
		// parent behavior just write, put the dirty check higher in the state.
		
		// need to make sure output stream
	}
	
	public void save(ContentName name) {
		// move object to this name
		// TODO
	}
	
	/**
	 * DKS TODO -- return timestamp instead of name?
	 * @return
	 */
	public Long getVersion() {
		if ((null == _currentName) || (null == _lastSaved)) {
			return null;
		}
		return VersioningProfile.getVersionNumber(_currentName);
	}
}
