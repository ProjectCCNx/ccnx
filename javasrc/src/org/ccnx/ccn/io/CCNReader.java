/**
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

package org.ccnx.ccn.io;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNBase;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * Miscellaneous helper functions to read data. Most clients will
 * prefer the higher-level interfaces offered by CCNInputStream and
 * its subclasses, or CCNNetworkObject and its subclasses.
 */
public class CCNReader {
	
	protected CCNHandle _handle;
	
	public CCNReader(CCNHandle handle) throws ConfigurationException, IOException {
		_handle = handle;
		if (null == _handle)
			_handle = CCNHandle.open();
	}

	/**
	 * Gets a ContentObject matching this name
	 * @param name desired name or prefix for data
	 * @param timeout milliseconds to wait for data
	 * @return data matching the name or null
	 * @throws IOException
	 * @see CCNBase#get(ContentName, long)
	 */
	public ContentObject get(ContentName name, long timeout) throws IOException {
		return _handle.get(name, timeout);
	}
	
	/**
	 * Gets a ContentObject matching this name and publisher
	 * @param name desired name or prefix for data
	 * @param publisher desired publisher or null for any publisher
	 * @param timeout milliseconds to wait for data
	 * @return data matching the name and publisher or null
	 * @throws IOException
	 */
	public ContentObject get(ContentName name, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		return _handle.get(name, publisher, timeout);
	}
	
	/**
	 * Return data the specified number of levels below us in the
	 * hierarchy, with order preference of leftmost.
	 * 
	 * @param handle handle to use for requests
	 * @param name of content to get
	 * @param level number of levels below name in the hierarchy content should sit
	 * @param publisher the desired publisher of this content, or null for any publisher.
	 * @param timeout timeout for retrieval
	 * @return matching content, if found
	 * @throws IOException
	 */
	public ContentObject getLower(ContentName name, int level, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		return _handle.get(Interest.lower(name, level, publisher), timeout);
	}

	/**
	 * Gets data which lexicographically follows the input name
	 * @param name starting name
	 * @param omissions components to ignore in result
	 * @param timeout milliseconds to wait for data
	 * @return next content or null if none found
	 * @throws IOException
	 */
	public ContentObject getNext(ContentName name, byte omissions[][], long timeout) 
			throws IOException {
		return _handle.get(Interest.next(name, omissions, null, null), timeout);
	}
	
	/**
	 * Gets data which lexicographically follows the input name
	 * @param name starting name
	 * @param timeout milliseconds to wait for data
	 * @return next content or null if none found
	 * @throws IOException
	 */
	public ContentObject getNext(ContentName name, long timeout)
			throws IOException, InvalidParameterException {
		return getNext(name, null, timeout);
	}
	
	/**
	 * Gets data which lexicographically follows the input name at the level of the prefixCount
	 * 
	 * @param name starting name
	 * @param prefixCount level at which to look for next data
	 * @param timeout milliseconds to wait for data
	 * @return next content or null if none found
	 * @throws IOException
	 */
	public ContentObject getNext(ContentName name, int prefixCount, long timeout)
			throws IOException, InvalidParameterException {
		return  _handle.get(Interest.next(name, prefixCount), timeout);
	}
	
	/**
	 * Gets data which lexicographically follows the input name at the level of the prefixCount
	 * 
	 * @param name starting name
	 * @param prefixCount level at which to look for next data
	 * @param omissions components to ignore in result
	 * @param timeout milliseconds to wait for data
	 * @return next content or null if none found
	 * @throws IOException
	 */
	public ContentObject getNext(ContentObject content, int prefixCount, byte[][] omissions, long timeout) 
			throws IOException {
		return getNext(contentObjectToContentName(content, prefixCount), omissions, timeout);
	}

	/**
	 * Gets the lexicographically last data following the input name
	 * @param name starting name
	 * @param exclude Exclude containing components to exclude from result
	 * @param timeout milliseconds to wait for data
	 * @return last content or null if none found
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public ContentObject getLatest(ContentName name, Exclude exclude, long timeout) 
			throws IOException, InvalidParameterException {
		return _handle.get(Interest.last(name, exclude, name.count() - 1), timeout);
	}
	
	/**
	 * Gets the lexicographically last data following the input name
	 * @param name starting name
	 * @param timeout milliseconds to wait for data
	 * @return last content or null if none found
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public ContentObject getLatest(ContentName name, long timeout) throws InvalidParameterException, 
			IOException {
		return getLatest(name, null, timeout);
	}
	
	/**
	 * Gets the lexicographically last data following the input name at the level of the prefixCount
	 * @param name starting name
	 * @param prefixCount level at which to look for last data
	 * @param timeout milliseconds to wait for data
	 * @return last content or null if none found
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public ContentObject getLatest(ContentName name, int prefixCount, long timeout) throws InvalidParameterException, 
			IOException {
		return _handle.get(Interest.last(name, prefixCount), timeout);
	}
	
	/**
	 * Gets the lexicographically last data following the input content at the level of the prefixCount
	 * @param name starting name
	 * @param prefixCount level at which to look for last data
	 * @param timeout milliseconds to wait for data
	 * @return last content or null if none found
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public ContentObject getLatest(ContentObject content, int prefixCount, long timeout) throws InvalidParameterException, 
			IOException {
		return getLatest(contentObjectToContentName(content, prefixCount), null, timeout);
	}
	
	/**
	 * Gets the lexicographically last data following the input name
	 * @param name starting name
	 * @param omissions components to ignore in result
	 * @param timeout milliseconds to wait for data
	 * @return last content or null if none found
	 * @throws InvalidParameterException
	 * @throws MalformedContentNameStringException
	 * @throws IOException
	 */
	public ContentObject getExcept(ContentName name, byte[][] omissions, long timeout) throws InvalidParameterException, MalformedContentNameStringException, 
			IOException {
		return _handle.get(Interest.exclude(name, omissions), timeout);
	}
	
	/**
	 * Enumerate matches below query name in the hierarchy, looking
	 * at raw content. For a higher-level enumeration protocol see the 
	 * name enumeration protocol.
	 * Note this method is also quite slow because it has to timeout requests at every
	 * search level
	 * @param query an Interest defining the highest level of the query
	 * @param timeout - milliseconds to wait for each individual get of data, default is 5 seconds
	 * @return a list of the content objects matching this query
	 * @throws IOException 
	 */
	public ArrayList<ContentObject> enumerate(Interest query, long timeout) throws IOException {
		ArrayList<ContentObject> result = new ArrayList<ContentObject>();
		// This won't work without a correct order preference
		int count = query.name().count();
		while (true) {
			ContentObject co = null;
			co = _handle.get(query, timeout);
			if (co == null)
				break;
			Log.info("enumerate: retrieved " + co.fullName() + 
					" on query: " + query.name());
			result.add(co);
			for (int i = co.name().count() - 1; i > count; i--) {
				result.addAll(enumerate(new Interest(new ContentName(i, co.name().components())), timeout));
			}
			query = Interest.next(co, count);
		}
		Log.info("enumerate: retrieved " + result.size() + " objects.");
		return result;
	}

	private ContentName contentObjectToContentName(ContentObject content, int prefixCount) {
		ContentName cocn = content.fullName().clone();
		return new ContentName(prefixCount, cocn.components());
	}
}
