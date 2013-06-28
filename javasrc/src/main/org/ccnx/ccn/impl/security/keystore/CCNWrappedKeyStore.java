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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.Enumeration;
import java.util.HashMap;

/**
 * Wraps keystores including standard java keystores and CCN proprietary keystores. Provides
 * a facility to determine whether the keystore supports only symmetric keys.
 */
public final class CCNWrappedKeyStore {
	boolean _requiresSymmetric = false;
	static HashMap<TypeAndSymmetric, Class<?>> _ourKeyStores = new HashMap<TypeAndSymmetric, Class<?>>();
	static KeyStore _keyStore = null;
	
	static class TypeAndSymmetric {
		private String _type;
		private boolean _requiresSymmetric;
		
		private TypeAndSymmetric(String type, boolean requiresSymmetric) {
			_type = type;
			_requiresSymmetric = requiresSymmetric;
		}
	}
	
	static		{
		_ourKeyStores.put(new TypeAndSymmetric(AESKeyStoreSpi.TYPE, true), AESKeyStore.class);
	}
	
	private CCNWrappedKeyStore(KeyStore keyStore, boolean requiresSymmetric) {
		_keyStore = keyStore;
		_requiresSymmetric = requiresSymmetric;
	}
	
	public static CCNWrappedKeyStore getInstance(String type) throws KeyStoreException {
		for (TypeAndSymmetric tas :  _ourKeyStores.keySet()) {
			if (tas._type.equals(type)) {
				Class<?> ourClass = _ourKeyStores.get(tas);
				try {
					Method m = ourClass.getMethod("getInstance", String.class);
					KeyStore ks = (KeyStore)m.invoke(null, type);
					return new CCNWrappedKeyStore(ks, tas._requiresSymmetric);
				} catch (IllegalAccessException e) {
					throw new KeyStoreException(e);
				} catch (SecurityException e) {
					throw new KeyStoreException(e);
				} catch (NoSuchMethodException e) {
					throw new KeyStoreException(e);
				} catch (IllegalArgumentException e) {
					throw new KeyStoreException(e);
				} catch (InvocationTargetException e) {
					throw new KeyStoreException(e);
				}
			}
		}
		return new CCNWrappedKeyStore(KeyStore.getInstance(type), false);
	}
	
	public boolean requiresSymmetric() {
		return _requiresSymmetric;
	}
	
	public final void load(InputStream stream, char[] password) 
			throws IOException, NoSuchAlgorithmException, CertificateException {
        _keyStore.load(stream, password);	
	}
	
	public KeyStore.Entry getEntry(String alias, KeyStore.ProtectionParameter protParam) 
			throws NoSuchAlgorithmException, UnrecoverableEntryException, KeyStoreException {
		return _keyStore.getEntry(alias, protParam);
	}
	
	public void	setEntry(String alias, KeyStore.Entry entry, KeyStore.ProtectionParameter protParam) 
			throws KeyStoreException {
		_keyStore.setEntry(alias, entry, protParam);
	}
	
	public void	store(OutputStream stream, char[] password) 
			throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
		_keyStore.store(stream, password);
	}
	
	public Enumeration<String>	aliases() throws KeyStoreException {
		return _keyStore.aliases();
	}
	
	public boolean	isKeyEntry(String alias) throws KeyStoreException {
		return _keyStore.isKeyEntry(alias);
	}
}
