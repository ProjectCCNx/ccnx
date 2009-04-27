package com.parc.ccn.security.crypto;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Specifies encryption algorithm and keys to use for encrypting content.
 */
public class ContentKeys {
	public String encryptionAlgorithm;
	public SecretKeySpec encryptionKey;
	public IvParameterSpec masterIV;

	public ContentKeys(String encryptionAlgorithm, SecretKeySpec encryptionKey,
			IvParameterSpec masterIV) {
		this.encryptionAlgorithm = encryptionAlgorithm;
		this.encryptionKey = encryptionKey;
		this.masterIV = masterIV;
	}
}