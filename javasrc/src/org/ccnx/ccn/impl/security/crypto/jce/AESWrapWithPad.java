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

package org.ccnx.ccn.impl.security.crypto.jce;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

import org.bouncycastle.jce.provider.WrapCipherSpi;
import org.ccnx.ccn.io.content.WrappedKey;



public class AESWrapWithPad extends WrapCipherSpi {
	
	protected SecureRandom _random = new SecureRandom();
	
	public AESWrapWithPad() {
		super(new AESWrapWithPadEngine());
	}
	
	/**
	 * Temporarily expose internal wrapping functions till
	 * can make this a provider.
	 * @throws IllegalBlockSizeException 
	 * @throws InvalidKeyException 
	 */
	public byte [] wrap(Key wrappingKey, Key keyToBeWrapped) throws InvalidKeyException, IllegalBlockSizeException {
		engineInit(Cipher.WRAP_MODE, wrappingKey, _random);
		return engineWrap(keyToBeWrapped);
	}
	
	public Key unwrap(Key wrappingKey, byte [] wrappedKey, String wrappedKeyAlgorithm) throws InvalidKeyException {
		engineInit(Cipher.UNWRAP_MODE, wrappingKey, _random);
		return engineUnwrap(wrappedKey, wrappedKeyAlgorithm, WrappedKey.getCipherType(wrappedKeyAlgorithm));
	}

}
