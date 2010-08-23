/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2009, 2010 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * A simple server that takes a set of blocks and makes them available
 * to readers. Unlike standard flow controllers, this class doesn't care if
 * anyone ever reads its blocks -- it doesn't call waitForPutDrain. If no one
 * is interested, it simply persists until deleted/cleared. 
 * This version of flow server holds blocks until they have been
 * read once, manually removed or cleared, or the 
 * server is deleted by canceling all its registered prefixes. It does not
 * signal an error if the blocks are never read.
 */
public class CCNFlowServer extends CCNFlowControl {

	boolean _persistent = true;
	
	public CCNFlowServer(ContentName name, Integer capacity, boolean persistent, CCNHandle handle) throws IOException {
		super(name, handle);
		_persistent = persistent;
		if (null != capacity)
			setCapacity(capacity);
	}

	public CCNFlowServer(String name, Integer capacity, CCNHandle handle)
			throws MalformedContentNameStringException, IOException {
		super(name, handle);
		if (null != capacity)
			setCapacity(capacity);
	}

	public CCNFlowServer(Integer capacity, boolean persistent, CCNHandle handle) throws IOException {
		super(handle);
		_persistent = persistent;
		if (null != capacity)
			setCapacity(capacity);
	}
	
	/**
	 * If this is a non-persistent flow server, remove
	 * content objects after they have been read once; otherwise do nothing.
	 * 
	 * @param co ContentObject to remove from flow controller.
	 * @throws IOException may be thrown by overriding subclasses
	 */
	@Override
	public void afterPutAction(ContentObject co) throws IOException {
		if (!_persistent) {
			remove(co);
		}
	}

	/**
	 * Do not force client to wait for content to drain -- if
	 * no one wants it, that's just fine.
	 */
	@Override
	public void afterClose() throws IOException {
		// do nothing
	}
}
