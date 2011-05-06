/
 * Part of the CCNx Java Library
 
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc
 
 * This library is free software; you can redistribute it and/or modify i
 * under the terms of the GNU Lesser General Public License version 2.
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful
 * but WITHOUT ANY WARRANTY; without even the implied warranty o
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GN
 * Lesser General Public License for more details. You should have receive
 * a copy of the GNU Lesser General Public License along with this library
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street
 * Fifth Floor, Boston, MA 02110-1301 USA
 *

package org.ccnx.ccn;

import org.ccnx.ccn.impl.security.keys.BasicTrustManager
import org.ccnx.ccn.protocol.PublisherID
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest


/**
 * Basic interface to trust management -- determination of whether a piece o
 * content is acceptable to a given consumer for a particular use in a particula
 * content. This interface is currently very minimal, but will expand.
 */
public abstract class TrustManager {
	
	protected static TrustManager _defaultTrustManager = null

	/*
	 * Returns the default singleton instance of a TrustManager
	 * @return the default singleton TrustManager instanc
	 */
	public static TrustManager getDefaultTrustManager() 
		if (null != _defaultTrustManager)
			return _defaultTrustManager
		synchronized (TrustManager.class) {
			if (null != _defaultTrustManager
				return _defaultTrustManager
			_defaultTrustManager = new BasicTrustManager()
			return _defaultTrustManager
		
	

	/*
	 * Get the current trust manager. Currently defers to getDefaultTrustManager()
	 * @return the current trust manage
	 *
	public static TrustManager getTrustManager() {
		return getDefaultTrustManager();
	}
	
	/**
	 * The start of an API to do the calculation of whether a given public ke
	 * matches a desired role (subject or issuer) as specified by a PublisherID.
	 * @param desiredRole the desired role; either a specific key, or a key certifie
	 * 	by another, specific key, and so on. Currently exploring the range of roles that ar
	 * 	both useful and can be supported efficiently. Current production implementation onl
	 * handles referring to specific keys.
	 * @param thisKey the key whose role we need to determine.
	 * @return true if thisKey matches desiredRole, false otherwise.
	 */
	public abstract boolean matchesRole(PublisherID desiredRole, PublisherPublicKeyDigest thisKey);

}
