package com.parc.ccn.data;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

public class WirePacket extends GenericXMLEncodable implements XMLEncodable {
	
	protected List<GenericXMLEncodable> _contents = null; 

	public WirePacket() {}; // for use by decoders

	public WirePacket(List<GenericXMLEncodable> contents) {
		_contents = contents;
	}
	
	public WirePacket(GenericXMLEncodable contents) {
		_contents = new ArrayList<GenericXMLEncodable>(1);
		_contents.add(contents);
	}
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		boolean done = false;
		_contents = new ArrayList<GenericXMLEncodable>();
		while (!done)
		if (decoder.peekStartElement(Interest.INTEREST_ELEMENT)) {
			Interest interest = new Interest();
			interest.decode(decoder);
			_contents.add(interest);
		} else if (decoder.peekStartElement(ContentObject.CONTENT_OBJECT_ELEMENT)) {
			ContentObject data = new ContentObject();
			data.decode(decoder);
			_contents.add(data);
		} else {
			done = true;
			if (_contents.size() == 0) {
				throw new XMLStreamException("Unrecognized packet content");
			}
		}
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": bad or missing values.");
		}
		for (Iterator<GenericXMLEncodable> iterator = _contents.iterator(); iterator.hasNext();) {
			GenericXMLEncodable item = iterator.next();
			item.encode(encoder);
		}
	}

	public boolean validate() {
		if (_contents.size() < 1) {
			return false;
		}
		for (Iterator<GenericXMLEncodable> iterator = _contents.iterator(); iterator.hasNext();) {
			GenericXMLEncodable item = (GenericXMLEncodable) iterator.next();
			if ( ! (item instanceof Interest || item instanceof ContentObject) )  {
				return false;
			}
		}
		return true;
	}
	
	public void add(ContentObject data) {
		_contents.add(data);
	}
	
	public void add(Interest interest) {
		_contents.add(interest);
	}
	
	public List<Interest> interests() {
		List<Interest> result = new ArrayList<Interest>(_contents.size());
		for (Iterator<GenericXMLEncodable> iterator = _contents.iterator(); iterator.hasNext();) {
			GenericXMLEncodable item = iterator.next();
			if (item instanceof Interest)  {
				result.add((Interest)item);
			}
		}
		return result;
	}

	public List<ContentObject> data() {
		List<ContentObject> result = new ArrayList<ContentObject>(_contents.size());
		for (Iterator<GenericXMLEncodable> iterator = _contents.iterator(); iterator.hasNext();) {
			GenericXMLEncodable item = iterator.next();
			if (item instanceof ContentObject)  {
				result.add((ContentObject)item);
			}
		}
		return result;
	}

}
