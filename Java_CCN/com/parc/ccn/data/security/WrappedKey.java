package com.parc.ccn.data.security;

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
import javax.crypto.spec.SecretKeySpec;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.params.KeyParameter;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.crypto.jce.AESWrapWithPad;
import com.parc.ccn.security.crypto.jce.AESWrapWithPadEngine;

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
		public String contentNameElement() { 
			return WRAPPING_KEY_NAME_ELEMENT;
		}
	}

	public class WrappedKeyObject extends CCNEncodableObject<WrappedKey> {

		public WrappedKeyObject() throws ConfigurationException, IOException {
			super(WrappedKey.class);
		}
		
		public WrappedKeyObject(ContentName name, CCNLibrary library) throws XMLStreamException, IOException, ClassNotFoundException {
			super(WrappedKey.class, name, library);
		}

		public WrappedKeyObject(ContentName name) throws XMLStreamException, IOException, ClassNotFoundException, ConfigurationException {
			super(WrappedKey.class, name);
		}
		public WrappedKeyObject(ContentName name, WrappedKey wrappedKey, CCNLibrary library) {
			super(WrappedKey.class, name, wrappedKey, library);
		}

		public WrappedKeyObject(ContentName name, WrappedKey wrappedKey) throws ConfigurationException, IOException {
			super(WrappedKey.class, name, wrappedKey);
		}
		
		public WrappedKey wrappedKey() { return data(); }
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
	 * can be added if necessary using {@link #setWrappingKeyIdentifier(SecretKeySpec)}.
	 * Default wrap algorithm if wrapping key is AES is AESWrap (RFC3394, NIST std);
	 * this is available in Java 1.6, or BouncyCastle.
	 * 
	 * @param keyLabel optional label for the wrapped key
	 * @param optional algorithm to decode the wrapped key (e.g. AES-CBC); otherwise
	 *    we use  key.getAlgorithm()
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws IllegalBlockSizeException 
	 */
	public static WrappedKey wrapKey(Key keyToBeWrapped,
									 String keyAlgorithm, 
									 String keyLabel, 
									 Key wrappingKey) 
	throws NoSuchAlgorithmException, NoSuchPaddingException, 
	InvalidKeyException, IllegalBlockSizeException {

		String wrappingAlgorithm = wrapAlgorithmForKey(wrappingKey.getAlgorithm());
		byte [] wrappedNonceKey = null;
		byte [] wrappedKey = null;

		if (wrappingAlgorithm.equalsIgnoreCase("AESWrapWithPad")) {
			byte [] encodedKeyToBeWrapped = keyToBeWrapped.getEncoded();
			wrappedKey = AESWrapWithPad(wrappingKey, encodedKeyToBeWrapped, 0, encodedKeyToBeWrapped.length);
		} else {

			Cipher wrapCipher = Cipher.getInstance(wrappingAlgorithm);
			wrapCipher.init(Cipher.WRAP_MODE, wrappingKey);
			// If we are dealing with a short-block cipher, like RSA, we need to
			// interpose a nonce key.

			int wrappedKeyType = getCipherType(keyToBeWrapped.getAlgorithm());
			// If we're wrapping a private key in a public key, need to handle multi-block
			// keys. Probably don't want to do that with ECB mode. ECIES already acts
			// as a hybrid cipher, so we don't need to do this for that.
			if (((Cipher.PRIVATE_KEY == wrappedKeyType) || (Cipher.PUBLIC_KEY == wrappedKeyType)) && 
					(wrappingKey instanceof PublicKey) && (!wrappingKey.getAlgorithm().equals("ECIES"))) {

				Key nonceKey = generateNonceKey();
				//try {
				// We know the nonce key is an AES key. Use standard wrap algorithm.
				// DKS -- fix when have provider.
				//Cipher nonceCipher = Cipher.getInstance(wrapAlgorithmForKey(nonceKey.getAlgorithm()));
				//nonceCipher.init(Cipher.WRAP_MODE, nonceKey);
				//wrappedKey = nonceCipher.wrap(keyToBeWrapped);
				byte [] encodedKeyToBeWrapped = keyToBeWrapped.getEncoded();
				wrappedKey = AESWrapWithPad(nonceKey, encodedKeyToBeWrapped, 0, encodedKeyToBeWrapped.length);
				//} catch (NoSuchAlgorithmException nsex) {
				//	Library.logger().warning("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
				//	Library.warningStackTrace(nsex);
				//	throw new RuntimeException("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);	    		
				//}
				wrappedNonceKey = wrapCipher.wrap(nonceKey);

			} else {
				wrappedKey = wrapCipher.wrap(keyToBeWrapped);
			}
		}
		// Default wrapping algorithm is being used, don't need to include it.
	    return new WrappedKey(null, null, 
	    					 ((null == keyAlgorithm) ? keyToBeWrapped.getAlgorithm() : keyAlgorithm), 
	    					 keyLabel, wrappedNonceKey, wrappedKey);
	}
	
	/**
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
	 * @param wrappingKeyIdentifier
	 * @param wrapAlgorithm
	 * @param keyAlgorithm
	 * @param label
	 * @param encryptedKey
	 */
	public WrappedKey(byte [] wrappingKeyIdentifier, String wrapAlgorithm, String keyAlgorithm,
			  String label, byte [] encryptedKey) {
		this(wrappingKeyIdentifier, wrapAlgorithm, keyAlgorithm, label, null, encryptedKey);
	}

	public WrappedKey(byte [] wrappingKeyIdentifier, String wrapAlgorithm, String keyAlgorithm,
					  String label, byte [] encryptedNonceKey, byte [] encryptedKey) {
		_wrappingKeyIdentifier = wrappingKeyIdentifier;
		_wrapAlgorithm = wrapAlgorithm;
		_keyAlgorithm = keyAlgorithm;
		_label = label;
		_encryptedNonceKey = encryptedNonceKey;
		_encryptedKey = encryptedKey;
	}
	
	public WrappedKey(byte [] wrappingKeyIdentifier, byte [] encryptedKey) {
		this(wrappingKeyIdentifier, null, null, null, null, encryptedKey);
	}
	
	public WrappedKey(byte [] wrappingKeyIdentifier, String label, byte [] encryptedKey) {
		this(wrappingKeyIdentifier, null, null, label, null, encryptedKey);
	}

	/**
	 * Empty constructor for decoding.
	 */
	public WrappedKey() {
	}
	
	public Key unwrapKey(Key unwrapKey) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidCipherTextException {

		if (null == keyAlgorithm()) {
			throw new NoSuchAlgorithmException("No algorithm specified for key to be unwrapped!");
		}
		
		return unwrapKey(unwrapKey, keyAlgorithm());
	}
	
	public Key unwrapKey(Key unwrapKey, String wrappedKeyAlgorithm) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidCipherTextException {

		Key unwrappedKey = null;
		Library.logger().info("wrap algorithm: " + wrapAlgorithm() + " wa for key " +
				wrapAlgorithmForKey(unwrapKey.getAlgorithm()));
		if (((null != wrapAlgorithm()) && (wrapAlgorithm().equalsIgnoreCase("AESWrapWithPad"))) || 
							wrapAlgorithmForKey(unwrapKey.getAlgorithm()).equalsIgnoreCase("AESWrapWithPad")) {
			unwrappedKey = AESUnwrapWithPad(unwrapKey, wrappedKeyAlgorithm, encryptedKey(), 0, encryptedKey().length);
		} else {
			Cipher unwrapCipher = null;
			if (null != wrapAlgorithm()) {
				unwrapCipher = Cipher.getInstance(wrapAlgorithm());
			} else {
				unwrapCipher = Cipher.getInstance(wrapAlgorithmForKey(unwrapKey.getAlgorithm()));
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
					Library.logger().warning("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
					Library.warningStackTrace(nsex);
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
	
	public void setWrappingKeyIdentifier(PublicKey wrappingKey) {
		setWrappingKeyIdentifier(PublisherID.generatePublicKeyDigest(wrappingKey));
	}
	
	public void setWrappingKeyIdentifier(SecretKeySpec wrappingKey) {
		setWrappingKeyIdentifier(CCNDigestHelper.digest(wrappingKey.getEncoded()));
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
		decoder.readStartElement(WRAPPED_KEY_ELEMENT);

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
		
		encoder.writeStartElement(WRAPPED_KEY_ELEMENT);
		
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
			Library.logger().warning("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
			Library.warningStackTrace(e);
			throw new RuntimeException("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
		}
	}
	
	/**
	 * Until we can sign a provider, we need to reach directly in to wrap public keys in AES keys.
	 * @param input
	 * @param offset
	 * @param length
	 * @return
	 */
	protected static byte [] AESWrapWithPad(Key wrappingKey, byte[] input, int offset, int length) {
		if (wrappingKey.getAlgorithm() != "AES") {
			throw new IllegalArgumentException("AES wrap must wrap with with an AES key.");
		}
		AESWrapWithPadEngine engine = new AESWrapWithPadEngine();
		engine.init(true, new KeyParameter(wrappingKey.getEncoded()));
		return engine.wrap(input, offset, length);
	}
	
	protected static Key AESUnwrapWithPad(Key unwrappingKey, String wrappedKeyAlgorithm,
				byte [] input, int offset, int length) throws InvalidCipherTextException, InvalidKeyException {
		if (unwrappingKey.getAlgorithm() != "AES") {
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
