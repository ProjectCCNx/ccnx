/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn;

import java.io.IOException;

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * This is the lowest-level interface to CCN, and describes in its entirety,
 * the interface the library has to speak to the CCN network layer. It consists of only a small number
 * of methods, all other operations in CCN are built on top of these methods together
 * with the constraint specifications allowed by Interest.
 * 
 * Clients wishing to build simple test programs can access an implementation of
 * these methods most easily using the CCNReader and CCNWriter class. Clients wishing
 * to do more sophisticated IO should look at the options available in the
 * org.ccnx.ccn.io and org.ccnx.ccn.io.content packages.
 * 
 * @see CCNHandle
 */
public interface CCNBase {
	
	/**
	 * Put a single content object into the network. This is a low-level put,
	 * and typically should only be called by a flow controller, in response to
	 * a received Interest. Attempting to write to ccnd without having first
	 * received a corresponding Interest violates flow balance, and the content
	 * will be dropped.
	 * @param co the content object to write. This should be complete and well-formed -- signed and
	 * 	so on.
	 * @return the object that was put if successful, otherwise null.
	 * @throws IOException
	 */
	public ContentObject put(ContentObject co) throws IOException;
	
	/**
	 * Get a single piece of content from CCN. This is a blocking get, it will return
	 * when matching content is found or it times out, whichever comes first.
	 * @param interest
	 * @param timeout
	 * @return the content object
	 * @throws IOException
	 */
	public ContentObject get(Interest interest, long timeout) throws IOException;
	
	/**
	 * Register a standing interest filter with callback to receive any 
	 * matching interests seen
	 * @param filter
	 * @param callbackListener
	 * @throws IOException 
	 */
	public void registerFilter(ContentName filter,
							   CCNInterestHandler callbackHandler) throws IOException;
	
	/**
	 * Unregister a standing interest filter
	 * @param filter
	 * @param callbackListener
	 */
	public void unregisterFilter(ContentName filter,
								 CCNInterestHandler callbackHandler);
	
	/**
	 * Query, or express an interest in particular
	 * content. This request is sent out over the
	 * CCN to other nodes. On any results, the
	 * callbackHandler if given, is notified.
	 * Results may also be cached in a local repository
	 * for later retrieval by get().
	 * Get and expressInterest could be implemented
	 * as a single function that might return some
	 * content immediately and others by callback;
	 * we separate the two for now to simplify the
	 * interface.
	 * 
	 * Pass it on to the CCNInterestManager to
	 * forward to the network. Also express it to the
	 * repositories we manage, particularly the primary.
	 * Each might generate their own CCNQueryDescriptor,
	 * so we need to group them together.
	 */
	public void expressInterest(
			Interest interest,
			CCNContentHandler handler) throws IOException;

	/**
	 * Cancel this interest. 
	 * @param interest
	 * @param listener Used to distinguish the same interest
	 * 	requested by more than one handler.
	 */
	public void cancelInterest(Interest interest, CCNContentHandler handler);
}
