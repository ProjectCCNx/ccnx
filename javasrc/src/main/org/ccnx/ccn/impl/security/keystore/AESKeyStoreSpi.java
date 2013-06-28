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
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.ccnx.ccn.impl.security.crypto.util.OIDLookup;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;

/**
 * This is a specialized keystore for storing symmetric keys. We looked at PKCS #11 for this but decided 
 * against it for now because industry doesn't seem to be standardizing around it - at least not yet, and
 * standard support for it is somewhat sketchy at this point.
 * 
 * The keystore can be used for only one key at a time and is located by naming it with a suffix
 * created from the key's digest.
 * 
 * Following is the formula for the KeyStore
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
 * 
 * ASN1 encoded KeyStore = Version || Key algorithm OID || SK
 */
public class AESKeyStoreSpi extends KeyStoreSpi {
	
	public static final int VERSION = 1;
	public static final String TYPE = "CCN_AES";
	public static final String MAC_ALGORITHM = "HMAC-SHA256";	// XXX Should these be settable?
	public static final String AES_ALGORITHM = "AES";
	public static final String AES_CRYPTO_ALGORITHM = "AES/CBC/PKCS5Padding";
	
	public static final int IV_SIZE = 16;

	public static final Random _random = new SecureRandom();
	
	public static Mac _macKeyMac;
	public static Mac _AESKeyMac;
	public static String _AESKeyAlgorithm = MAC_ALGORITHM;
	
	protected static DERInteger _version = new DERInteger(VERSION);
	
	protected byte[] _id = null;
	protected KeyStore.Entry _ourEntry = null;
	protected DERObjectIdentifier _oid = null;
	
	/*
	 * Convert from a key algorithm to a size for an encrypted key
	 * XXX might be some better way to do this but I don't know what it is...
	 */
	private static Map<String,Integer> _k2Size = new HashMap<String,Integer>();
	
	static {
		_k2Size.put("SHA256", 48);
		try {
			_macKeyMac = Mac.getInstance(MAC_ALGORITHM);
			int maxKeyLen = Cipher.getMaxAllowedKeyLength(AES_ALGORITHM);
			if (maxKeyLen < 160)
				_AESKeyAlgorithm = "HMACMD5";
			else if (maxKeyLen < 256)
				_AESKeyAlgorithm = /* "HMACSHA1"; */ "HMACMD5"; // HMACSHA1 doesn't seem to work for some reason...
			_AESKeyMac = Mac.getInstance(_AESKeyAlgorithm);
		} catch (NoSuchAlgorithmException e) {
			Log.severe("Couldn't initialize for keystore due to: {0}", e.getMessage());
		}
	}
	
	/*
	 * TODO
	 * As far as I know we don't need to do most of this stuff. If we discover its needed, it will be filled in later
	 */
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
	
	/**
	 * Create a new entry for the keystore if needed. Since there is only 1 key in the keystore
	 * we only ever return the single entry.
	 */
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

