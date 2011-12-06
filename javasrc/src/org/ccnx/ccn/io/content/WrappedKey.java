/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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
import java.util.logging.Level;

import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.SignatureLocks;
import org.ccnx.ccn.impl.security.crypto.jce.AESWrapWithPad;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * A representation of wrapped (encrypted) keys for strorage in CCN. These are used
 * to transfer symmetric and private keys between users, store them for backup, do
 * key distribution for access control, and so on. They use standard key wrapping
 * algorithms, and try to be fairly general. You can use them to wrap almost any
 * type of key in any other type of key (as long as the latter is capable of
 * encryption), though use of this class with some key types or key lengths
 * may require installation of the Java unlimited strength cryptography policy
 * files to succeed. 
 * 
 * This class automatically handles generation of interstitial nonce keys for wrapping keys
 * of incompatible lengths -- if you want to wrap a private key in another private key,
 * it will generate a nonce key, wrap the first key in that nonce key, and that nonce key
 * in the second private key. 
 * 
 * For now, we have a very loose definition of default -- the default wrap algorithm
 * depends on the type of key being used to wrap; similarly the default key algorithm
 * depends on the type of the key being wrapped. We assume that the wrapper and unwrapper
 * usually know the type of the wrapping key, and can derive the wrapAlgorithm. The
 * keyAlgorithm is more often necessary to determine how to decode the key once unwrapped
 * so it is more frequently present. Both are optional.
 * 
 * If the caller does not specify a wrapping algorithm, a standards-based default is
 * selected, see documentation for details.
 * 
 * If the caller specifies values they will be encoded on the wire and decoded on 
 * the other end; defaults will not currently be enforced automatically. This means
 * equals behavior should be watched closely. 
 */
public class WrappedKey extends GenericXMLEncodable implements XMLEncodable {

	protected static final String NONCE_KEY_ALGORITHM = "AES";
	protected static final int NONCE_KEY_LENGTH = 128;
	
	/**
	 * A CCNNetworkObject wrapper around WrappedKey, used for easily saving and retrieving
	 * versioned WrappedKeys to CCN. A typical pattern for using network objects to save
	 * objects that happen to be encodable or serializable is to incorporate such a static
	 * member wrapper class subclassing CCNEncodableObject, CCNSerializableObject, or
	 * CCNNetworkObject itself inside the main class definition.
	 */
	public static class WrappedKeyObject extends CCNEncodableObject<WrappedKey> {

		public WrappedKeyObject(ContentName name, WrappedKey data, SaveType saveType, CCNHandle handle) throws IOException {
			super(WrappedKey.class, true, name, data, saveType, handle);
		}

		public WrappedKeyObject(ContentName name, WrappedKey data, SaveType saveType,
				PublisherPublicKeyDigest publisher, 
				KeyLocator locator, CCNHandle handle) throws IOException {
			super(WrappedKey.class, true, name, data, saveType, publisher, locator, handle);
		}

		public WrappedKeyObject(ContentName name, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(WrappedKey.class, true, name, (PublisherPublicKeyDigest)null, handle);
		}

		public WrappedKeyObject(ContentName name, PublisherPublicKeyDigest publisher,
								CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(WrappedKey.class, true, name, publisher, handle);
		}
		
		public WrappedKeyObject(ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(WrappedKey.class, true, firstBlock, handle);
		}
		
		public WrappedKey wrappedKey() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
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
	
	/*
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
		
		if (Log.isLoggable(Level.FINER)) {
			Log.finer("wrapKey: wrapping key with id {0} under key with id {1} using label {2}",
					DataUtils.printHexBytes(wrappingKeyIdentifier(keyToBeWrapped)),
					DataUtils.printHexBytes(wrappingKeyIdentifier(wrappingKey)),
					keyLabel);
		}

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
				if (Log.isLoggable(Level.INFO)) {
					Log.info("Wrap cipher {0}, provider {1}.", wrapCipher.getAlgorithm(), wrapCipher.getProvider());
				}
			} catch (NoSuchAlgorithmException e) {
				Log.warning("Unexpected NoSuchAlgorithmException attempting to instantiate wrapping algorithm: "+ wrappingAlgorithm);
				throw new InvalidKeyException("Unexpected NoSuchAlgorithmException attempting to instantiate wrapping algorithm: "+ wrappingAlgorithm);
			} catch (NoSuchPaddingException e) {
				Log.warning("Unexpected NoSuchPaddingException attempting to instantiate wrapping algorithm: "+ wrappingAlgorithm);
				throw new InvalidKeyException("Unexpected NoSuchPaddingException attempting to instantiate wrapping algorithm: "+ wrappingAlgorithm);
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
	    WrappedKey wk = new WrappedKey(null, null, 
	    					 ((null == keyAlgorithm) ? keyToBeWrapped.getAlgorithm() : keyAlgorithm), 
	    					 keyLabel, wrappedNonceKey, wrappedKey);
	    if (Log.isLoggable(Level.FINER)) {
			Log.finer("wrapKey: got {0} by wrapping {1} with {2}", wk, 
						DataUtils.printHexBytes(keyToBeWrapped.getEncoded()), DataUtils.printHexBytes(wrappingKey.getEncoded()));
	    }
		return wk;
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
	 * @throws NoSuchAlgorithmException 
	 * @throws  
	 * @see unwrapKey(Key, String)
	 **/
	public Key unwrapKey(Key unwrapKey) throws InvalidKeyException, NoSuchAlgorithmException {

		if (null == keyAlgorithm()) {
			throw new NoSuchAlgorithmException("Null algorithm specified for key to be unwrapped!");
		}
		byte [] wki = wrappingKeyIdentifier(unwrapKey);
		if (Log.isLoggable(Level.INFO)) {
			Log.info("WrappedKey: unwrapping key wrapped with wrapping key ID {0}, incoming wrapping key digest {1} match? {2}",
						DataUtils.printHexBytes(wrappingKeyIdentifier()), 
						DataUtils.printHexBytes(wki),
						Arrays.equals(wki, wrappingKeyIdentifier()));
		}
		return unwrapKey(unwrapKey, keyAlgorithm());
	}

