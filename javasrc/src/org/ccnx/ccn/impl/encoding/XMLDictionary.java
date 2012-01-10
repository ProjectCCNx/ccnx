/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011, 2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.impl.encoding;

/**
 * Defines the API of an XML dictionary.
 * 
 * We use an interface because Java can only extend one base class.  If we want
 * the dictionary provider to be a network object, we need to do this as
 * an interface not an abstract base class.
 */
public interface XMLDictionary {
	public Long stringToTag(String tag);
	public String tagToString(long tagVal);
}
