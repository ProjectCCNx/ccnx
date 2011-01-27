/*
 * A CCNx example of extending ccnb encoding/decoding.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */


package org.ccnx.ccn.apps.examples.ccnb;

import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;

public class Example2Integers extends GenericXMLEncodable  {
	
	protected Integer integer1;
	protected Integer integer2;
	
	

	/**
	 * @param integer1
	 * @param integer2
	 */
	public Example2Integers(Integer integer1, Integer integer2) {
		this.integer1 = integer1;
		this.integer2 = integer2;
	}
	
	public Example2Integers() {
	}

	
	public Example2Integers(byte[] raw) {
	}


	/**
	 * @return the integer1
	 */
	public Integer getInteger1() {
		return integer1;
	}

	/**
	 * @param integer1 the integer1 to set
	 */
	public void setInteger1(Integer integer1) {
		this.integer1 = integer1;
	}

	/**
	 * @return the integer2
	 */
	public Integer getInteger2() {
		return integer2;
	}

	/**
	 * @param integer2 the integer2 to set
	 */
	public void setInteger2(Integer integer2) {
		this.integer2 = integer2;
	}

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		
		if (decoder.peekStartElement(ExampleDTags.Integer1)) {
			integer1 = decoder.readIntegerElement(ExampleDTags.Integer1);
		} else {
			Log.warning("Integer1({0}) tag not found", ExampleDTags.Integer1);
		}
		if (decoder.peekStartElement(ExampleDTags.Integer2)) {
			integer2 = decoder.readIntegerElement(ExampleDTags.Integer2);
		} else {
			Log.warning("Integer2({0}) tag not found", ExampleDTags.Integer2);
		}
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		encoder.writeStartElement(getElementLabel());
		encoder.writeElement(ExampleDTags.Integer1, integer1);
		encoder.writeElement(ExampleDTags.Integer2, integer2);
		encoder.writeEndElement();	
	}

	@Override
	public long getElementLabel() {
		return ExampleDTags.Example2Integers;
	}

	@Override
	public boolean validate() {
		return true;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "Example2Integers [integer1: " + integer1 + " integer2: " + integer2 + "]";
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((integer1 == null) ? 0 : integer1.hashCode());
		result = prime * result
				+ ((integer2 == null) ? 0 : integer2.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		Example2Integers other = (Example2Integers) obj;
		if (integer1 == null) {
			if (other.integer1 != null) {
				return false;
			}
		} else if (!integer1.equals(other.integer1)) {
			return false;
		}
		if (integer2 == null) {
			if (other.integer2 != null) {
				return false;
			}
		} else if (!integer2.equals(other.integer2)) {
			return false;
		}
		return true;
	}

}
