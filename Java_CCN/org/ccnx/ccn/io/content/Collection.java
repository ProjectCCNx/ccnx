package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * Mapping from a collection to the underlying XML representation.
 * Basically a collection is a content object containing
 * a List of links -- names and optionally some content authentication
 * information to specify whose value for each name
 * to take.
 * @author smetters
 *
 */
public class Collection extends GenericXMLEncodable implements XMLEncodable {
	
	/**
	 * This should eventually be called Collection, and the Collection class deleted.
	 */
	public static class CollectionObject extends CCNEncodableObject<Collection> {
		
		/**
		 * Write constructor. Doesn't save until you call save, in case you want to tweak things first.
		 * @param name
		 * @param data
		 * @param library
		 * @throws ConfigurationException
		 * @throws IOException
		 */
		public CollectionObject(ContentName name, Collection data, CCNHandle library) throws IOException {
			super(Collection.class, name, data, library);
		}
		
		public CollectionObject(ContentName name, java.util.Collection<Link> contents, CCNHandle library) throws IOException {
			this(name, new Collection(contents), library);
		}
		
		public CollectionObject(ContentName name, Link [] contents, CCNHandle library) throws IOException {
			this(name, new Collection(contents), library);			
		}

		public CollectionObject(ContentName name, Collection data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle library) throws IOException {
			super(Collection.class, name, data, publisher, keyLocator, library);
		}

		public CollectionObject(ContentName name, java.util.Collection<Link> contents, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle library) throws IOException {
			this(name, new Collection(contents), publisher, keyLocator, library);
		}
		
		public CollectionObject(ContentName name, Link [] contents, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle library) throws IOException {
			this(name, new Collection(contents), publisher, keyLocator, library);			
		}

		/**
		 * Read constructor -- opens existing object.
		 * @param name
		 * @param library
		 * @throws XMLStreamException
		 * @throws IOException
		 * @throws ClassNotFoundException 
		 */
		public CollectionObject(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle library) throws IOException, XMLStreamException {
			super(Collection.class, name, publisher, library);
		}
		
		public CollectionObject(ContentName name, CCNHandle library) throws IOException, XMLStreamException {
			super(Collection.class, name, (PublisherPublicKeyDigest)null, library);
		}
		
		public CollectionObject(ContentObject firstBlock, CCNHandle library) throws IOException, XMLStreamException {
			super(Collection.class, firstBlock, library);
		}
		
		public Collection collection() {
			return data();
		}
		
		public LinkedList<Link> contents() { 
			if (null == data())
				return null;
			return data().contents(); 
		}
	}
	
	protected static final String COLLECTION_ELEMENT = "Collection";

	protected LinkedList<Link> _contents = new LinkedList<Link>();
	
	public Collection() {
	}
	
	public Collection clone() {
		return new Collection(_contents);
	}
	
	public Collection(java.util.Collection<Link> contents) {
		_contents.addAll(contents); // should we clone each?
	}
	
	public Collection(Link [] contents) {
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
		final Collection other = (Collection) obj;
		if (_contents == null) {
			if (other._contents != null)
				return false;
		} else if (!_contents.equals(other._contents))
			return false;
		return true;
	}
}
