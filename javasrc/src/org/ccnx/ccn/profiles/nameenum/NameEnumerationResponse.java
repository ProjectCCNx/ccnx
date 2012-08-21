/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.nameenum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * NameEnumerationResponse objects are used to respond to incoming NameEnumeration interests.
 * 
 * NameEnumerationResponses are generated in two ways, in direct response to an interest
 * where there is new information to return, and where a previous interest was not
 * satisfied (set the interest flag), but a later save occurs directly under the namespace.
 *
 */
public class NameEnumerationResponse {
	
	private ContentName _prefix;
	private ArrayList<ContentName> _names;
	private CCNTime _version;
	
	/**
	 * Inner class to slightly modify the collection type used to respond to NE
	 * requests. Eventually will move to its own message type.
	 */
	public static class NameEnumerationResponseMessage extends Collection {
		
		public static class NameEnumerationResponseMessageObject extends CCNEncodableObject<NameEnumerationResponseMessage> {
			
			public NameEnumerationResponseMessageObject(ContentName name, NameEnumerationResponseMessage data, CCNHandle handle) throws IOException {
				super(NameEnumerationResponseMessage.class, true, name, data, SaveType.RAW, null, null, handle);
			}
			
			public NameEnumerationResponseMessageObject(ContentName name, java.util.Collection<Link> contents, CCNHandle handle) throws IOException {
				this(name, new NameEnumerationResponseMessage(contents), handle);
			}
			
			public NameEnumerationResponseMessageObject(ContentName name, Link [] contents, CCNHandle handle) throws IOException {
				this(name, new NameEnumerationResponseMessage(contents), handle);			
			}

			public NameEnumerationResponseMessageObject(ContentName name, NameEnumerationResponseMessage data, PublisherPublicKeyDigest publisher, 
									KeyLocator keyLocator, CCNHandle handle) throws IOException {
				super(NameEnumerationResponseMessage.class, true, name, data, SaveType.RAW, publisher, keyLocator, handle);
			}

			public NameEnumerationResponseMessageObject(ContentName name, java.util.Collection<Link> contents, 
									PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle handle) throws IOException {
				this(name, new NameEnumerationResponseMessage(contents), publisher, keyLocator, handle);
			}
			
			public NameEnumerationResponseMessageObject(ContentName name, Link [] contents, PublisherPublicKeyDigest publisher, 
									KeyLocator keyLocator, CCNHandle handle) throws IOException {
				this(name, new NameEnumerationResponseMessage(contents), publisher, keyLocator, handle);			
			}

			public NameEnumerationResponseMessageObject(ContentName name, CCNHandle handle) 
			throws ContentDecodingException, IOException {
				super(NameEnumerationResponseMessage.class, true, name, (PublisherPublicKeyDigest)null, handle);
				setSaveType(SaveType.RAW);
			}

			public NameEnumerationResponseMessageObject(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) 
					throws ContentDecodingException, IOException {
				super(NameEnumerationResponseMessage.class, true, name, publisher, handle);
				setSaveType(SaveType.RAW);
			}
			
			public NameEnumerationResponseMessageObject(ContentObject firstBlock, CCNHandle handle) 
					throws ContentDecodingException, IOException {
				super(NameEnumerationResponseMessage.class, true, firstBlock, handle);
				setSaveType(SaveType.RAW);
			}
			
			public NameEnumerationResponseMessage responseMessage() throws ContentNotReadyException, ContentGoneException, ErrorStateException {
				return data();
			}
			
			public LinkedList<Link> contents() throws ContentNotReadyException, ContentGoneException, ErrorStateException { 
				if (null == data())
					return null;
				return data().contents(); 
			}
		}
		
		/*
		 * Not used yet.
		 */
		public static final String NE_RESPONSE_MESSAGE = "NameEnumerationResponse";

		public NameEnumerationResponseMessage() {
			super();
		}

		public NameEnumerationResponseMessage(
				ArrayList<ContentName> nameContents) {
			super(nameContents);
		}

