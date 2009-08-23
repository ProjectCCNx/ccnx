package com.parc.ccn.security.crypto.jce;

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