	/**
	 * Load in the key from the keystore file
	 */
	@Override
	public void engineLoad(InputStream stream, char[] password) throws IOException,
			NoSuchAlgorithmException, CertificateException {
		if (null == stream)
			return;
		if (null != _id)
			return;		// We already have the key so don't need to reload it
		ASN1InputStream ais = new ASN1InputStream(stream);
		DERSequence ds = (DERSequence) ais.readObject();
		DERInteger version = (DERInteger)ds.getObjectAt(0);
		if (version.getValue().intValue() != VERSION)
			throw new IOException("Unsupported AESKeyStore version: " + version.getValue().intValue());
		_oid = (DERObjectIdentifier) ds.getObjectAt(1);
		String keyAlgorithm = OIDLookup.getDigestName(_oid.toString());
		int aeslen = keyAlgorithmToCipherSize(keyAlgorithm);
		ASN1OctetString os = (ASN1OctetString) ds.getObjectAt(2);
		byte [] cryptoData = os.getOctets();
		int checkLength = cryptoData.length - (IV_SIZE + aeslen);
		if (checkLength <= 0)
			throw new IOException("Corrupted keystore");
		byte[] iv = new byte[IV_SIZE];
		System.arraycopy(cryptoData, 0, iv, 0, iv.length);
		Tuple<SecretKeySpec, SecretKeySpec> keys = initializeForAES(password);
		try {
			Cipher cipher = Cipher.getInstance(AES_CRYPTO_ALGORITHM);
			IvParameterSpec ivspec = new IvParameterSpec(iv);
			cipher.init(Cipher.DECRYPT_MODE, keys.first(), ivspec);
			byte[] cryptBytes = new byte[aeslen];
			System.arraycopy(cryptoData, IV_SIZE, cryptBytes, 0, cryptBytes.length);
			_id = cipher.doFinal(cryptBytes);
			byte[] checkbuf = new byte[IV_SIZE + cryptBytes.length];
			System.arraycopy(iv, 0, checkbuf, 0, IV_SIZE);
			System.arraycopy(cryptBytes, 0, checkbuf, IV_SIZE, cryptBytes.length);
			byte[] check = new byte[checkLength];
			System.arraycopy(cryptoData, IV_SIZE + aeslen, check, 0, checkLength);
			_macKeyMac.init(keys.second());
			byte[] hmac = _macKeyMac.doFinal(checkbuf);
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
		String oid = OIDLookup.getDigestOID(key.getAlgorithm());
		if (null == oid)
			throw new KeyStoreException("Not a Mac algorithm we recognize: " + key.getAlgorithm());
		_oid = new DERObjectIdentifier(oid);
	}

	@Override
	public int engineSize() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Store the key from _id into a keystore file
	 */
	@Override
	public void engineStore(OutputStream stream, char[] password) throws IOException,
			NoSuchAlgorithmException, CertificateException {
		if (null == _id)
			throw new IOException("Key not entered yet");
		ASN1OutputStream aos = new ASN1OutputStream(stream);
		Tuple<SecretKeySpec, SecretKeySpec> keys = initializeForAES(password);
		try {
			byte[] iv = new byte[IV_SIZE];
			_random.nextBytes(iv);
			byte[] aesCBC = null;
			Cipher cipher = Cipher.getInstance(AES_CRYPTO_ALGORITHM);
			IvParameterSpec ivspec = new IvParameterSpec(iv);
			cipher.init(Cipher.ENCRYPT_MODE, keys.first(), ivspec);
			aesCBC = cipher.doFinal(_id);
			_macKeyMac.init(keys.second());
			byte[] checkbuf = new byte[iv.length + aesCBC.length];
			System.arraycopy(iv, 0, checkbuf, 0, iv.length);
			System.arraycopy(aesCBC, 0, checkbuf, iv.length, aesCBC.length);
			byte[] part3 = _macKeyMac.doFinal(checkbuf);
			// TODO might be a better way to do this but am not sure how
			// (and its not really that important anyway)
			byte[] asn1buf = new byte[iv.length + aesCBC.length + part3.length];
			System.arraycopy(checkbuf, 0, asn1buf, 0, checkbuf.length);
			System.arraycopy(part3, 0, asn1buf, iv.length + aesCBC.length, part3.length);
			ASN1OctetString os = new DEROctetString(asn1buf);
			ASN1Encodable[] ae = new ASN1Encodable[3];
			ae[0] = _version;
			ae[1] = _oid;
			ae[2] = os;
			DERSequence ds = new DERSequence(ae);
			aos.writeObject(ds);
			aos.flush();
			aos.close();
		} catch (Exception e) {
			throw new IOException(e);
		}
	}
	
	/**
	 * Create aesK and macK from password as in formula above
	 * @param password
	 * @return
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	private Tuple<SecretKeySpec, SecretKeySpec> initializeForAES(char[] password) throws IOException, NoSuchAlgorithmException {
		Tuple<SecretKeySpec, SecretKeySpec> result = null;
		
		byte[] passwordAsBytes = charToByteArray(password);
		byte[] little = new byte[1];
		SecretKeySpec passK = new SecretKeySpec(passwordAsBytes, _AESKeyAlgorithm);
		try {
			_AESKeyMac.init(passK);
			little[0] = 0;
			byte[] aesKBytes = _AESKeyMac.doFinal(little);
			SecretKeySpec aesK = new SecretKeySpec(aesKBytes, AES_ALGORITHM);
			_macKeyMac.init(passK);
			little[0] = 1;
			byte [] macKBytes = _macKeyMac.doFinal(little);
			SecretKeySpec macK = new SecretKeySpec(macKBytes, MAC_ALGORITHM);
			result = new Tuple<SecretKeySpec, SecretKeySpec>(aesK, macK);
		} catch (Exception e) {
			throw new IOException(e);
		}
		return result;
	}
	
	private int keyAlgorithmToCipherSize(String algorithm) throws NoSuchAlgorithmException {
		Integer size = _k2Size.get(algorithm);
		if (null == size)
			throw new NoSuchAlgorithmException("Not a recognized algorithm: " + algorithm);
		return size;
	}
	
	/**
	 * Service providers automatically supply the passphrase in a char array but we need
	 * a byte array. 
	 * 
	 * TODO Perhaps this should be moved to DataUtils
	 * 
	 * @param in
	 * @return
	 */
	private byte[] charToByteArray(char[] in) {
		byte[] bytes = new byte[in.length];
		for (int i = 0; i < in.length; i++) {
			bytes[i] = (byte)in[i];
		}
		return bytes;
	}
}