	/**
	 * Unwraps an encrypted key, and decodes it into a Key of an algorithm type
	 * specified by wrappedKeyAlgorithm.
	 * @param unwrapKey the key to use to decrypt this wrapped key.
	 * @param wrappedKeyAlgorithm the algorithm of the wrapped key, used in decoding it.
	 * @return the decrypted key if successful.
	 * @throws InvalidKeyException if we encounter an error using the unwrapKey to decrypt.
	 * @throws NoSuchAlgorithmException if we do not recognize the wrappedKeyAlgorithm.
	 **/
	public Key unwrapKey(Key unwrapKey, String wrappedKeyAlgorithm) 
			throws InvalidKeyException, NoSuchAlgorithmException {

		Key unwrappedKey;
		if (Log.isLoggable(Level.INFO)) {
			Log.info("wrap algorithm: " + wrapAlgorithm() + " wa for key " +
					wrapAlgorithmForKey(unwrapKey.getAlgorithm()));
			Log.info("unwrapKey: unwrapping {0} with {1}", this, DataUtils.printHexBytes(wrappingKeyIdentifier(unwrapKey)));
		}
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
					Key nonceKey = null;
					
					// Cope with GC memory corruption
					SignatureLocks.unwrapLock();
					try {
						nonceKey = unwrapCipher.unwrap(encryptedNonceKey(), NONCE_KEY_ALGORITHM, Cipher.SECRET_KEY);
					} finally {
						SignatureLocks.unwrapUnock();
					}
					
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
				// Cope with GC memory corruption
				SignatureLocks.unwrapLock();
				try {
					unwrappedKey = unwrapCipher.unwrap(encryptedKey(), wrappedKeyAlgorithm, keyType);
				} finally {
					SignatureLocks.unwrapUnock();
				}
			}
		}
	    return unwrappedKey;
	}

	/**
	 * 
	 * @return the wrappingKeyIdentfier for this object
	 */
	public byte [] wrappingKeyIdentifier() { return _wrappingKeyIdentifier; }
	
	/**
	 * Sets the wrappingKeyIdentifier
	 * @param wrappingKeyIdentifier new identifier
	 */
	public void setWrappingKeyIdentifier(byte [] wrappingKeyIdentifier) {
		_wrappingKeyIdentifier = wrappingKeyIdentifier;
	}
	
	/**
	 * Sets the wrappingKeyIdentifier
	 * @param wrappingKey key from which to generate the new identifier
	 */
	public void setWrappingKeyIdentifier(Key wrappingKey) {
		setWrappingKeyIdentifier(wrappingKeyIdentifier(wrappingKey));
	}
	
	/**
	 * Calculate the wrappingKeyIdentifier corresponding to this key
	 * @param wrappingKey the key
	 * @return the identifier
	 */
	public static byte [] wrappingKeyIdentifier(Key wrappingKey) {
		return CCNDigestHelper.digest(wrappingKey.getEncoded());
	}
	
	/**
	 * @return the wrappingKeyName if specified
	 */
	public ContentName wrappingKeyName() { return _wrappingKeyName; }
	
	/**
	 * Set the wrappingKeyName
	 * @param keyName the new name
	 */
	public void setWrappingKeyName(ContentName keyName) { _wrappingKeyName = new WrappingKeyName(keyName); }
	
	/**
	 * Returns the wrapping algorithm identifier, if specified
	 * @return the wrap algorithm
	 */
	public String wrapAlgorithm() { return _wrapAlgorithm; }
	
	/**
	 * Returns the key algorithm identifier, if specified
	 * @return the key algorithm
	 */
	public String keyAlgorithm() { return _keyAlgorithm; }
	
	/**
	 * Returns the label if we have one
	 * @return the label
	 */
	public String label() { return _label; }

	/**
	 * Returns the encrypted nonce key if we have one.
	 * @return the encryptedNonceKey, if one is present
	 */
	public byte [] encryptedNonceKey() { return _encryptedNonceKey; }
	
	/**
	 * Get the encrypted key
	 * @return the encrypted key
	 */
	public byte [] encryptedKey() { return _encryptedKey; }

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());

		if (decoder.peekStartElement(CCNProtocolDTags.WrappingKeyIdentifier)) {
			_wrappingKeyIdentifier = decoder.readBinaryElement(CCNProtocolDTags.WrappingKeyIdentifier); 
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.WrappingKeyName)) {
			_wrappingKeyName = new WrappingKeyName();
			_wrappingKeyName.decode(decoder);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.WrapAlgorithm)) {
			_wrapAlgorithm = decoder.readUTF8Element(CCNProtocolDTags.WrapAlgorithm); 
		}

		if (decoder.peekStartElement(CCNProtocolDTags.KeyAlgorithm)) {
			_keyAlgorithm = decoder.readUTF8Element(CCNProtocolDTags.KeyAlgorithm); 
		}

		if (decoder.peekStartElement(CCNProtocolDTags.Label)) {
			_label = decoder.readUTF8Element(CCNProtocolDTags.Label); 
		}

		if (decoder.peekStartElement(CCNProtocolDTags.EncryptedNonceKey)) {
			_encryptedNonceKey = decoder.readBinaryElement(CCNProtocolDTags.EncryptedNonceKey); 
		}
		
		_encryptedKey = decoder.readBinaryElement(CCNProtocolDTags.EncryptedKey);
		
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		
		encoder.writeStartElement(getElementLabel());
		
		if (null != wrappingKeyIdentifier()) {
			// needs to handle null WKI
			encoder.writeElement(CCNProtocolDTags.WrappingKeyIdentifier, wrappingKeyIdentifier());
		}
		
		if (null != wrappingKeyName()) {
			wrappingKeyName().encode(encoder);
		}

		if (null != wrapAlgorithm()) {
			//String wrapOID = OIDLookup.getCipherOID(wrapAlgorithm());
			encoder.writeElement(CCNProtocolDTags.WrapAlgorithm, wrapAlgorithm());
		}
		
		if (null != keyAlgorithm()) {
			//String keyOID = OIDLookup.getCipherOID(keyAlgorithm());
			encoder.writeElement(CCNProtocolDTags.KeyAlgorithm, keyAlgorithm());
		}		

		if (null != label()) {
			encoder.writeElement(CCNProtocolDTags.Label, label());
		}

		if (null != encryptedNonceKey()) {
			encoder.writeElement(CCNProtocolDTags.EncryptedNonceKey, encryptedNonceKey());
		}

		encoder.writeElement(CCNProtocolDTags.EncryptedKey, encryptedKey());

		encoder.writeEndElement();   		
	}

	@Override
	public long getElementLabel() { 
		return CCNProtocolDTags.WrappedKey;
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
	
	@Override
	public String toString() {
		return "WrappedKey: wrapping key ID: " + DataUtils.printHexBytes(_wrappingKeyIdentifier) + 
				" wrapping key name: " + _wrappingKeyName + " wrap algorithm: " + _wrapAlgorithm + 
				" key algorithm: " + _keyAlgorithm + " label: " + _label + " has nonce key? " + (null != _encryptedNonceKey);
					
	}

	public static int getCipherType(String cipherAlgorithm) {
		if (cipherAlgorithm.equalsIgnoreCase("ECIES") || cipherAlgorithm.equalsIgnoreCase("EC") ||
				cipherAlgorithm.equalsIgnoreCase("RSA") || cipherAlgorithm.equalsIgnoreCase("ElGamal") ||
				cipherAlgorithm.equalsIgnoreCase("DH") || cipherAlgorithm.equalsIgnoreCase("DSA")) {
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
	 * @param keyToBeWrapped key to encrypt
	 * @return encrypted key.
	 * @throws IllegalBlockSizeException 
	 * @throws InvalidKeyException 
	 */
	protected static byte [] AESWrapWithPad(Key wrappingKey, Key keyToBeWrapped) 
					throws InvalidKeyException, IllegalBlockSizeException {
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
				byte [] input, int offset, int length) throws InvalidKeyException {
		if (! unwrappingKey.getAlgorithm().equals("AES")) {
			throw new IllegalArgumentException("AES wrap must unwrap with with an AES key.");
		}
		AESWrapWithPad engine = new AESWrapWithPad();
		if ((offset != 0) || (length != input.length)) {
			byte [] tmpbuf = new byte[length];
			System.arraycopy(input, offset, tmpbuf, 0, length);
			input = tmpbuf;
		}
		try {
			return engine.unwrap(unwrappingKey, input, wrappedKeyAlgorithm);
		} catch (NoSuchAlgorithmException e) {
			// engine.unwrap only throws NoSuchAlgorithmException in older versions of BouncyCastle.
			// Newer versions do exactly what we're doing here; add it here manually to allow 
			// compatibility with multiple BC versions.
            throw new InvalidKeyException("Unknown key type " + e.getMessage());
		}
	}
}
