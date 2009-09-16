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

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.jce.AESWrapWithPad;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * 
 * For now, we have a very loose definition of default -- the default wrap algorithm
 * depends on the type of key being used to wrap; similarly the default key algorithm
 * depends on the type of the key being wrapped. We assume that the wrapper and unwrapper
 * usually know the type of the wrapping key, and can derive the wrapAlgorithm. The
 * keyAlgorithm is more often necessary to determine how to decode the key once unwrapped
 * so it is more frequently present. Both are optional.
 * 
 * If the caller specifies values they will be encoded on the wire and decoded on 
 * the other end; defaults will not currently be enforced automatically. This means
 * equals behavior should be watched closely. 

 * @author smetters
 *
 */
public class WrappedKey extends GenericXMLEncodable implements XMLEncodable {

	protected static final String WRAPPED_KEY_ELEMENT = "WrappedKey";
	protected static final String WRAPPING_KEY_IDENTIFIER_ELEMENT = "WrappingKeyIdentifier";
	protected static final String WRAPPING_KEY_NAME_ELEMENT = "WrappingKeyName";
	protected static final String WRAP_ALGORITHM_ELEMENT = "WrapAlgorithm";
	protected static final String KEY_ALGORITHM_ELEMENT = "KeyAlgorithm";
	protected static final String LABEL_ELEMENT = "Label";
	protected static final String ENCRYPTED_NONCE_KEY_ELEMENT = "EncryptedNonceKey";
	protected static final String ENCRYPTED_KEY_ELEMENT = "EncryptedKey";
	
	protected static final String NONCE_KEY_ALGORITHM = "AES";
	protected static final int NONCE_KEY_LENGTH = 128;
	
	public class WrappingKeyName extends ContentName {

		public WrappingKeyName(ContentName name) {
			super(name);
		}
		
		public WrappingKeyName() {}
		
		@Override
		public String getElementLabel() { 
			return WRAPPING_KEY_NAME_ELEMENT;
		}
	}

	/**
	 * A CCNNetworkObject wrapper around WrappedKey, used for easily saving and retrieving
	 * versioned WrappedKeys to CCN. A typical pattern for using network objects to save
	 * objects that happen to be encodable or serializable is to incorporate such a static
	 * member wrapper class subclassing CCNEncodableObject, CCNSerializableObject, or
	 * CCNNetworkObject itself inside the main class definition.
	 */
	public static class WrappedKeyObject extends CCNEncodableObject<WrappedKey> {

		public WrappedKeyObject(ContentName name, WrappedKey data, CCNHandle handle) throws IOException {
			super(WrappedKey.class, name, data, handle);
		}
		
		public WrappedKeyObject(ContentName name, PublisherPublicKeyDigest publisher,
				CCNHandle handle) throws IOException, XMLStreamException {
			super(WrappedKey.class, name, publisher, handle);
		}
		
		public WrappedKeyObject(ContentName name, 
				CCNHandle handle) throws IOException, XMLStreamException {
			super(WrappedKey.class, name, (PublisherPublicKeyDigest)null, handle);
		}
		
		public WrappedKeyObject(ContentObject firstBlock,
				CCNHandle handle) throws IOException, XMLStreamException {
			super(WrappedKey.class, firstBlock, handle);
		}
		
		public WrappedKey wrappedKey() throws ContentNotReadyException, ContentGoneException { return data(); }
	}

	private static final Map<String,String> _WrapAlgorithmMap = new HashMap<String,String>();

	byte [] _wrappingKeyIdentifier;
	WrappingKeyName _wrappingKeyName;
	String _wrapAlgorithm;
	String _keyAlgorithm;
	String _label;
	byte [] _encryptedNonceKey;
	byte [] _encryptedKey;

	
	static {
		// In Java 1.5, many of these require BouncyCastle. They are typically built in in 1.6.
		_WrapAlgorithmMap.put("AES", "AESWRAPWITHPAD");
		_WrapAlgorithmMap.put("RSA", "RSA/NONE/OAEPWithSHA256AndMGF1Padding");
	}
	
