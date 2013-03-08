/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.impl.security.keystore;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.util.HashMap;

import sun.security.jca.GetInstance;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class CCNKeyStore extends KeyStore {
	protected static HashMap<String, Class<KeyStoreSpi>> _ccnKeyStores = new HashMap<String, Class<KeyStoreSpi>>();
	protected boolean _requiresSymmetric = false;
	protected static Class<KeyStoreSpi> _searchedClass = null;
	
	static {
		_ccnKeyStores.put("CCN_AES", (Class)AESKeyStoreSpi.class);
	}

	protected CCNKeyStore(KeyStoreSpi spi, Provider provider, String type, boolean requiresSymmetric) {
		super(spi, provider, type);
		_requiresSymmetric = requiresSymmetric;
	}
	
	public static CCNKeyStore getInstance(String type) throws KeyStoreException {
		boolean requiresSymmetric = false;
		Class<KeyStoreSpi> ourClass = null;
		for (String ccnType : _ccnKeyStores.keySet()) {
			if (ccnType.equals(type)) {
				ourClass = (Class<KeyStoreSpi>)_ccnKeyStores.get(type);
				requiresSymmetric = true;
				break;
			}
		}
			
		try {
			KeyStoreSpi ourInstance = null;
			if (null == ourClass) {
				if (null == _searchedClass)
					_searchedClass = (Class<KeyStoreSpi>) Class.forName("java.security.KeyStoreSpi");
				ourClass = _searchedClass;
				Object[] objs = GetInstance.getInstance("KeyStore", ourClass, type).toArray();
				ourInstance = (KeyStoreSpi)objs[0];
			} else
				ourInstance = ourClass.newInstance();
			return new CCNKeyStore(ourInstance, null, type, requiresSymmetric);
		} catch (Exception e) {
			e.printStackTrace();
			throw new KeyStoreException(e);
		} 
	}
	
	public boolean requiresSymmetric() {
		return _requiresSymmetric;
	}
}
