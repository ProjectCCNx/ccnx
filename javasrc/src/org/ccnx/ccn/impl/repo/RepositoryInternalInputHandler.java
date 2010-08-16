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
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * Input stream to get data directly from the repository
 */
public class RepositoryInternalInputHandler extends CCNHandle {
	protected RepositoryStore _repo = null;

	protected RepositoryInternalInputHandler(RepositoryStore repo, KeyManager km) throws ConfigurationException,
			IOException {
		super(km);
		_repo = repo;
	}
	
	public ContentObject get(Interest interest, long timeout) throws IOException {
		while (true) {
			try {
				return _repo.getContent(interest);
			} catch (RepositoryException e) {
				throw new IOException(e.getMessage());
			}
		}
	}
}