	/**
	 * Factory methods to build wrapped keys out of their components, wrapping 
	 * in the process. For the moment, these use only the default wrapping algorithms.
	 */
	
	/**
	 * Wraps a {symmetric, private} key in another {symmetric, public} key, using standard wrap
	 * algorithm. Does not include an identifier of the wrapping key; that
	 * can be added if necessary using setWrappingKeyIdentifier(Key).
	 * Default wrap algorithm if wrapping key is AES is AESWrap (RFC3394, NIST standard);
	 * this is available in Java 1.6, or BouncyCastle.
	 * 
	 * @param keyToBeWrapped The key to wrap, can be symmetric, private, or public.
	 * @param keyAlgorithm optional algorithm to associate with the wrapped key; if null
	 *    we use  key.getAlgorithm(). 
	 * @param keyLabel a friendly name for the key.
	 * @param wrappingKey The key to use for wrapping. This can be a symmetric key or a public key
	 * 	(as noted above, some public key algorithms may require Java's unlimited strength policy
	 * 	files). If the wrapping key is a public key, and the wrapped key is a private key (which
	 *  may extend past the block length of the public key) we will automatically generate a nonce (random)
	 *   AES key, wrap the private key in that, and then wrap that nonce key in the public key.
	 *   We derive the wrapping algorithm to use as a function of this key's algorithm. Eventually may
	 *   want to allow it to be passed in (merely encrypting with the key as usual may not be the
	 *   best key wrap algorithm; keys are high entropy and often require specialized padding schemes).
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws IllegalBlockSizeException 
	 */
	public static WrappedKey wrapKey(Key keyToBeWrapped,
									 String keyAlgorithm, 
									 String keyLabel, 
									 Key wrappingKey) 
		throws InvalidKeyException {

		String wrappingAlgorithm = wrapAlgorithmForKey(wrappingKey.getAlgorithm());
		if (null == wrappingAlgorithm) {
			// Try this, may not work...
			wrappingAlgorithm = wrappingKey.getAlgorithm();
		}
		
		byte [] wrappedNonceKey = null;
		byte [] wrappedKey = null;

		if (wrappingAlgorithm.equalsIgnoreCase("AESWrapWithPad")) {
			try {
				wrappedKey = AESWrapWithPad(wrappingKey, keyToBeWrapped);
			} catch (IllegalBlockSizeException e) {
				Log.warning("Unexpected IllegalBlockSizeException attempting to instantiate wrapping algorithm.");
				throw new InvalidKeyException("Unexpected IllegalBlockSizeException attempting to instantiate wrapping algorithm");
			}
		} else {

			Cipher wrapCipher = null;
			try {
				wrapCipher = Cipher.getInstance(wrappingAlgorithm);
			} catch (NoSuchAlgorithmException e) {
				Log.warning("Unexpected NoSuchAlgorithmException attempting to instantiate wrapping algorithm.");
				throw new InvalidKeyException("Unexpected NoSuchAlgorithmException attempting to instantiate wrapping algorithm.");
			} catch (NoSuchPaddingException e) {
				Log.warning("Unexpected NoSuchPaddingException attempting to instantiate wrapping algorithm.");
				throw new InvalidKeyException("Unexpected NoSuchPaddingException attempting to instantiate wrapping algorithm");
			}
			wrapCipher.init(Cipher.WRAP_MODE, wrappingKey);
			
			// If we are dealing with a short-block cipher, like RSA, we need to
			// interpose a nonce key.
			Key nonceKey = null;
			try {

				int wrappedKeyType = getCipherType(keyToBeWrapped.getAlgorithm());
				// If we're wrapping a private key in a public key, need to handle multi-block
				// keys. Probably don't want to do that with ECB mode. ECIES already acts
				// as a hybrid cipher, so we don't need to do this for that.
				if (((Cipher.PRIVATE_KEY == wrappedKeyType) || (Cipher.PUBLIC_KEY == wrappedKeyType)) && 
						(wrappingKey instanceof PublicKey) && (!wrappingKey.getAlgorithm().equals("ECIES"))) {

					nonceKey = generateNonceKey();
					//try {
					// We know the nonce key is an AES key. Use standard wrap algorithm.
					// DKS -- fix when have provider.
					//Cipher nonceCipher = Cipher.getInstance(wrapAlgorithmForKey(nonceKey.getAlgorithm()));
					//nonceCipher.init(Cipher.WRAP_MODE, nonceKey);
					//wrappedKey = nonceCipher.wrap(keyToBeWrapped);
					wrappedKey = AESWrapWithPad(nonceKey, keyToBeWrapped);
					//} catch (NoSuchAlgorithmException nsex) {
					//	Log.warning("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
					//	Log.warningStackTrace(nsex);
					//	throw new RuntimeException("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);	    		
					//}
					wrappedNonceKey = wrapCipher.wrap(nonceKey);

				} else {
					wrappedKey = wrapCipher.wrap(keyToBeWrapped);
				}
			} catch (IllegalBlockSizeException ex) {
				Log.warning("IllegalBlockSizeException " + ex.getMessage() + " in wrap key -- unexpected, we should have compensated for this. Key to be wrapped algorithm? " + keyToBeWrapped.getAlgorithm() + ". Using nonce key? " + (null == nonceKey));
				throw new InvalidKeyException("IllegalBlockSizeException " + ex.getMessage() + " in wrap key -- unexpected, we should have compensated for this. Key to be wrapped algorithm? " + keyToBeWrapped.getAlgorithm() + ". Using nonce key? " + (null == nonceKey));
			}
		}
		// Default wrapping algorithm is being used, don't need to include it.
	    return new WrappedKey(null, null, 
	    					 ((null == keyAlgorithm) ? keyToBeWrapped.getAlgorithm() : keyAlgorithm), 
	    					 keyLabel, wrappedNonceKey, wrappedKey);
	}
	
