/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.impl.security.keys.BasicTrustManager;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Basic interface to trust management -- determination of whether a piece of
 * content is acceptable to a given consumer for a particular use in a particular
 * content. This interface is currently very minimal, but will expand.
 */
public abstract class TrustManager {
	
	protected static TrustManager _defaultTrustManager = null;
	
	/**
	 * Returns the default singleton instance of a TrustManager.
	 * @return the default singleton TrustManager instance
	 */
	public synchronized static TrustManager getDefaultTrustManager() {
		if (null == _defaultTrustManager)
			_defaultTrustManager = new BasicTrustManager();
		return _defaultTrustManager;
	}
	
	/**
	 * Get the current trust manager. Currently defers to getDefaultTrustManager().
	 * @return the current trust manager
	 */
	public static TrustManager getTrustManager() {
		return getDefaultTrustManager();
	}
	
	/**
	 * The start of an API to do the calculation of whether a given public key
	 * matches a desired role (subject or issuer) as specified by a PublisherID.
	 * @param desiredRole the desired role; either a specific key, or a key certified
	 * 	by another, specific key, and so on. Currently exploring the range of roles that are
	 * 	both useful and can be supported efficiently. Current production implementation only
	 * handles referring to specific keys.
	 * @param thisKey the key whose role we need to determine.
	 * @return true if thisKey matches desiredRole, false otherwise.
	 */
	public abstract boolean matchesRole(PublisherID desiredRole, PublisherPublicKeyDigest thisKey);

}
