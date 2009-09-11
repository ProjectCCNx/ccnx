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
 * prefer higher-level interfaces (e.g. streams).
 * @author smetters
 *
 */
public class CCNReader {
	
	protected CCNHandle _handle;
	
	public CCNReader(CCNHandle handle) throws ConfigurationException, IOException {
		_handle = handle;
		if (null == _handle)
			_handle = CCNHandle.open();
	}

	public ContentObject get(ContentName name, long timeout) throws IOException {
		return _handle.get(name, timeout);
	}
	
	/**
	 * @param name
	 * @param publisher
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	public ContentObject get(ContentName name, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		return _handle.get(name, publisher, timeout);
	}

	/**
	 * Return data the specified number of levels below us in the
	 * hierarchy, with order preference of leftmost.
	 * 
	 * Static version for convenience.
	 * @param name
	 * @param level
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	public static ContentObject getLower(CCNHandle handle, ContentName name, int level, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		Interest interest = new Interest(name, publisher);
		interest.maxSuffixComponents(level);
		interest.minSuffixComponents(level);
		return handle.get(interest, timeout);
	}
	
	public ContentObject getLower(ContentName name, int level, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		return getLower(_handle, name, level, publisher, timeout);
	}

	/**
	 * Medium level interface for retrieving pieces of a file
	 *
	 * getNext - get next content after specified content
	 *
	 * @param name - ContentName for base of get
	 * @param prefixCount - next follows components of the name
	 * 						through this count.
	 * @param omissions - Exclude
	 * @param timeout - milliseconds
	 * @return
	 * @throws MalformedContentNameStringException
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public ContentObject getNext(ContentName name, byte[][] omissions, long timeout) 
			throws IOException {
		return _handle.get(Interest.next(name, omissions, null), timeout);
	}
	
	public ContentObject getNext(ContentName name, long timeout)
			throws IOException, InvalidParameterException {
		return getNext(name, null, timeout);
	}
	
	public ContentObject getNext(ContentName name, int prefixCount, long timeout)
			throws IOException, InvalidParameterException {
		return  _handle.get(Interest.next(name, prefixCount), timeout);
	}
	
	public ContentObject getNext(ContentObject content, int prefixCount, byte[][] omissions, long timeout) 
			throws IOException {
		return getNext(contentObjectToContentName(content, prefixCount), omissions, timeout);
	}

	
	/**
	 * Get last content that follows name in similar manner to
	 * getNext
	 * 
	 * @param name
	 * @param omissions
	 * @param timeout
	 * @return
	 * @throws MalformedContentNameStringException
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public ContentObject getLatest(ContentName name, Exclude exclude, long timeout) 
			throws IOException, InvalidParameterException {
		return _handle.get(Interest.last(name, exclude, name.count() - 1), timeout);
	}
	
	public ContentObject getLatest(ContentName name, long timeout) throws InvalidParameterException, 
			IOException {
		return getLatest(name, null, timeout);
	}
	
	public ContentObject getLatest(ContentName name, int prefixCount, long timeout) throws InvalidParameterException, 
			IOException {
		return _handle.get(Interest.last(name, prefixCount), timeout);
	}
	
	public ContentObject getLatest(ContentObject content, int prefixCount, long timeout) throws InvalidParameterException, 
			IOException {
		return getLatest(contentObjectToContentName(content, prefixCount), null, timeout);
	}
	
	/**
	 * 
	 * @param name
	 * @param omissions
	 * @param timeout
	 * @return
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
	 * @param query
	 * @param timeout - microseconds
	 * @return
	 * @throws IOException 
	 */
	public ArrayList<ContentObject> enumerate(Interest query, long timeout) throws IOException {
		ArrayList<ContentObject> result = new ArrayList<ContentObject>();
		// This won't work without a correct order preference
		int count = query.name().count();
		while (true) {
			ContentObject co = null;
			co = _handle.get(query, timeout == CCNBase.NO_TIMEOUT ? 5000 : timeout);
			if (co == null)
				break;
			Log.info("enumerate: retrieved " + co.name() + 
					" digest: " + ContentName.componentPrintURI(co.contentDigest()) + " on query: " + query.name());
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
		ContentName cocn = content.name().clone();
		cocn.components().add(content.contentDigest());
		return new ContentName(prefixCount, cocn.components());
	}
}