	/**
	 * Represent an already-wrapped key as a WrappedKey. 
	 * @param wrappingKeyIdentifier a byte ID for this key, usually the digest of the encoded key.
	 * @param encryptedKey the wrapped, encoded key.
	 */
	public WrappedKey(byte [] wrappingKeyIdentifier, byte [] encryptedKey) {
		this(wrappingKeyIdentifier, null, null, null, null, encryptedKey);
	}
	
	/**
	 * Represent an already-wrapped key as a WrappedKey. 
	 * @param wrappingKeyIdentifier a byte ID for this key, usually the digest of the encoded key.
	 * @param label a friendly name for the key.
	 * @param encryptedKey the wrapped, encoded key.
	 */
	public WrappedKey(byte [] wrappingKeyIdentifier, String label, byte [] encryptedKey) {
		this(wrappingKeyIdentifier, null, null, label, null, encryptedKey);
	}

	/**
	 * Represent an already-wrapped key as a WrappedKey. 
	 * @param wrappingKeyIdentifier a byte ID for this key, usually the digest of the encoded key.
	 * @param wrapAlgorithm the algorithm used to wrap this key, if null the default wrap algorithm for
	 * 		the decryption key (specified to unwrapKey()) is used
	 * @param keyAlgorithm the algorithm of the wrapped (encrypted, encoded) key. Necessary to decode
	 * 		the key back into a Key object as part of unwrapping. Can be specified at unwrapping time if
	 * 		not stored here.
	 * @param label a friendly name for the key.
	 * @param encryptedKey the wrapped, encoded key.
	 */
	public WrappedKey(byte [] wrappingKeyIdentifier, String wrapAlgorithm, String keyAlgorithm,
			  String label, byte [] encryptedKey) {
		this(wrappingKeyIdentifier, wrapAlgorithm, keyAlgorithm, label, null, encryptedKey);
	}

