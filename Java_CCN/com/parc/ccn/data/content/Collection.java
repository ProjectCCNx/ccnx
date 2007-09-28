package com.parc.ccn.data.content;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.ContentAuthenticator;
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
public class Collection implements XMLEncodable {
	
	protected static final String COLLECTION_ELEMENT = "Collection";

	private static final String ENTRY_ELEMENT = null;
	
	protected HashMap<ContentName,ContentAuthenticator> _contents = new HashMap<ContentName,ContentAuthenticator>();
	
	public Collection(ContentName destName, ContentAuthenticator destAuthenticator) {
		_contents.put(destName, destAuthenticator);
	}
	
	public Collection(ContentName [] names, ContentAuthenticator [] authenticators) {
		if ((names == null) || (names.length == 0) ||
			((null != authenticators) && (authenticators.length > 0) && (names.length != authenticators.length))) {
			throw new IllegalArgumentException("Collections must contain names, and either no authenticators or the same number of authenticators as names.");
		}
		for (int i=0; i < names.length; ++i) {
			if ((null != authenticators) && (authenticators.length > 0)) {
				_contents.put(names[i], authenticators[i]);
			} else {
				_contents.put(names[i], null);
			}
		}
	}
	
	public Collection(HashMap<ContentName,ContentAuthenticator> contents) {
		Iterator<Map.Entry<ContentName,ContentAuthenticator>> it = contents.entrySet().iterator();
		while (it.hasNext()) {
			_contents.entrySet().add(it.next());
		}
	}
	
	public Collection(ContentName destName) {
		this(destName, null);
	}
	
	public Collection(InputStream iStream) throws XMLStreamException {
		decode(iStream);
	}
	
	public HashMap<ContentName,ContentAuthenticator> contents() { return _contents; }
	public Set<ContentName> names() { return contents().keySet(); }
		
	public ContentAuthenticator authenticator(ContentName name) {
		return contents().get(name);
	}
	
	public void add(ContentName name, ContentAuthenticator authenticator) {
		_contents.put(name, authenticator);
	}
	
	/**
	 * XML format:
	 * @throws XMLStreamException 
	 * 
	 */
	
	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
	}
	
	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer);
		XMLHelper.endEncoding(writer);	
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		_contents.clear();
		
		XMLHelper.readStartElement(reader, COLLECTION_ELEMENT);

		ContentName name = null;
		ContentAuthenticator authenticator = null;
		while ((null != reader.peek()) && (XMLHelper.peekStartElement(reader, ENTRY_ELEMENT))) {
			XMLHelper.readStartElement(reader, ENTRY_ELEMENT);
			name = new ContentName();
			name.decode(reader);
			if (XMLHelper.peekStartElement(reader, ContentAuthenticator.CONTENT_AUTHENTICATOR_ELEMENT)) {
				authenticator = new ContentAuthenticator();
				authenticator.decode(reader);
			}
			_contents.put(name, authenticator);
			XMLHelper.readEndElement(reader);
		}
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		writer.writeStartElement(COLLECTION_ELEMENT);
		Iterator<ContentName> keyIt = names().iterator();
		while (keyIt.hasNext()) {
			writer.writeStartElement(ENTRY_ELEMENT);
			ContentName name = keyIt.next();
			name.encode(writer);
			ContentAuthenticator auth = contents().get(name);
			if (null != auth) {
				auth.encode(writer);
			}
			writer.writeEndElement();
		}
		writer.writeEndElement();   		
	}
}
