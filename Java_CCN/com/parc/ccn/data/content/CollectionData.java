package com.parc.ccn.data.content;

import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

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
	
	protected static final String COLLECTION_ELEMENT = "Collection";

	protected ArrayList<LinkReference> _contents = new ArrayList<LinkReference>();
	
	public CollectionData() {
	}
	
	/**
	 * Need to make final objects sometimes, for which we
	 * need an atomic create from byte array option. But
	 * if we do it with a constructor, we run into the problem
	 * that each subclass must reimplement it, to be sure
	 * that their members are constructed prior to decoding.
	 * So do it this way.
	 * @throws XMLStreamException 
	 */
	public static Collection newCollection(byte [] encodedCollection) throws XMLStreamException {
		Collection newCollection = new Collection();
		newCollection.decode(encodedCollection);
		return newCollection;
	}
	public ArrayList<LinkReference> contents() { 
		return _contents; 
	}
		
	public LinkReference get(int i) {
		return contents().get(i);
	}
	
	public void add(LinkReference content) {
		_contents.add(content);
	}
	
	public void remove(int i) {
		_contents.remove(i);
	}
	
	public void remove(Link content) {
		_contents.remove(content);
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

		LinkReference link = null;
		while (decoder.peekStartElement(Link.LINK_ELEMENT)) {
			link = new LinkReference();
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
		Iterator<LinkReference> linkIt = contents().iterator();
		while (linkIt.hasNext()) {
			LinkReference link = linkIt.next();
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
