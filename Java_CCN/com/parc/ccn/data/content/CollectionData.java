package com.parc.ccn.data.content;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.library.CCNLibrary;

/**
 * Mapping from a collection to the underlying XML representation.
 * Basically a collection is a content object containing
 * a List of names and optionally some content authentication
 * information to specify whose value for each name
 * to take.
 * @author smetters
 *
 */
public class CollectionData extends GenericXMLEncodable implements XMLEncodable {
	
	/**
	 * This should eventually be called Collection, and the Collection class deleted.
	 */
	public static class CollectionObject extends CCNEncodableObject<CollectionData> {
		
		/**
		 * Write constructor. Doesn't save until you call save, in case you want to tweak things first.
		 * @param name
		 * @param data
		 * @param library
		 * @throws ConfigurationException
		 * @throws IOException
		 */
		public CollectionObject(ContentName name, CollectionData data, CCNLibrary library) throws IOException {
			super(CollectionData.class, name, data, library);
		}
		
		public CollectionObject(ContentName name, java.util.Collection<Link> contents, CCNLibrary library) throws IOException {
			this(name, new CollectionData(contents), library);
		}
		
		public CollectionObject(ContentName name, Link [] contents, CCNLibrary library) throws IOException {
			this(name, new CollectionData(contents), library);			
		}

		public CollectionObject(ContentName name, CollectionData data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNLibrary library) throws IOException {
			super(CollectionData.class, name, data, publisher, keyLocator, library);
		}

		public CollectionObject(ContentName name, java.util.Collection<Link> contents, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNLibrary library) throws IOException {
			this(name, new CollectionData(contents), publisher, keyLocator, library);
		}
		
		public CollectionObject(ContentName name, Link [] contents, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNLibrary library) throws IOException {
			this(name, new CollectionData(contents), publisher, keyLocator, library);			
		}

		/**
		 * Read constructor -- opens existing object.
		 * @param name
		 * @param library
		 * @throws XMLStreamException
		 * @throws IOException
		 * @throws ClassNotFoundException 
		 */
		public CollectionObject(ContentName name, PublisherPublicKeyDigest publisher, CCNLibrary library) throws IOException, XMLStreamException {
			super(CollectionData.class, name, publisher, library);
		}
		
		public CollectionObject(ContentName name, CCNLibrary library) throws IOException, XMLStreamException {
			super(CollectionData.class, name, (PublisherPublicKeyDigest)null, library);
		}
		
		public CollectionObject(ContentObject firstBlock, CCNLibrary library) throws IOException, XMLStreamException {
			super(CollectionData.class, firstBlock, library);
		}
		
		public LinkedList<Link> contents() { 
			if (null == data())
				return null;
			return data().contents(); 
		}
	}
	
	protected static final String COLLECTION_ELEMENT = "Collection";

	protected LinkedList<Link> _contents = new LinkedList<Link>();
	
	public CollectionData() {
	}
	
	public CollectionData clone() {
		return new CollectionData(_contents);
	}
	
	public CollectionData(java.util.Collection<Link> contents) {
		_contents.addAll(contents); // should we clone each?
	}
	
	public CollectionData(Link [] contents) {
		if (contents != null) {
			for (int i=0; i < contents.length; ++i) {
				_contents.add(contents[i]);
			}
		}
	}
	
	public LinkedList<Link> contents() { 
		return _contents; 
	}
		
	public Link get(int i) {
		return contents().get(i);
	}
	
	public void add(Link content) {
		_contents.add(content);
	}
	
	public void add(ArrayList<Link> contents) {
		_contents.addAll(contents);
	}
	
	public Link remove(int i) {
		return _contents.remove(i);
	}
	
	public boolean remove(Link content) {
		return _contents.remove(content);
	}

	public void removeAll() {
		_contents.clear();
	}
	
	public int size() { return _contents.size(); }
	
	/**
	 * XML format:
	 * @throws XMLStreamException 
	 * 
	 */
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		_contents.clear();
		
		decoder.readStartElement(COLLECTION_ELEMENT);

		Link link = null;
		while (decoder.peekStartElement(Link.LINK_ELEMENT)) {
			link = new Link();
			link.decode(decoder);
			add(link);
		}
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(COLLECTION_ELEMENT);
		Iterator<Link> linkIt = contents().iterator();
		while (linkIt.hasNext()) {
			Link link = linkIt.next();
			link.encode(encoder);
		}
		encoder.writeEndElement();   		
	}
	
	public boolean validate() { 
		return (null != contents());
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_contents == null) ? 0 : _contents.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final CollectionData other = (CollectionData) obj;
		if (_contents == null) {
			if (other._contents != null)
				return false;
		} else if (!_contents.equals(other._contents))
			return false;
		return true;
	}
}
