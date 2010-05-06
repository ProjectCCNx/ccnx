/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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
	
	protected GenericXMLEncodable _content = null; 

	public WirePacket() {
	}; // for use by decoders
	
	public WirePacket(GenericXMLEncodable content) {
		_content = content;
	}
	
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		if (decoder.peekStartElement(CCNProtocolDTags.Interest)) {
			_content = new Interest();
			_content.decode(decoder);
		} else if (decoder.peekStartElement(CCNProtocolDTags.ContentObject)) {
			_content = new ContentObject();
			_content.decode(decoder);
			if( Log.isLoggable(Level.FINEST) )
				SystemConfiguration.logObject(Level.FINEST, "packetDecode", (ContentObject)_content);
		}
		Log.finest("Finished decoding wire packet.");
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": bad or missing values.");
		}
		_content.encode(encoder);
	}

	@Override
	public boolean validate() {
		if (null == _content) {
			return false;
		}
		if ( ! (_content instanceof Interest || _content instanceof ContentObject) )  {
			return false;
		}
		return true;
	}
	
	@Override
	public long getElementLabel() { // unused, we add nothing to encoding
		return -1;
	}
	
	public XMLEncodable getPacket() {
		return _content;
	}
}
