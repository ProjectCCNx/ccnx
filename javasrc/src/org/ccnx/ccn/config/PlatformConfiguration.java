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
		// Default is not to lock signature operations
		boolean needLock = false;

		try {
			Log.info("BC provider: " + BouncyCastleProvider.PROVIDER_NAME);

			Security.addProvider(new BouncyCastleProvider());

			Provider prov = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
			Log.info("Provider info: {0} version: {1}", prov.getInfo(), prov.getVersion());

			String vm = System.getProperty("java.vm.name");
			Log.info("java.vm.name = {0}", vm);

			// If we are running on Android then we need to work around a bug by
			// locking all signature operations. Hopefully this will be fixed at
			// some point, then this code will have to test version number.
			if( vm.matches(".*(?i)Dalvik.*") ) {
				Log.info("Running on Dalvik - locking all signature operations");
				needLock = true;
			}
		} catch(Exception e) {
			e.printStackTrace();
		}

		_needSignatureLock = needLock;
	}
}
