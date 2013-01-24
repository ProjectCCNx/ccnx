/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;


/**
 * This element in an Exclude filter matches all components.
 */
public class ExcludeAny extends Exclude.Filler implements Comparable<ExcludeAny> {
	
	public boolean match(byte [] component) {
		return true;
	}

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		encoder.writeStartElement(getElementLabel());
		encoder.writeEndElement();
	}
	
	@Override
	public long getElementLabel() { return CCNProtocolDTags.Any; }

	@Override
	public boolean validate() {
		return true;
	}
	
	public int compareTo(ExcludeAny o) {
		return 0;	// always equal
	}
	
	/**
     * All ExcludeAny's are equal to each other (but only to ExcludeAnys). 
	 * Without overriding equals we get Object's ==, which isn't what we want.
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj == null)
			return false;
		if (!(obj instanceof ExcludeAny))
			return false;
		return true; // match any ExcludeAny
	}
	
	public int hashCode() {
		// I don't think super.hashCode() can return 0 - at least this is better than nothing...
		return (this instanceof ExcludeAny) ? 0 : super.hashCode();
	}
}