	/**
	 * Represent an already-wrapped key as a WrappedKey. 
	 * @param wrappingKeyIdentifier a byte ID for this key, usually the digest of the encoded key.
	 * @param wrapAlgorithm the algorithm used to wrap this key, if null the default wrap algorithm for
	 * 		the decryption key (specified to unwrapKey()) is used
	 * @param keyAlgorithm the algorithm of the wrapped (encrypted, encoded) key. Necessary to decode
	 * 		the key back into a Key object as part of unwrapping. Can be specified at unwrapping time if
	 * 		not stored here.
	 * @param label a friendly name for the key.
	 * @param encryptedNonceKey if the key is a private or public key wrapped by a public key, this defines
	 * 		an encrypted interposed nonce key where the nonce key is used to wrap the private or public
	 * 		key and the wrapping key is used to wrap the nonce key.
	 * @param encryptedKey the wrapped, encoded key.
	 */
	public WrappedKey(byte [] wrappingKeyIdentifier, String wrapAlgorithm, String keyAlgorithm,
					  String label, byte [] encryptedNonceKey, byte [] encryptedKey) {
		_wrappingKeyIdentifier = wrappingKeyIdentifier;
		_wrapAlgorithm = wrapAlgorithm;
		_keyAlgorithm = keyAlgorithm;
		_label = label;
		_encryptedNonceKey = encryptedNonceKey;
		_encryptedKey = encryptedKey;
	}
	
	/**
	 * Empty constructor for decoding.
	 */
	public WrappedKey() {
	}
	
	/**
	 * Unwraps an encrypted key, and decodes it into a Key of an algorithm type
	 * specified by keyAlgorithm(). See unwrapKey(Key, String) for details.
	 * @see unwrapKey(Key, String)
	 **/
	public Key unwrapKey(Key unwrapKey) throws InvalidKeyException, InvalidCipherTextException {

		if (null == keyAlgorithm()) {
			throw new InvalidCipherTextException("No algorithm specified for key to be unwrapped!");
		}
		try {
			return unwrapKey(unwrapKey, keyAlgorithm());
		} catch (NoSuchAlgorithmException e) {
			throw new InvalidCipherTextException("Algorithm specified for wrapped key " + keyAlgorithm() + " is unknown: " + e.getMessage());
		}
	}

