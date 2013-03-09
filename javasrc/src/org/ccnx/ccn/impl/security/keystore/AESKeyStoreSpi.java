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
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.impl.support.Tuple;

/**
 * This is a specialized keystore for users who are using a symmetric key as their user key.
 * CCN keystores only store 1 key so the algorithm used for the key storage is as follows:
 * 
 * Let P=passphrase
 * Let PT = symmetric key to store
 * Let IV = random 16-bytes
 *
 * aesK = HMAC-SHA256(P, '\0')
 * macK = HMAC-SHA256(P, '\1')
 * AES256-CBC(IV, key, PT) - performs AES256 in CBC mode
 *
 * SK = IV || AES256-CBC(IV, aesK, PT) || HMAC-SHA256(macK, AES256-CBC(IV, aesK, PT))
 *
 * SK is the symmetric keystore ciphertext
 */
public class AESKeyStoreSpi extends KeyStoreSpi {
	
	public static final String MAC_ALGORITHM = "HMAC-SHA256";	// XXX Should these be settable?
	public static final String AES_CRYPTO_ALGORITHM = "AES/CBC/PKCS5Padding";
	
	public static final int IV_SIZE = 16;
	public static final int AES_SIZE = 48;

	public static final Random _random = new SecureRandom();
	
	public Mac _mac;
	
	protected byte[] _id = null;
	protected KeyStore.Entry _ourEntry = null;
	
	public AESKeyStoreSpi() {
		try {
			_mac = Mac.getInstance(MAC_ALGORITHM);
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public Enumeration<String> engineAliases() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean engineContainsAlias(String arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void engineDeleteEntry(String arg0) throws KeyStoreException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Certificate engineGetCertificate(String arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String engineGetCertificateAlias(Certificate arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Certificate[] engineGetCertificateChain(String arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Date engineGetCreationDate(String arg0) {
		// TODO Auto-generated method stub
		return null;
	}
	
	public KeyStore.Entry engineGetEntry(String alias, KeyStore.ProtectionParameter protParam) {
		if (null == _ourEntry) {
			if (null != _id) {
				SecretKeySpec sks = new SecretKeySpec(_id, MAC_ALGORITHM);
				_ourEntry = new KeyStore.SecretKeyEntry(sks);
			}
		}
		return _ourEntry;
	}

	@Override
	public Key engineGetKey(String arg0, char[] arg1)
			throws NoSuchAlgorithmException, UnrecoverableKeyException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean engineIsCertificateEntry(String arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean engineIsKeyEntry(String arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void engineLoad(InputStream stream, char[] password) throws IOException,
			NoSuchAlgorithmException, CertificateException {
		if (null == stream)
			return;
		if (null != _id)
			return;		// We already have the key so don't need to reload it
		byte[] iv = new byte[IV_SIZE];
		if (stream.read(iv) < IV_SIZE) {
			throw new IOException("Truncated AES keystore");
		}
		Tuple<SecretKeySpec, SecretKeySpec> keys = initializeForAES(password);
		try {
			Cipher cipher = Cipher.getInstance(AES_CRYPTO_ALGORITHM);
			IvParameterSpec ivspec = new IvParameterSpec(iv);
			cipher.init(Cipher.DECRYPT_MODE, keys.first(), ivspec);
			byte[] cryptBytes = new byte[AES_SIZE];
			if (stream.read(cryptBytes) < AES_SIZE) {
				throw new IOException("Truncated AES keystore");
			}
			_id = cipher.doFinal(cryptBytes);
			byte[] check = new byte[stream.available()];
			stream.read(check);
			_mac.init(keys.second());
			byte[] hmac = _mac.doFinal(cryptBytes);
			if (!Arrays.equals(hmac, check))
				throw new IOException("Bad signature in AES keystore");
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	@Override
	public void engineSetCertificateEntry(String arg0, Certificate arg1)
			throws KeyStoreException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void engineSetKeyEntry(String arg0, byte[] arg1, Certificate[] arg2)
			throws KeyStoreException {
	}

	@Override
	public void engineSetKeyEntry(String name, Key key, char[] arg2,
			Certificate[] arg3) throws KeyStoreException {
		_id = key.getEncoded();
	}

	@Override
	public int engineSize() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void engineStore(OutputStream stream, char[] password) throws IOException,
			NoSuchAlgorithmException, CertificateException {
		if (null == _id)
			throw new IOException("Key not entered yet");
		Tuple<SecretKeySpec, SecretKeySpec> keys = initializeForAES(password);
		try {
			byte[] iv = new byte[IV_SIZE];
			_random.nextBytes(iv);
			byte[] aesCBC = null;
			Cipher cipher = Cipher.getInstance(AES_CRYPTO_ALGORITHM);
			IvParameterSpec ivspec = new IvParameterSpec(iv);
			cipher.init(Cipher.ENCRYPT_MODE, keys.first(), ivspec);
			aesCBC = cipher.doFinal(_id);
			_mac.init(keys.second());
			byte[] part4 = _mac.doFinal(aesCBC);
			stream.write(iv);
			stream.write(aesCBC);
			stream.write(part4);
		} catch (Exception e) {
			throw new IOException(e);
		}
	}
	
	private Tuple<SecretKeySpec, SecretKeySpec> initializeForAES(char[] password) throws IOException, NoSuchAlgorithmException {
		Tuple<SecretKeySpec, SecretKeySpec> result = null;
		
		byte[] passwordAsBytes = charToByteArray(password);		
		SecretKeySpec passK = new SecretKeySpec(passwordAsBytes, MAC_ALGORITHM);
		try {
			_mac.init(passK);
			byte[] little = new byte[1];
			little[0] = 0;
			byte[] aesKBytes = _mac.doFinal(little);
			SecretKeySpec aesK = new SecretKeySpec(aesKBytes, MAC_ALGORITHM);
			_mac.init(passK);
			little[0] = 1;
			byte [] macKBytes = _mac.doFinal(little);
			SecretKeySpec macK = new SecretKeySpec(macKBytes, MAC_ALGORITHM);
			result = new Tuple<SecretKeySpec, SecretKeySpec>(aesK, macK);
		} catch (Exception e) {
			throw new IOException(e);
		}
		return result;
	}
	
	private byte[] charToByteArray(char[] in) {
		byte[] bytes = new byte[in.length << 1];
		for (int i = 0; i < in.length; i++) {
			int bpos = i << 1;
			bytes[bpos] = (byte)((in[i] & 0xff00) >> 8);
			bytes[bpos + 1] = (byte)(in[i] & 0xff);
		}
		return bytes;
	}
}
