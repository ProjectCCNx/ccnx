/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2009,2010 Palo Alto Research Center, Inc.
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.search.Pathfinder;
import org.ccnx.ccn.profiles.search.Pathfinder.SearchResults;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.profiles.security.access.AccessControlProfile;
import org.ccnx.ccn.profiles.security.access.AccessControlPolicyMarker.RootObject;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 *
 **/
public class NamespaceManager {
	
	protected static Set<AccessControlManager> _acmList = new HashSet<AccessControlManager>(); 
	protected static Set<ContentName> _searchedPathCache = new HashSet<ContentName>();
	
	
	public static AccessControlManager createAccessControlManager(RootObject policyInformation, CCNHandle handle) throws ContentNotReadyException, ContentGoneException, ErrorStateException, InstantiationException, IllegalAccessException, ConfigurationException, IOException {
		Class<? extends AccessControlManager> acmClazz = null;
		synchronized(NamespaceManager.class) {
			acmClazz = _accessControlManagerTypes.get(policyInformation.root().profileName());
		}
		if (null != acmClazz) {
			AccessControlManager acm = (AccessControlManager)acmClazz.newInstance();
			acm.initialize(policyInformation, handle);
			return acm;
		}
		return null;
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
		if (null != results.getExcluded()) {
			_searchedPathCache.addAll(results.getExcluded());
		}
		
		if (null != results.getResult()) {
			// does this seek?
			Log.info("Got a segment of an object, is it the first segment of the right object: {0}", results.getResult().name());
			RootObject ro = new RootObject(results.getResult(), handle);
			AccessControlManager acm;
			try {
				acm = NamespaceManager.createAccessControlManager(ro, handle);
			} catch (InstantiationException e) {
				Log.severe("InstantiationException attempting to create access control manager: " + e.getMessage());
				Log.warningStackTrace(e);
				throw new ConfigurationException("InstantiationException attempting to create access control manager: " + e.getMessage(), e);
			} catch (IllegalAccessException e) {
				Log.severe("IllegalAccessException attempting to create access control manager: " + e.getMessage());
				Log.warningStackTrace(e);
				throw new ConfigurationException("IllegalAccessException attempting to create access control manager: " + e.getMessage(), e);
			}
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
