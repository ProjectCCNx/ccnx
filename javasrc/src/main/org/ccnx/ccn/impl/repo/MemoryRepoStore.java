/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * MemoryRepoStore is a transient, in-memory store for applications that 
 * wish to provide a repo for their own content as long as they are running.
 * Given that it is transient, this implementation breaks the usual convention
 * that a repository provides persistent storage.  *
 */
public class MemoryRepoStore extends RepositoryStoreBase implements RepositoryStore, ContentTree.ContentGetter {
	public final static String CURRENT_VERSION = "1.0";
	protected ContentTree _index;
	protected ContentName _namespace = null; // Prinmary/initial namespace

	// For in-memory repo, we just hold onto each ContentObject directly in the ref
	static class MemRef extends ContentRef {
		ContentObject co;
		public MemRef(ContentObject content) {
			co = content;
		}
	}
	
	public MemoryRepoStore(ContentName namespace) {
		_namespace = namespace;
	}

	public String getVersion() {
		return CURRENT_VERSION;
	}
	
	public void initialize(String repositoryRoot, File policyFile, String localName, String globalPrefix,
				String nameSpace, CCNHandle handle) throws RepositoryException {
		if (null == handle) {
			// use the default user credentials
			try {
				handle = CCNHandle.open();
			} catch (IOException e) {
				throw new RepositoryException("IOException opening a CCNHandle!", e);
			} catch (ConfigurationException e) {
				throw new RepositoryException("ConfigurationException opening a CCNHandle!", e);
			}
		}
		_handle = handle;
	
		if (null != _index) {
			throw new RepositoryException("Attempt to re-initialize " + this.getClass().getName());
		}
		_index = new ContentTree();
		if (null != _namespace) {
			ArrayList<ContentName> ns = new ArrayList<ContentName>();
			ns.add(_namespace);
			_policy = new BasicPolicy(null, ns);
			_policy.setVersion(CURRENT_VERSION);
		} else {
			startInitPolicy(policyFile, nameSpace);
		}
		// We never have any persistent content so don't try to read policy from there with readPolicy()
		try {
			finishInitPolicy(localName, globalPrefix);
		} catch (MalformedContentNameStringException e) {
			throw new RepositoryException(e.getMessage());
		}
	}

	public ContentObject get(ContentRef ref) {
		if (null == ref) {
			return null;
		} else {
			// This is a call back based on what we put in ContentTree, so it must be
			// using our subtype of ContentRef
			MemRef mref = (MemRef)ref;
			return mref.co;
		}
	}
	
	public NameEnumerationResponse saveContent(ContentObject content) throws RepositoryException {
		// Note: we're trusting the app to store what it wants and not implementing any 
		// namespace restrictions here
		MemRef ref = new MemRef(content);
		NameEnumerationResponse ner = new NameEnumerationResponse();
		_index.insert(content, ref, System.currentTimeMillis(), this, ner);
		return ner;
	}
	
	public ContentObject getContent(Interest interest)
	throws RepositoryException {
		return _index.get(interest, this);
	}
		
	public boolean hasContent(ContentName name) throws RepositoryException {
		return _index.matchContent(name);
	}

	public NameEnumerationResponse getNamesWithPrefix(Interest i, ContentName responseName) {
		return _index.getNamesWithPrefix(i, responseName);
	}

    public void shutDown() {
    	// no-op
    }

	public Object getStatus(String type) {
		return null;
	}

	public boolean bulkImport(String name) throws RepositoryException {
		return false; // not supported
	}

	public void policyUpdate() throws RepositoryException {}
    
}
