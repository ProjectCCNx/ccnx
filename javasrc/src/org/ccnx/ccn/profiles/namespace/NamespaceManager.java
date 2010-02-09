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

package org.ccnx.ccn.profiles.namespace;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.namespace.NamespaceManager.Root.RootObject;
import org.ccnx.ccn.profiles.search.Pathfinder;
import org.ccnx.ccn.profiles.search.Pathfinder.SearchResults;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.profiles.security.access.AccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

/**
 *
 **/
public class NamespaceManager {

	protected static Set<AccessControlManager> _acmList = new HashSet<AccessControlManager>(); 
	protected static Set<ContentName> _searchedPathCache = new HashSet<ContentName>();

	/**
	 * Used to mark the top level in a namespace under access control
	 * This class currently holds no data - it will be extended to hold access control 
	 * configuration information for that namespace.
	 */
	public static class Root extends GenericXMLEncodable {

		public static class RootObject extends CCNEncodableObject<Root> {

			// Not mutable yet, but will be soon.
			public RootObject(ContentName name, CCNHandle handle) throws IOException {
				super(Root.class, true, name, handle);
			}

			public RootObject(ContentName name, Root r, SaveType saveType, CCNHandle handle) throws IOException {
				super(Root.class, true, name, r, saveType, handle);
			}

			public RootObject(ContentObject firstBlock, CCNHandle handle)
					throws ContentDecodingException, IOException {
				super(Root.class, true, firstBlock, handle);
			}

			public ContentName namespace() {
				return _baseName.copy(_baseName.count()-2);
			}
		}

		/**
		 * Set up a part of the namespace to be under access control.
		 * This method writes the root block and root ACL to a repository.
		 * @param name The top of the namespace to be under access control
		 * @param acl The access control list to be used for the root of the
		 * namespace under access control.
		 * @throws IOException 
		 * @throws ConfigurationException 
		 */
		public static void create(ContentName name, ACL acl, SaveType saveType, CCNHandle handle) throws IOException, ConfigurationException {
			Root r = new Root();
			RootObject ro = new RootObject(AccessControlProfile.accessRoot(name), r, saveType, handle);
			ro.save();
			ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(name), acl, handle);
			aclo.save();
		}

		@Override
		public void decode(XMLDecoder decoder) {
		}

		@Override
		public void encode(XMLEncoder encoder) {
		}

		@Override
		public boolean validate() {
			return true;
		}

		@Override
		public String getElementLabel() {
			// TODO Auto-generated method stub
			return null;
		}
	}

	/**
	 * Find an ACM object that covers operations on a specific name. 
	 * If none exists in memory then this searches up the name tree 
	 * looking for the root of a namespace under access control,
	 * and if it finds one creates an ACM. If none is found null is returned.
	 * @param name
	 * @param handle
	 * @return null if namespace is not under access control, or an ACM to perform 
	 * 	operations on the name if it is.
	 * @throws IOException
	 * @throws ConfigurationException 
	 */
	public static AccessControlManager findACM(ContentName name, CCNHandle handle)  throws IOException, ConfigurationException {
		// See if we already have an AccessControlManager covering this namespace
		for (AccessControlManager acm : _acmList) {
			if (acm.inProtectedNamespace(name)) {
				Log.info("Found cached access control manager rooted at {0} protecting {1}", acm.getNamespaceRoot(), name);
				return acm;
			}
		}
		// No ACM exists for this name - now look to see if we can find an ACL down the path to create one...
		ContentName searchName = VersioningProfile.cutTerminalVersion(name).first();
		Log.info("No cached access control manager found, searching for root object for {0}. Removed terminal version, checking path {1}", name, searchName);
		
		// Have a cache of searched paths, so we don't re-search.
		if (cacheContainsPath(searchName)) {
			Log.info("Cache indicates that we have already checked the path {0} for namespace roots, with none found. Returning null.", searchName);
			return null;
		}

		// Search up a path (towards the root) for an Access Control root marker
		Pathfinder pathfinder = new Pathfinder(searchName, null, AccessControlProfile.rootPostfix(), true, false, 
											  SystemConfiguration.SHORT_TIMEOUT, 
											  _searchedPathCache,
											  handle);
		SearchResults results = pathfinder.waitForResults();
		if (null != results.second()) {
			_searchedPathCache.addAll(results.second());
		}
		
		if (null != results.first()) {
			// does this seek?
			Log.info("Got a segment of an object, is it the first segment of the right object: {0}", results.first().name());
			RootObject ro = new RootObject(results.first(), handle);
			AccessControlManager acm = AccessControlManager.createManager(ro, handle);
			registerACM(acm);
			return acm;
		}

		Log.info("No ac ROOT found on path {0}", searchName);
		return null;
	}

	/**
	 * A way to add an access control manager to the search set without namespace support.
	 * @param acm
	 */
	public static void registerACM(AccessControlManager acm) {
		if (null != acm) {
			synchronized(_acmList) {
				_acmList.add(acm);
			}
		}
	}
	
	public synchronized static void clearSearchedPathCache() { _searchedPathCache.clear(); }
	
	public synchronized static void addToSearchedPathCache(Set<ContentName> newPaths) {
		_searchedPathCache.addAll(newPaths);
	}
	
	public synchronized static void removeFromSearchedPathCache(ContentName path) {
		_searchedPathCache.remove(path);
	}
	
	public static boolean cacheContainsPath(ContentName path) {
		
		// Need cache to contain everything on the path to be useful. 
		while (_searchedPathCache.contains(path)) {
			if (path.equals(ContentName.ROOT)) {
				break;
			}
			path = path.parent();
		}
		if (path.equals(ContentName.ROOT) && _searchedPathCache.contains(path)) {
			return true;
		}
		return false;
	}
}
