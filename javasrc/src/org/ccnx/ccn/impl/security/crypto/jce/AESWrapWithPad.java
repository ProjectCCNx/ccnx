/*
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
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

import org.bouncycastle.jce.provider.WrapCipherSpi;
import org.ccnx.ccn.io.content.WrappedKey;


/**
 * Engine wrapper around RFC3394WrapWithPadEngine, which as part of a signed Provider
 * will let it be used via the JCE crypto interfaces. In the short term, expose the
 * wrap and unwrap functionality directly to allow it to be used without having to be signed.
 */
public class AESWrapWithPad extends WrapCipherSpi {
	
	protected SecureRandom _random = new SecureRandom();
	
	public AESWrapWithPad() {
		super(new AESWrapWithPadEngine());
	}
	
	/**
	 * Temporarily expose internal wrapping functions till
	 * can make this a provider.
	 * @param wrappingKey key to use to wrap another key
	 * @param keyToBeWrapped key to be wrapped
	 * @return the wrapped key
	 * @throws IllegalBlockSizeException if the wrapped key or its padded version does not match the block size of the ciphar
	 * @throws InvalidKeyException  if the wrappingKey is invalid
	 */
	public byte [] wrap(Key wrappingKey, Key keyToBeWrapped) throws InvalidKeyException, IllegalBlockSizeException {
		engineInit(Cipher.WRAP_MODE, wrappingKey, _random);
		return engineWrap(keyToBeWrapped);
	}
	
	/**
	 * Temporarily expose internal unwrapping functions till
	 * can make this a provider.
	 * @param wrappingKey key to use to wrap another key
	 * @param wrappedKey key to be unwrapped
	 * @param wrappedKeyAlgorithm algorithm to decode wrappedKey into a key for
	 * @return the unwrapped key
	 * @throws InvalidKeyException  if the wrappingKey is invalid
	 * @throws NoSuchAlgorithmException if the wrappedKeyAlgorithm is unknown. Thrown only
	 * 	in older versions of BouncyCastle, here for compatibility. (Later versions catch it
	 * 	and rethrow as an InvalidKeyException, which we do upstream from here. Can't do it
	 * 	here or we'd hit an unthrown exception error when running against newer BouncyCastle
	 * 	libraries.)
	 */
	public Key unwrap(Key wrappingKey, byte [] wrappedKey, String wrappedKeyAlgorithm) throws InvalidKeyException, NoSuchAlgorithmException {
		engineInit(Cipher.UNWRAP_MODE, wrappingKey, _random);
		return engineUnwrap(wrappedKey, wrappedKeyAlgorithm, WrappedKey.getCipherType(wrappedKeyAlgorithm));
	}

}
