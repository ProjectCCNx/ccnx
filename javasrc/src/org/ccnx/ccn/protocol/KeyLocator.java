package org.ccnx.ccn.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.security.crypto.util.OIDLookup;
import org.ccnx.ccn.impl.support.Log;


public class KeyLocator extends GenericXMLEncodable implements XMLEncodable {
	/**
	 * KeyLocator(name) must allow for a complete name -- i.e.
	 * a name and authentication information.
	 * @author smetters
	 *
	 */
    public enum KeyLocatorType { NAME, KEY, CERTIFICATE }

    protected static final HashMap<KeyLocatorType, String> TypeNames = new HashMap<KeyLocatorType, String>();
    protected static final HashMap<String, KeyLocatorType> NameTypes = new HashMap<String, KeyLocatorType>();
    
    static {
        TypeNames.put(KeyLocatorType.NAME, "NAME");
        TypeNames.put(KeyLocatorType.KEY, "KEY");
        TypeNames.put(KeyLocatorType.CERTIFICATE, "CERTIFICATE");
        NameTypes.put("NAME", KeyLocatorType.NAME);
        NameTypes.put("KEY", KeyLocatorType.KEY);
        NameTypes.put("CERTIFICATE", KeyLocatorType.CERTIFICATE);
    }

    protected static final String KEY_LOCATOR_ELEMENT = "KeyLocator";
    protected static final String KEY_LOCATOR_TYPE_ELEMENT = "Type";
    protected static final String PUBLISHER_KEY_ELEMENT = "Key";
    protected static final String PUBLISHER_CERTIFICATE_ELEMENT = "Certificate";

    // Fake out a union.
    protected KeyName _keyName;       // null if wrong type
    protected PublicKey _key;
    protected X509Certificate _certificate;
    
    public KeyLocator(ContentName name) {
    	this (name, null);
    }
    
    public KeyLocator(ContentName name, PublisherID publisher) {
    	this(new KeyName(name, publisher));
    }
    
    public KeyLocator(KeyName keyName) {
    	_keyName = keyName;
    }

    public KeyLocator(PublicKey key) {
    	_key = key;
    }
    
    public KeyLocator(X509Certificate certificate) {
    	_certificate = certificate;
    }
    
    protected KeyLocator(KeyName name, PublicKey key, X509Certificate certificate) {
    	_keyName = name;
    	_key = key;
    	_certificate = certificate;
    }
    
    public KeyLocator clone() {
    	return new KeyLocator(name(),
    						  key(),
    						  certificate());
    }
    
    public KeyLocator() {} // for use by decoders
    
	public PublicKey key() { return _key; }
    public KeyName name() { return _keyName; }
    public X509Certificate certificate() { return _certificate; }
    public KeyLocatorType type() { 
    	if (null != certificate())
    		return KeyLocatorType.CERTIFICATE;
    	if (null != key())
    		return KeyLocatorType.KEY;
    	return KeyLocatorType.NAME; 
    }

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_key == null) ? 0 : _key.hashCode());
		result = PRIME * result + ((_keyName == null) ? 0 : _keyName.hashCode());
		result = PRIME * result + ((_certificate == null) ? 0 : _certificate.hashCode());
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
		final KeyLocator other = (KeyLocator) obj;
		if (_key == null) {
			if (other._key != null)
				return false;
		} else if (!_key.equals(other._key))
			return false;
		if (_keyName == null) {
			if (other.name() != null)
				return false;
		} else if (!_keyName.equals(other.name()))
			return false;
		return true;
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(KEY_LOCATOR_ELEMENT);

		if (decoder.peekStartElement(PUBLISHER_KEY_ELEMENT)) {
			try {
				byte [] encodedKey = decoder.readBinaryElement(PUBLISHER_KEY_ELEMENT);
				// This is a DER-encoded SubjectPublicKeyInfo.
				_key = decodeKey(encodedKey);
			} catch (IOException e) {
				Log.warning("Cannot parse stored key: error: " + e.getMessage());
				throw new XMLStreamException("Cannot parse key: ", e);
			} catch (InvalidKeySpecException e) {
				Log.warning("Cannot turn stored key " + " into key of appropriate type.");
				throw new XMLStreamException("Cannot turn stored key " + " into key of appropriate type.");
			}
			if (null == _key) {
				throw new XMLStreamException("Cannot parse key: ");
			}
		} else if (decoder.peekStartElement(PUBLISHER_CERTIFICATE_ELEMENT)) {
			try {
				byte [] encodedCert = decoder.readBinaryElement(PUBLISHER_CERTIFICATE_ELEMENT);
				CertificateFactory factory = CertificateFactory.getInstance("X.509");
				_certificate = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(encodedCert));
			} catch (CertificateException e) {
				throw new XMLStreamException("Cannot decode certificate: " + e.getMessage(), e);
			}
			if (null == _certificate) {
				throw new XMLStreamException("Cannot parse certificate! ");
			}
		} else {
			_keyName = new KeyName();
			_keyName.decode(decoder);
		}
		decoder.readEndElement();
	}
	
	public byte [] getEncoded() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			encode(baos);
		} catch (XMLStreamException e) {
			Log.log(Level.WARNING, "This should not happen: cannot encode KeyLocator to byte array.");
			Log.warningStackTrace(e);
			// DKS currently returning invalid byte array...
		}
		return baos.toByteArray();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(KEY_LOCATOR_ELEMENT);
		if (type() == KeyLocatorType.KEY) {
			encoder.writeElement(PUBLISHER_KEY_ELEMENT, key().getEncoded());
		} else if (type() == KeyLocatorType.CERTIFICATE) {
			try {
				encoder.writeElement(PUBLISHER_CERTIFICATE_ELEMENT, certificate().getEncoded());
			} catch (CertificateEncodingException e) {
				Log.warning("CertificateEncodingException attempting to write key locator: " + e.getMessage());
				throw new XMLStreamException("CertificateEncodingException attempting to write key locator: " + e.getMessage(), e);
			}
		} else if (type() == KeyLocatorType.NAME) {
			name().encode(encoder);
		}
		encoder.writeEndElement();   		
	}
	
	public static String typeToName(KeyLocatorType type) {
		return TypeNames.get(type);
	}

	public static KeyLocatorType nameToType(String name) {
		return NameTypes.get(name);
	}
	
	protected static PublicKey decodeKey(byte [] encodedKey) throws InvalidKeySpecException, IOException {
		
		X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encodedKey);
		// Have to know what kind of key factory to make. Should be able
		// to pull it out of the encoding, but Java is kind of dumb.
		// We know it's a SubjectPublicKeyInfo, so get the algorithmID.
		ASN1InputStream as = new ASN1InputStream(encodedKey);
		DERObject object = as.readObject();
		SubjectPublicKeyInfo keyInfo = new SubjectPublicKeyInfo((ASN1Sequence)object);
		String keyType = OIDLookup.getCipherName(keyInfo.getAlgorithmId().getObjectId().toString());
		if (null == keyType) {
			Log.info("Cannot find key type corresponding to OID: " + keyInfo.getAlgorithmId().getObjectId().toString());
		}
		KeyFactory keyFactory = null;
		PublicKey key = null;
		try {
			keyFactory = KeyFactory.getInstance(keyType);
			key = keyFactory.generatePublic(keySpec);
		} catch (NoSuchAlgorithmException e) {
			Log.warning("Unknown key type " + keyType + " in stored key.");
			throw new InvalidKeySpecException("Unknown key type " + keyType + " in stored key.");
		}
		return key;
	}
	
	public boolean validate() {
		return ((null != name() || (null != key()) || (null != certificate())));
	}

}
