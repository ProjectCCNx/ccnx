package com.parc.ccn.data.content;

import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

/**
 * Mapping from a collection to the underlying XML representation.
 * Basically a collection is a content object containing
 * a List of names and optionally some content authentication
 * information to specify whose value for each name
 * to take.
 * @author smetters
 *
 */
public class Collection extends GenericXMLEncodable implements XMLEncodable {
	
	protected static final String COLLECTION_ELEMENT = "Collection";

	protected ArrayList<Link> _contents = new ArrayList<Link>();
	
	public Collection(Link destination) {
		_contents.add(destination);
	}
	
	public Collection(ContentName destName) {
		_contents.add(new Link(destName));
	}
	
	public Collection(Link [] links) {
		for (int i=0; i < links.length; ++i) {
			_contents.add(links[i]);
		}
	}
			
	public Collection(ArrayList<Link> contents) {
		Iterator<Link> it = contents.iterator();
		while (it.hasNext()) {
			_contents.add(it.next());
		}
	}
	
	public Collection() {
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
	public ArrayList<Link> contents() { 
		return _contents; 
	}
		
	public Link get(int i) {
		return contents().get(i);
	}
	
	public void add(Link content) {
		_contents.add(content);
	}
	
	public void add(ContentName name) {
		_contents.add(new Link(name, null));
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
	public void decode(XMLEventReader reader) throws XMLStreamException {
		_contents.clear();
		
		XMLHelper.readStartElement(reader, COLLECTION_ELEMENT);

		Link link = null;
		while ((null != reader.peek()) && (XMLHelper.peekStartElement(reader, Link.LINK_ELEMENT))) {
			link = new Link();
			link.decode(reader);
			add(link);
		}
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, COLLECTION_ELEMENT, isFirstElement);
		Iterator<Link> linkIt = contents().iterator();
		while (linkIt.hasNext()) {
			Link link = linkIt.next();
			link.encode(writer);
		}
		writer.writeEndElement();   		
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
