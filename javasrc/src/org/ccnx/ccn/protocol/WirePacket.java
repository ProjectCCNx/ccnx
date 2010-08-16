/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.protocol;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;


public class WirePacket extends GenericXMLEncodable implements XMLEncodable {
	
	protected List<GenericXMLEncodable> _contents = null; 

	public WirePacket() {
		// Empty packet won't generate NullPointerException
		_contents = new ArrayList<GenericXMLEncodable>();
	}; // for use by decoders

	public WirePacket(List<GenericXMLEncodable> contents) {
		_contents = contents;
	}
	
	public WirePacket(GenericXMLEncodable contents) {
		_contents = new ArrayList<GenericXMLEncodable>(1);
		_contents.add(contents);
	}
	
	public void clear() {
		_contents.clear();
	}
	
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		boolean done = false;
		_contents = new ArrayList<GenericXMLEncodable>();
		
		while (!done) {
			if (decoder.peekStartElement(CCNProtocolDTags.Interest)) {
				Interest interest = new Interest();
				interest.decode(decoder);
				_contents.add(interest);
			} else if (decoder.peekStartElement(CCNProtocolDTags.ContentObject)) {
				ContentObject data = new ContentObject();
				data.decode(decoder);
				if( Log.isLoggable(Level.FINEST) )
					SystemConfiguration.logObject(Level.FINEST, "packetDecode", data);
				_contents.add(data);
			} else {
				done = true;
				if (_contents.size() == 0) {
					throw new ContentDecodingException("Unrecognized packet content");
				}
			}
		}
		Log.finest("Finished decoding wire packet.");
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": bad or missing values.");
		}
		for (Iterator<GenericXMLEncodable> iterator = _contents.iterator(); iterator.hasNext();) {
			GenericXMLEncodable item = iterator.next();
			item.encode(encoder);
		}
	}

	@Override
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
	
	@Override
	public long getElementLabel() { // unused, we add nothing to encoding
		return -1;
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