		public NameEnumerationResponseMessage(
				java.util.Collection<Link> contents) {
			super(contents);
		}

		public NameEnumerationResponseMessage(Link[] contents) {
			super(contents);
		}
		
		/* 
		 * When we are ready to label this message separately, un-comment this.
		@Override
		public String getElementLabel() { return COLLECTION_ELEMENT; }
		 */
		
	}
	
	/**
	 * Empty NameEnumerationResponse constructor that sets the variables to null.
	 */
	public NameEnumerationResponse() {
		_prefix = null;
		_names = new ArrayList<ContentName>(0);
		_version = null;
	}
	
	/**
	 * NameEnumerationResponse constructor that populates the object's variables.
	 * 
	 * @param p ContentName that is the prefix for this response
	 * @param n ArrayList<ContentName> of the names under the prefix
	 * @param ts CCNTime is the timestamp used to create the version component
	 *   for the object when it is written out
	 */
	public NameEnumerationResponse(ContentName p, ArrayList<ContentName> n, CCNTime ts) {
		_prefix = p;
		_names = n;
		_version = ts;
	}
	
	/**
	 * Builds a NE response from name components -- NE responses contain ContentNames that
	 * only have a single component. Make a friendlier constructor that doesn't require
	 * pre-making names from the components we really want to list.
	 */
	public NameEnumerationResponse(ContentName p, byte [][] names, CCNTime ts) {
		_prefix = p;
		_version = ts;
		_names = new ArrayList<ContentName>((null == names) ? 0 : names.length);
		if (null != names) {
			for (int i=0; i < names.length; ++i) {
				_names.add(new ContentName(names[i]));
			}
		}
	}
	
	/**
	 * Method to set the NameEnumerationReponse prefix. Right now
	 * forces caller to add the command prefix (e.g. CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION),
	 * should make this cleverer (even if there are multiple NE protocols).
	 * 
	 * @param p ContentName of the prefix for the response
	 * @return void
	 */
	public void setPrefix(ContentName p) {
		_prefix = p;
	}
	
	/**
	 * Method to set the names to return under the prefix.
	 * 
	 * @param n ArrayList<ContentName> of the children for the response
	 * @return void
	 */
	public void setNameList(ArrayList<ContentName> n) {
		_names = n;
	}
	
	/**
	 * Add a name to the list
	 */
	public void add(ContentName name) {
		_names.add(name);
	}
	
	/**
	 * Add a single-component name to the list.
	 */
	public void add(byte [] name) {
		_names.add(new ContentName(name));
	}
	
	/**
	 * Add a single-component name to the list.
	 */
	public void add(String name) {
		_names.add(new ContentName(name));
	}
	
	/**
	 * Method to get the prefix for the response.
	 * 
	 * @return ContentName prefix for the response
	 */
	public ContentName getPrefix() {
		return _prefix;
	}
	
	/**
	 * Method to get the names for the response.
	 * 
	 * @return ArrayList<ContentName> Names to return in the response
	 */
	public ArrayList<ContentName> getNames() {
		return _names;
	}
	
	
	/**
	 * Method to set the timestamp for the response version.
	 * @param ts CCNTime for the ContentObject version
	 * @return void
	 */
	public void setTimestamp(CCNTime ts) {
		_version = ts;
	}
	
	
	/**
	 * Method to get the timestamp for the response object.
	 * 
	 * @return CCNTime for the version component of the object
	 */
	public CCNTime getTimestamp() {
		return _version;
	}
	
	/**
	 * Method to return a Collection object for the names in the response
	 * 
	 * @return Collection A collection of the names (as Link objects) to return.
	 */
	public NameEnumerationResponseMessage getNamesForResponse() {
		return new NameEnumerationResponseMessage(_names);
	}
	
	/**
	 * Method to check if the NameEnumerationResponse object has names to return.
	 * 
	 * @return boolean True if there are names to return, false if there are no
	 *   names or the list of names is null
	 */
	public boolean hasNames() {
		if (_names != null && _names.size() > 0)
			return true;
		else
			return false;
	}
	
}
