package com.parc.ccn.data.security;

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

import com.parc.ccn.Library;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.security.crypto.CCNDigestHelper;

public class WrappedKey extends GenericXMLEncodable implements XMLEncodable {

	protected static final String WRAPPED_KEY_ELEMENT = "WrappedKey";
	protected static final String WRAPPING_KEY_IDENTIFIER_ELEMENT = "WrappingKeyIdentifier";
	protected static final String WRAP_ALGORITHM_ELEMENT = "WrapAlgorithm";
	protected static final String KEY_ALGORITHM_ELEMENT = "KeyAlgorithm";
	protected static final String LABEL_ELEMENT = "Label";
	protected static final String ENCRYPTED_NONCE_KEY_ELEMENT = "EncryptedNonceKey";
	protected static final String ENCRYPTED_KEY_ELEMENT = "EncryptedKey";
	
	protected static final String NONCE_KEY_ALGORITHM = "AES";
	protected static final int NONCE_KEY_LENGTH = 128;
	
	private static final Map<String,String> _WrapAlgorithmMap = new HashMap<String,String>();

	byte [] _wrappingKeyIdentifier;
	String _wrapAlgorithm;
	String _keyAlgorithm;
	String _label;
	byte [] _encryptedNonceKey;
	byte [] _encryptedKey;

	
	static {
		// In Java 1.5, many of these require BouncyCastle. They are typically built in in 1.6.
		_WrapAlgorithmMap.put("AES", "AESWrap");
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
	 * @param optional algorithm to decode/se the wrapped key (e.g. AES-CBC)
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
		Cipher wrapCipher = Cipher.getInstance(wrappingAlgorithm);

	    wrapCipher.init(Cipher.WRAP_MODE, wrappingKey);
	    
	    // If we are dealing with a short-block cipher, like RSA, we need to
	    // interpose a nonce key.
	    byte [] wrappedNonceKey = null;
	    byte [] wrappedKey = null;
	    
	    int wrappedKeyType = getCipherType(keyToBeWrapped.getAlgorithm());
	    // If we're wrapping a private key in a public key, need to handle multi-block
	    // keys. Probably don't want to do that with ECB mode. ECIES already acts
	    // as a hybrid cipher, so we don't need to do this for that.
	    if (((Cipher.PRIVATE_KEY == wrappedKeyType) || (Cipher.PUBLIC_KEY == wrappedKeyType)) && 
	    	(wrappingKey instanceof PublicKey) && (!wrappingKey.getAlgorithm().equals("ECIES"))) {
	    	
	    	Key nonceKey = generateNonceKey();
	    	try {
	    		Cipher nonceCipher = Cipher.getInstance(wrapAlgorithmForKey(nonceKey.getAlgorithm()));
	    		nonceCipher.init(Cipher.WRAP_MODE, nonceKey);
	    		
	    		// DKS RFC 3394 key wrapping does not handle padding, and requires input to be
	    		// a multiple of 8 bytes. A workaround to this problem is currently in IETF
	    		// process as draft-housley-aes-key-wrap-with-pad-02.txt; though it is not
	    		// yet supported by libraries. But can't 0-pad without dropping key wrap API in
	    		// general. So use it.
	    		wrappedKey = nonceCipher.wrap(keyToBeWrapped);
	    	} catch (NoSuchAlgorithmException nsex) {
				Library.logger().warning("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
				Library.warningStackTrace(nsex);
				throw new RuntimeException("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);	    		
	    	}
	    	wrappedNonceKey = wrapCipher.wrap(nonceKey);
	    	
	    } else {
	    	wrappedKey = wrapCipher.wrap(keyToBeWrapped);
	    }
	    
	    return new WrappedKey(null, keyAlgorithm, keyToBeWrapped.getAlgorithm(), 
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
	
	public Key unwrapKey(Key unwrapKey) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException {

		if (null == keyAlgorithm()) {
			throw new NoSuchAlgorithmException("No algorithm specified for key to be unwrapped!");
		}
		
		return unwrapKey(unwrapKey, keyAlgorithm());
	}
	
	public Key unwrapKey(Key unwrapKey, String wrappedKeyAlgorithm) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException {

		Cipher unwrapCipher = null;
		if (null != wrapAlgorithm()) {
			unwrapCipher = Cipher.getInstance(wrapAlgorithm());
		} else {
			unwrapCipher = Cipher.getInstance(wrapAlgorithmForKey(unwrapKey.getAlgorithm()));
		}

	    unwrapCipher.init(Cipher.UNWRAP_MODE, unwrapKey);
	    int keyType = getCipherType(wrappedKeyAlgorithm);
	  
	    Key unwrappedKey = null;
	    if (null != encryptedNonceKey()) {
	    	try {
	    		Key nonceKey = unwrapCipher.unwrap(encryptedNonceKey(), NONCE_KEY_ALGORITHM, Cipher.SECRET_KEY);

	    		Cipher nonceKeyCipher = Cipher.getInstance(wrapAlgorithmForKey(NONCE_KEY_ALGORITHM));
	    		nonceKeyCipher.init(Cipher.UNWRAP_MODE, nonceKey);
	    		unwrappedKey = nonceKeyCipher.unwrap(encryptedKey(), wrappedKeyAlgorithm, keyType);
	    		
	    	} catch(NoSuchAlgorithmException nsex) {
	    		Library.logger().warning("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);
	    		Library.warningStackTrace(nsex);
	    		throw new RuntimeException("Configuration error: Unknown default nonce key algorithm: " + NONCE_KEY_ALGORITHM);	    		
	    	}
	    } else {
	    	unwrappedKey = unwrapCipher.unwrap(encryptedKey(), wrappedKeyAlgorithm, keyType);
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
		result = prime * result
				+ ((_keyAlgorithm == null) ? 0 : _keyAlgorithm.hashCode());
		result = prime * result + ((_label == null) ? 0 : _label.hashCode());
		result = prime * result
				+ ((_wrapAlgorithm == null) ? 0 : _wrapAlgorithm.hashCode());
		result = prime * result + Arrays.hashCode(_wrappingKeyIdentifier);
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
}