	/**
	 * Unwraps an encrypted key, and decodes it into a Key of an algorithm type
	 * specified by wrappedKeyAlgorithm.
	 * @param unwrapKey the key to use to decrypt this wrapped key.
	 * @param wrappedKeyAlgorithm the algorithm of the wrapped key, used in decoding it.
	 * @return the decrypted key if successful.
	 * @throws InvalidKeyException if we encounter an error using the unwrapKey to decrypt.
	 * @throws InvalidCipherTextException if the wrapped key is not a valid encrypted key.
	 * @throws NoSuchAlgorithmException if we do not recognize the wrappedKeyAlgorithm.
	 **/
	public Key unwrapKey(Key unwrapKey, String wrappedKeyAlgorithm) 
			throws InvalidKeyException, InvalidCipherTextException, NoSuchAlgorithmException {

		Key unwrappedKey = null;
		Log.info("wrap algorithm: " + wrapAlgorithm() + " wa for key " +
				wrapAlgorithmForKey(unwrapKey.getAlgorithm()));
		if (((null != wrapAlgorithm()) && (wrapAlgorithm().equalsIgnoreCase("AESWrapWithPad"))) || 
							wrapAlgorithmForKey(unwrapKey.getAlgorithm()).equalsIgnoreCase("AESWrapWithPad")) {
			unwrappedKey = AESUnwrapWithPad(unwrapKey, wrappedKeyAlgorithm, encryptedKey(), 0, encryptedKey().length);
		} else {
			Cipher unwrapCipher = null;
			try {
				if (null != wrapAlgorithm()) {
					unwrapCipher = Cipher.getInstance(wrapAlgorithm());
				} else {
					unwrapCipher = Cipher.getInstance(wrapAlgorithmForKey(unwrapKey.getAlgorithm()));
				}
			} catch (NoSuchAlgorithmException e) {
				Log.warning("Unexpected NoSuchAlgorithmException attempting to instantiate wrapping algorithm.");
				throw new InvalidKeyException("Unexpected NoSuchAlgorithmException attempting to instantiate wrapping algorithm.");
			} catch (NoSuchPaddingException e) {
				Log.warning("Unexpected NoSuchPaddingException attempting to instantiate wrapping algorithm.");
				throw new InvalidKeyException("Unexpected NoSuchPaddingException attempting to instantiate wrapping algorithm");
			}

			unwrapCipher.init(Cipher.UNWRAP_MODE, unwrapKey);
			int keyType = getCipherType(wrappedKeyAlgorithm);

			if (null != encryptedNonceKey()) {
				try {
					Key nonceKey = unwrapCipher.unwrap(encryptedNonceKey(), NONCE_KEY_ALGORITHM, Cipher.SECRET_KEY);

					//Cipher nonceKeyCipher = Cipher.getInstance(wrapAlgorithmForKey(NONCE_KEY_ALGORITHM));
					//nonceKeyCipher.init(Cipher.UNWRAP_MODE, nonceKey);
					//unwrappedKey = nonceKeyCipher.unwrap(encryptedKey(), wrappedKeyAlgorithm, keyType);
					unwrappedKey = AESUnwrapWithPad(nonceKey, wrappedKeyAlgorithm, encryptedKey(), 0, encryptedKey().length);

				} catch(NoSuchAlgorithmException nsex) {
					Log.warning("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
					Log.warningStackTrace(nsex);
					throw new RuntimeException("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);	    		
				}
			} else {
				unwrappedKey = unwrapCipher.unwrap(encryptedKey(), wrappedKeyAlgorithm, keyType);
			}
		}
	    return unwrappedKey;
	}

	public byte [] wrappingKeyIdentifier() { return _wrappingKeyIdentifier; }
	
	public void setWrappingKeyIdentifier(byte [] wrappingKeyIdentifier) {
		_wrappingKeyIdentifier = wrappingKeyIdentifier;
	}
	
	public void setWrappingKeyIdentifier(Key wrappingKey) {
		setWrappingKeyIdentifier(wrappingKeyIdentifier(wrappingKey));
	}
	
	public static byte [] wrappingKeyIdentifier(Key wrappingKey) {
		return CCNDigestHelper.digest(wrappingKey.getEncoded());
	}
	
	public ContentName wrappingKeyName() { return _wrappingKeyName; }
	public void setWrappingKeyName(ContentName keyName) { _wrappingKeyName = new WrappingKeyName(keyName); }
	
	public String wrapAlgorithm() { return _wrapAlgorithm; }
	public String keyAlgorithm() { return _keyAlgorithm; }
	public String label() { return _label; }
	public byte [] encryptedNonceKey() { return _encryptedNonceKey; }
	public byte [] encryptedKey() { return _encryptedKey; }

	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(getElementLabel());

		if (decoder.peekStartElement(WRAPPING_KEY_IDENTIFIER_ELEMENT)) {
			_wrappingKeyIdentifier = decoder.readBinaryElement(WRAPPING_KEY_IDENTIFIER_ELEMENT); 
		}
		
		if (decoder.peekStartElement(WRAPPING_KEY_NAME_ELEMENT)) {
			_wrappingKeyName = new WrappingKeyName();
			_wrappingKeyName.decode(decoder);
		}
		
		if (decoder.peekStartElement(WRAP_ALGORITHM_ELEMENT)) {
			_wrapAlgorithm = decoder.readUTF8Element(WRAP_ALGORITHM_ELEMENT); 
		}

		if (decoder.peekStartElement(KEY_ALGORITHM_ELEMENT)) {
			_keyAlgorithm = decoder.readUTF8Element(KEY_ALGORITHM_ELEMENT); 
		}

		if (decoder.peekStartElement(LABEL_ELEMENT)) {
			_label = decoder.readUTF8Element(LABEL_ELEMENT); 
		}

		if (decoder.peekStartElement(ENCRYPTED_NONCE_KEY_ELEMENT)) {
			_encryptedNonceKey = decoder.readBinaryElement(ENCRYPTED_NONCE_KEY_ELEMENT); 
		}
		
		_encryptedKey = decoder.readBinaryElement(ENCRYPTED_KEY_ELEMENT);
		
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		
		encoder.writeStartElement(getElementLabel());
		
		if (null != wrappingKeyIdentifier()) {
			// needs to handle null WKI
			encoder.writeElement(WRAPPING_KEY_IDENTIFIER_ELEMENT, wrappingKeyIdentifier());
		}
		
		if (null != wrappingKeyName()) {
			wrappingKeyName().encode(encoder);
		}

		if (null != wrapAlgorithm()) {
			//String wrapOID = OIDLookup.getCipherOID(wrapAlgorithm());
			encoder.writeElement(WRAP_ALGORITHM_ELEMENT, wrapAlgorithm());
		}
		
		if (null != keyAlgorithm()) {
			//String keyOID = OIDLookup.getCipherOID(keyAlgorithm());
			encoder.writeElement(KEY_ALGORITHM_ELEMENT, keyAlgorithm());
		}		

		if (null != label()) {
			encoder.writeElement(LABEL_ELEMENT, label());
		}

		if (null != encryptedNonceKey()) {
			encoder.writeElement(ENCRYPTED_NONCE_KEY_ELEMENT, encryptedNonceKey());
		}

		encoder.writeElement(ENCRYPTED_KEY_ELEMENT, encryptedKey());

		encoder.writeEndElement();   		
	}

	@Override
	public String getElementLabel() { 
		return WRAPPED_KEY_ELEMENT;
	}

	@Override
	public boolean validate() {
		// Only mandatory component is the encrypted key.
		return ((null != _encryptedKey) && (_encryptedKey.length > 0));
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(_encryptedKey);
		result = prime * result + Arrays.hashCode(_encryptedNonceKey);
		result = prime * result
				+ ((_keyAlgorithm == null) ? 0 : _keyAlgorithm.hashCode());
		result = prime * result + ((_label == null) ? 0 : _label.hashCode());
		result = prime * result
				+ ((_wrapAlgorithm == null) ? 0 : _wrapAlgorithm.hashCode());
		result = prime * result + Arrays.hashCode(_wrappingKeyIdentifier);
		result = prime
				* result
				+ ((_wrappingKeyName == null) ? 0 : _wrappingKeyName.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		WrappedKey other = (WrappedKey) obj;
		if (!Arrays.equals(_encryptedKey, other._encryptedKey))
			return false;
		if (!Arrays.equals(_encryptedNonceKey, other._encryptedNonceKey))
			return false;
		if (_keyAlgorithm == null) {
			if (other._keyAlgorithm != null)
				return false;
		} else if (!_keyAlgorithm.equals(other._keyAlgorithm))
			return false;
		if (_label == null) {
			if (other._label != null)
				return false;
		} else if (!_label.equals(other._label))
			return false;
		if (_wrapAlgorithm == null) {
			if (other._wrapAlgorithm != null)
				return false;
		} else if (!_wrapAlgorithm.equals(other._wrapAlgorithm))
			return false;
		if (!Arrays
				.equals(_wrappingKeyIdentifier, other._wrappingKeyIdentifier))
			return false;
		if (_wrappingKeyName == null) {
			if (other._wrappingKeyName != null)
				return false;
		} else if (!_wrappingKeyName.equals(other._wrappingKeyName))
			return false;
		return true;
	}

	public static int getCipherType(String cipherAlgorithm) {
		if (cipherAlgorithm.equalsIgnoreCase("ECIES") || 
					cipherAlgorithm.equalsIgnoreCase("RSA") || cipherAlgorithm.equalsIgnoreCase("ElGamal")) {
			return Cipher.PRIVATE_KEY; // right now assume we don't wrap public keys
		}
		return Cipher.SECRET_KEY;
	}
	
	/**
	 * Convert a given wrapping key algorithm to the default wrap algorithm for
	 * using that key.
	 * @param keyAlgorithm
	 * @return
	 */
	public static String wrapAlgorithmForKey(String keyAlgorithm) {
		
		String wrapAlgorithm = _WrapAlgorithmMap.get(keyAlgorithm);
		if (null == wrapAlgorithm) {
			// punt
			return keyAlgorithm;
		}
		return wrapAlgorithm;
	}
	
	public static Key generateNonceKey() {
		KeyGenerator kg;
		try {
			kg = KeyGenerator.getInstance(NONCE_KEY_ALGORITHM);
			kg.init(NONCE_KEY_LENGTH);

			Key nk = kg.generateKey();
			return nk;
			
		} catch (NoSuchAlgorithmException e) {
			Log.warning("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
			Log.warningStackTrace(e);
			throw new RuntimeException("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
		}
	}
	
	/**
	 * Wrap using AES. Do not use standard Cipher interface, as we need to use an alternate algorithm
	 * (see AESWrapWithPadEngine) that is not currently included in any signed provider. Once it
	 * is, we will drop this special-case code.
	 * @param wrappingKey key to use to encrypt
	 * @param input encoded key to encrypt
	 * @param offset offset into encoded data buffer
	 * @param length length of data to encrypt.
	 * @return encrypted data.
	 * @throws IllegalBlockSizeException 
	 * @throws InvalidKeyException 
	 */
	protected static byte [] AESWrapWithPad(Key wrappingKey, Key keyToBeWrapped) throws InvalidKeyException, IllegalBlockSizeException {
		if (! wrappingKey.getAlgorithm().equals("AES")) {
			throw new IllegalArgumentException("AES wrap must wrap with with an AES key.");
		}
		AESWrapWithPad engine = new AESWrapWithPad();
		return engine.wrap(wrappingKey, keyToBeWrapped);
	}
	
	/**
	 * Unwrap using AES. Do not use standard Cipher interface, as we need to use an alternate algorithm
	 * (see AESWrapWithPadEngine) that is not currently included in any signed provider. Once it
	 * is, we will drop this special-case code.
	 * @param unwrappingKey key to use to decrypt
	 * @param wrappedKeyAlgorithm algorithm to use to decode key once decrypted.
	 * @param input encrypted key to decrypt and decode
	 * @param offset offset into encrypted data buffer
	 * @param length length of data to decrypt.
	 * @return decrypted, decoded key.
	 */
	protected static Key AESUnwrapWithPad(Key unwrappingKey, String wrappedKeyAlgorithm,
				byte [] input, int offset, int length) throws InvalidCipherTextException, InvalidKeyException {
		if (! unwrappingKey.getAlgorithm().equals("AES")) {
			throw new IllegalArgumentException("AES wrap must unwrap with with an AES key.");
		}
		AESWrapWithPad engine = new AESWrapWithPad();
		if ((offset != 0) || (length != input.length)) {
			byte [] tmpbuf = new byte[length];
			System.arraycopy(input, offset, tmpbuf, 0, length);
			input = tmpbuf;
		}
		return engine.unwrap(unwrappingKey, input, wrappedKeyAlgorithm);
	}
}
