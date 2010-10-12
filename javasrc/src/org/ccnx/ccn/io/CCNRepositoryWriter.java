/*
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
package org.ccnx.ccn.io;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * A CCNWriter subclass that writes to a repository.
 *
 */
public class CCNRepositoryWriter extends CCNWriter {

	public CCNRepositoryWriter(String namespace, CCNHandle handle)
			throws MalformedContentNameStringException, IOException {
		super(namespace, handle);
	}

	public CCNRepositoryWriter(ContentName namespace, CCNHandle handle)
			throws IOException {
		super(namespace, handle);
	}

	public CCNRepositoryWriter(CCNHandle handle) throws IOException {
		super(handle);
	}

	public CCNRepositoryWriter(CCNFlowControl flowControl) {
		super(flowControl);
	}

	/**
	 * Create a repository flow controller. 
	 * @param namespace
	 * @param handle
	 * @return
	 * @throws IOException 
	 */
	protected CCNFlowControl getFlowController(ContentName namespace, CCNHandle handle) throws IOException {
		if (null != namespace) {
			return new RepositoryFlowControl(namespace, handle);
		}
		return new RepositoryFlowControl(handle);
	}
	
	/**
	 * Create a repository flow controller. 
	 * @param namespace
	 * @param handle
	 * @param local
	 * @return
	 * @throws IOException 
	 */
	protected CCNFlowControl getFlowController(ContentName namespace, CCNHandle handle, boolean local) throws IOException {
		if (null != namespace) {
			return new RepositoryFlowControl(namespace, handle, local);
		}
		return new RepositoryFlowControl(handle, local);
	}
}
