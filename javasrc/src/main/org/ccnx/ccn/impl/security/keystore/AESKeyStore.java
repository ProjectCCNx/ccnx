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

/**
 * Creates a CCN proprietary keystore to hold symmetric keys
 * See AESKeyStoreSpi for the format and features of this keystore.
 */
public class AESKeyStore extends KeyStore {
	
	protected AESKeyStore(KeyStoreSpi keyStoreSpi, Provider provider,
			String type) {
		super(keyStoreSpi, provider, type);
	}
	
	public static AESKeyStore getInstance(String type) throws KeyStoreException {
		return new AESKeyStore(new AESKeyStoreSpi(), null, type);
	}
}
