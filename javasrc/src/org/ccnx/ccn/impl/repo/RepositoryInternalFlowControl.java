/*
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

package org.ccnx.ccn.impl.repo;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * Special flow controller to write CCN objects to repository internally
 *
 */

public class RepositoryInternalFlowControl extends CCNFlowControl {
	RepositoryStore _repo;

	public RepositoryInternalFlowControl(RepositoryStore repo, CCNHandle handle) throws IOException {
		super(handle);
		_repo = repo;
	}
	
	/**
	 * Put to the repository instead of ccnd
	 */
	public ContentObject put(ContentObject co) throws IOException {
		try {
			_repo.saveContent(co);
		} catch (RepositoryException e) {
			throw new IOException(e.getMessage());
		}
		return co;
	}
	
	/**
	 * Don't do waitForPutDrain
	 */
	public void afterClose() throws IOException {};
}
