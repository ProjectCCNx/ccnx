/*
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
import java.util.Set;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.search.Pathfinder;
import org.ccnx.ccn.profiles.search.Pathfinder.SearchResults;
import org.ccnx.ccn.protocol.ContentName;

/**
 * Maintains a list of existing policy marker prefixes. 
 **/
public class NamespaceManager {
	
	protected static Set<ContentName> _searchedPathCache = new HashSet<ContentName>();
	protected static Set<ContentName> _policyControlledNamespaces = new HashSet<ContentName>();
	
	/**
	 * Find the closest policy controlled namespace that cover operations on a specific name.
	 * If none exists in memory than this searches up the name tree.
	 * @throws IOException 
	 */
	public static ContentName findPolicyControlledNamespace(ContentName controlledName, CCNHandle handle) throws IOException {
		
		// See if we already have a prefix controlling this name.
		for (ContentName prefix : _policyControlledNamespaces) {
			if (inProtectedNamespace(prefix, controlledName)) {
				// Doesn't handle nesting... want to find the longest match that matches this name,
				// while marking ones we don't have to search again. Works for now, might need to make
				// this more sophisticated if applications warrant.
				Log.info("Found policy control prefix {0} protecting {1}", prefix, controlledName);
				return prefix;
			}
		}
		// No known prefix exists for this name - now look to see if we can find a prefix up the path to create one...
		ContentName searchName = VersioningProfile.cutTerminalVersion(controlledName).first();
		Log.info("No cached policy control prefix found, searching for root object for {0}. Removed terminal version, checking path {1}", controlledName, searchName);
		
		// Have a cache of searched paths, so we don't re-search.
		if (cacheContainsPath(searchName)) {
			Log.info("Cache indicates that we have already checked the path {0} for namespace roots, with none found. Returning null.", searchName);
			return null;
		}

		// Search up a path (towards the root) for an Access Control root marker
		Pathfinder pathfinder = new Pathfinder(searchName, null, 
											  NamespaceProfile.policyPostfix(), true, false, 
											  SystemConfiguration.SHORT_TIMEOUT, 
											  _searchedPathCache,
											  handle);
		SearchResults results = pathfinder.waitForResults();
		if (null != results.getExcluded()) {
			_searchedPathCache.addAll(results.getExcluded());
		}
		if (null != results.getResult()) {
			ContentName policyPrefix = results.getResult().name().cut(results.getInterestName().count());
			_policyControlledNamespaces.add(policyPrefix);
			return policyPrefix;
		}
		return null;
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
	
	public static boolean inProtectedNamespace(ContentName namespace, ContentName content) {
		return namespace.isPrefixOf(content);
	}

}
