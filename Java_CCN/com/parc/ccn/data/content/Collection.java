package com.parc.ccn.data.content;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

/**
 * Mapping from a collection to the underlying XML representation.
 * Basically a collection is a content object containing
 * a list of names and optionally some content authentication
 * information to specify whose value for each name
 * to take.
 * @author smetters
 *
 */
public class Collection extends GenericXMLEncodable implements XMLEncodable {
	
	protected static final String COLLECTION_ELEMENT = "Collection";

	protected static final String ENTRY_ELEMENT = "Entry";
	
	protected ArrayList<CompleteName> _contents = new ArrayList<CompleteName>();
	
	public Collection(ContentName destName, ContentAuthenticator destAuthenticator) {
		_contents.add(new CompleteName(destName, destAuthenticator));
	}
	
	public Collection(CompleteName destName) {
		_contents.add(destName);
	}
	
	public Collection(ContentName [] names, ContentAuthenticator [] authenticators) {
		if ((names == null) || (names.length == 0) ||
			((null != authenticators) && (authenticators.length > 0) && (names.length != authenticators.length))) {
			throw new IllegalArgumentException("Collections must contain names, and either no authenticators or the same number of authenticators as names.");
		}
		for (int i=0; i < names.length; ++i) {
			if ((null != authenticators) && (authenticators.length > 0)) {
				_contents.add(new CompleteName(names[i], authenticators[i]));
			} else {
				_contents.add(new CompleteName(names[i], null));
			}
		}
	}
	
	public Collection(CompleteName [] names) {
		if (null != names) {
			for (int i=0; i < names.length; ++i) {
				if (null != names[i])
					_contents.add(names[i]);
			}
		}
	}
	
	/**
	 * Can use ContentObjects with empty content to group
	 * names and authenticators.
	 * @param objects
	 */
	public Collection(ContentObject [] objects) {
		for (int i=0; i < objects.length; ++i) {
			_contents.add(objects[i].completeName());
		}
	}
	
	public Collection(ArrayList<CompleteName> contents) {
		Iterator<CompleteName> it = contents.iterator();
		while (it.hasNext()) {
			_contents.add(it.next());
		}
	}
	
	public Collection(ContentName destName) {
		this(destName, null);
	}
	
	public Collection(InputStream iStream) throws XMLStreamException {
		decode(iStream);
	}
	
	public ArrayList<CompleteName> contents() { return _contents; }
		
	public CompleteName get(int i) {
		return contents().get(i);
	}
	
	public void add(ContentName name, ContentAuthenticator authenticator) {
		_contents.add(new CompleteName(name, authenticator));
	}
	
	public void add(CompleteName name) {
		_contents.add(name);
	}
	/**
	 * XML format:
	 * @throws XMLStreamException 
	 * 
	 */
	
	public void decode(XMLEventReader reader) throws XMLStreamException {
		_contents.clear();
		
		XMLHelper.readStartElement(reader, COLLECTION_ELEMENT);

		CompleteName completeName = null;
		while ((null != reader.peek()) && (XMLHelper.peekStartElement(reader, ENTRY_ELEMENT))) {
			XMLHelper.readStartElement(reader, ENTRY_ELEMENT);
			completeName = new CompleteName();
			completeName.decode(reader);
			add(completeName);
			XMLHelper.readEndElement(reader);
		}
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, COLLECTION_ELEMENT, isFirstElement);
		Iterator<CompleteName> keyIt = contents().iterator();
		while (keyIt.hasNext()) {
			CompleteName name = keyIt.next();
			name.encode(writer);
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
