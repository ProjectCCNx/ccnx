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
		//long startTime = System.currentTimeMillis();
		//long stopTime;
		while (true) {
			try {
				ContentObject co = _repo.getContent(interest);
				/*
				 * the code below is here as a reminder that this overridden version of get for the handle
				 * that seems to be only used for testing.  this method ignores the timeout and can cause
				 * the getLatestVersion code to work very hard and loop many times to try for the full
				 * timeout it was called with.  The gLV code has been modified so that if the get returns null
				 * and the response time from calling handle.get is 0, it will log the event at WARNING and
				 * return null to the caller, it will not loop many times to attempt to get the object for the
				 * full timeout it was called with.
				//stopTime = System.currentTimeMillis();
				if (co == null) {
					//there is nothing to return...  sleep for remaining time
					
					try {
						Thread.sleep(timeout - (stopTime - startTime));
					} catch (InterruptedException e) {
						Log.warning("error while sleeping in RepositoryInternalInputHandler");
					}
					
				}
				*/
				return co;
			} catch (RepositoryException e) {
				throw new IOException(e.getMessage());
			}
		}
	}
}
