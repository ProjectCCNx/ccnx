/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.config;

import java.security.Provider;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import org.ccnx.ccn.impl.support.Log;

/**
 * Determine any platform-specific flags
 */
public final class PlatformConfiguration {

	// ==============================================
	// Public interface
	public final static boolean needSignatureLock() {
		return _needSignatureLock;
	}

	// ==============================================
	// Internal methods
	private final static boolean _needSignatureLock;

	static {
		// Default is not to lock signature operations unless we're using
		// BC 1.34 (as seen on Android)
		boolean needLock = false;
		
		try {
			Log.info("BC provider: " + BouncyCastleProvider.PROVIDER_NAME);

			Security.addProvider(new BouncyCastleProvider());

			Provider prov = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
			Log.severe("Provider info: " + prov.getInfo());
			Log.severe("Provider ver : " + prov.getVersion());

			// The unix/mac code uses "BouncyCastle Security Provider v1.43"
			// The Android code uses "BouncyCastle Security Provider v1.34"

			if( prov.getVersion() == 1.34 ) {
				needLock = true;
			}
		} catch(Exception e) {
			e.printStackTrace();
		}

		_needSignatureLock = needLock;
	}
}
