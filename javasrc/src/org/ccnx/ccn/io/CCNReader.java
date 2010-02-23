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
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
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
	 * Gets a ContentObject matching this interest, CURRENTLY UNVERIFIED.
	 * @param interest interest for desired object
	 * @param timeout milliseconds to wait for data
	 * @return data matching the interest or null
	 * @throws IOException
	 */
	public ContentObject get(Interest interest, long timeout) throws IOException {
		return _handle.get(interest, timeout);
	}
	
	/**
	 * Helper method to retrieve a set of segmented content blocks and rebuild them
	 * into a single buffer. Equivalent to CCNWriter's put. Does not do anything about
	 * versioning.
	 */
	public byte [] getData(ContentName name, PublisherPublicKeyDigest publisher, int timeout) throws IOException {
		
		CCNInputStream inputStream = new CCNInputStream(name, publisher, _handle);
		inputStream.setTimeout(timeout);
		
		byte [] data = DataUtils.getBytesFromStream(inputStream);
		return data;
	}
	
	/**
	 * Helper method to retrieve a set of segmented content blocks and rebuild them
	 * into a single buffer. Equivalent to CCNWriter's put. Does not do anything about
	 * versioning.
	 */
	public byte [] getVersionedData(ContentName name, PublisherPublicKeyDigest publisher, int timeout) throws IOException {
		
		CCNInputStream inputStream = new CCNVersionedInputStream(name, publisher, _handle);
		inputStream.setTimeout(timeout);
		
		byte [] data = DataUtils.getBytesFromStream(inputStream);
		return data;
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
			query = Interest.next(co.name(), count, null);
		}
		Log.info("enumerate: retrieved " + result.size() + " objects.");
		return result;
	}
}
