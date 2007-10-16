package com.parc.ccn.data.security;

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

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

import com.parc.ccn.Library;
import com.parc.ccn.crypto.certificates.OIDLookup;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

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

    protected KeyLocatorType _type;
    // Fake out a union.
    protected CompleteName _name;       // null if wrong type
    protected PublicKey _key;
    protected X509Certificate _certificate;
    
    public KeyLocator(CompleteName name) {
    	_name = name; // DKS for the moment, we're using, not cloning...
    	_type = KeyLocatorType.NAME;
    }
    
    public KeyLocator(ContentName name, ContentAuthenticator authenticator) {
    	this(new CompleteName(name, authenticator));
    }
    
    public KeyLocator(ContentName name) {
    	this(name, null);
    }
    
    public KeyLocator(PublicKey key) {
    	_key = key;
    	_type = KeyLocatorType.KEY;
    }
    
    public KeyLocator(X509Certificate certificate) {
    	_certificate = certificate;
    	_type = KeyLocatorType.CERTIFICATE;
    }
    
    public KeyLocator(byte [] encoded) throws XMLStreamException {
    	super(encoded);
    }
    
    public KeyLocator() {} // for use by decoders
    
	public PublicKey key() { return _key; }
    public CompleteName name() { return _name; }
    public X509Certificate certificate() { return _certificate; }
    public KeyLocatorType type() { return _type; }

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_key == null) ? 0 : _key.hashCode());
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
		result = PRIME * result + ((_type == null) ? 0 : _type.hashCode());
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
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
			return false;
		if (_type == null) {
			if (other._type != null)
				return false;
		} else if (!_type.equals(other._type))
			return false;
		return true;
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, KEY_LOCATOR_ELEMENT);

		String strType = XMLHelper.readElementText(reader, KEY_LOCATOR_TYPE_ELEMENT);
		_type = nameToType(strType);
		if (null == _type) {
			throw new XMLStreamException("Cannot parse key type: " + strType);
		}
		
		if (type() == KeyLocatorType.KEY) {
			String strKey = XMLHelper.readElementText(reader, PUBLISHER_KEY_ELEMENT);
			try {
				byte [] encodedKey = XMLHelper.decodeElement(strKey);
				// This is a DER-encoded SubjectPublicKeyInfo.
				_key = decodeKey(encodedKey);
			} catch (IOException e) {
				Library.logger().warning("Cannot parse stored key: " + strKey + " error: " + e.getMessage());
				throw new XMLStreamException("Cannot parse key: " + strKey, e);
			} catch (InvalidKeySpecException e) {
				Library.logger().warning("Cannot turn stored key " + strKey + " into key of appropriate type.");
				throw new XMLStreamException("Cannot turn stored key " + strKey + " into key of appropriate type.");
			}
			if (null == _key) {
				throw new XMLStreamException("Cannot parse key: " + strKey);
			}
		} else if (type() == KeyLocatorType.CERTIFICATE) {
			String strCert = XMLHelper.readElementText(reader, PUBLISHER_CERTIFICATE_ELEMENT);
			try {
				byte [] encodedCert = XMLHelper.decodeElement(strCert);
				CertificateFactory factory = CertificateFactory.getInstance("X.509");
				_certificate = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(encodedCert));
			} catch (IOException e) {
				throw new XMLStreamException("Cannot parse certificate: " + strCert, e);
			} catch (CertificateException e) {
				throw new XMLStreamException("Cannot decode certificate: " + e.getMessage(), e);
			}
			if (null == _certificate) {
				throw new XMLStreamException("Cannot parse certificate: " + strCert);
			}
		} else if (type() == KeyLocatorType.NAME) {
			_name = new CompleteName();
			_name.decode(reader);
		}
		XMLHelper.readEndElement(reader);
	}
	
	public byte [] getEncoded() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			encode(baos);
		} catch (XMLStreamException e) {
			Library.logger().log(Level.WARNING, "This should not happen: cannot encode KeyLocator to byte array.");
			Library.warningStackTrace(e);
			// DKS currently returning invalid byte array...
		}
		return baos.toByteArray();
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, KEY_LOCATOR_ELEMENT, isFirstElement);
		writer.writeStartElement(KEY_LOCATOR_TYPE_ELEMENT);
		writer.writeCharacters(typeToName(type()));
		writer.writeEndElement();   
		if (type() == KeyLocatorType.KEY) {
			XMLHelper.writeElement(writer, PUBLISHER_KEY_ELEMENT, 
					XMLHelper.encodeElement(key().getEncoded()));
		} else if (type() == KeyLocatorType.CERTIFICATE) {
			writer.writeStartElement(PUBLISHER_CERTIFICATE_ELEMENT);
			try {
				writer.writeCharacters(XMLHelper.encodeElement(certificate().getEncoded()));
			} catch (CertificateEncodingException e) {
				Library.logger().warning("CertificateEncodingException attempting to write key locator: " + e.getMessage());
				throw new XMLStreamException(e);
			}
			writer.writeEndElement();
		} else if (type() == KeyLocatorType.NAME) {
			name().encode(writer);
		}
		writer.writeEndElement();   		
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
			Library.logger().info("Cannot find key type corresponding to OID: " + keyInfo.getAlgorithmId().getObjectId().toString());
		}
		KeyFactory keyFactory = null;
		PublicKey key = null;
		try {
			keyFactory = KeyFactory.getInstance(keyType);
			key = keyFactory.generatePublic(keySpec);
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("Unknown key type " + keyType + " in stored key.");
			throw new InvalidKeySpecException("Unknown key type " + keyType + " in stored key.");
		}
		return key;
	}
	
	public boolean validate() {
		return ((null != name() || (null != key()) || (null != certificate())));
	}

}
