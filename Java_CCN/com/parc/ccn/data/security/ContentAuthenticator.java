package com.parc.ccn.data.security;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;
import com.parc.ccn.security.crypto.DigestHelper;
import com.parc.ccn.security.crypto.SignatureHelper;
import com.parc.ccn.security.keys.KeyManager;

public class ContentAuthenticator extends GenericXMLEncodable implements XMLEncodable {

	public enum ContentType {FRAGMENT, LINK, CONTAINER, LEAF, SESSION, HEADER};
    protected static final HashMap<ContentType, String> ContentTypeNames = new HashMap<ContentType, String>();
    protected static final HashMap<String, ContentType> ContentNameTypes = new HashMap<String, ContentType>();
    public static final String CONTENT_AUTHENTICATOR_ELEMENT = "ContentAuthenticator";
    protected static final String NAME_COMPONENT_COUNT_ELEMENT = "NameComponentCount";
    protected static final String TIMESTAMP_ELEMENT = "Timestamp";
    protected static final String CONTENT_TYPE_ELEMENT = "Type";
    protected static final String CONTENT_HASH_ELEMENT = "ContentHash";
    protected static final String SIGNATURE_ELEMENT = "Signature";
    
    static {
        ContentTypeNames.put(ContentType.FRAGMENT, "FRAGMENT");
        ContentTypeNames.put(ContentType.LINK, "LINK");
        ContentTypeNames.put(ContentType.CONTAINER, "CONTAINER");
        ContentTypeNames.put(ContentType.LEAF, "LEAF");
        ContentTypeNames.put(ContentType.SESSION, "SESSION");
        ContentTypeNames.put(ContentType.HEADER, "HEADER");
        ContentNameTypes.put("FRAGMENT", ContentType.FRAGMENT);
        ContentNameTypes.put("LINK", ContentType.LINK);
        ContentNameTypes.put("CONTAINER", ContentType.CONTAINER);
        ContentNameTypes.put("LEAF", ContentType.LEAF);
        ContentNameTypes.put("SESSION", ContentType.SESSION);
        ContentNameTypes.put("HEADER", ContentType.HEADER);
    }
    
    protected PublisherID	_publisher;
    // int   		_version; // Java types are signed, must cope
    protected Integer 		_nameComponentCount; // how many name components are signed
    									 // if omitted, assume all
    protected Timestamp		_timestamp;
    protected ContentType 	_type;
    // long	  	_size; // signed, must cope
    protected byte []		_contentDigest; // encoded DigestInfo
    protected KeyLocator  	_keyLocator;
    protected byte[]		_signature; // DKS might want to use Signature type
    
    public ContentAuthenticator(
    		PublisherID publisher, 
    		Integer nameLength,
			Timestamp timestamp, 
			ContentType type, 
       		byte [] contentOrDigest, // may be already hashed
    		boolean isDigest, // should we digest it or is it already done?
			KeyLocator locator, 
			byte[] signature) {
    	super();
    	this._publisher = publisher;
    	this._nameComponentCount = nameLength;
    	this._timestamp = timestamp;
    	this._type = type;
    	try {
    		if (isDigest)
    			// Should check to see if it is encoded.
    			// If not, have to pass in algorithm to allow encoding.
    			_contentDigest = contentOrDigest;
    		else
	    		_contentDigest = 
	    			DigestHelper.encodedDigest(contentOrDigest);
    	} catch (CertificateEncodingException e) {
    		Library.logger().warning("This should not happen: exception encoding digest using built-in algorithms: " + e.getMessage());
    		Library.warningStackTrace(e);
    		// DKS TODO what to throw?
    	}
    	_keyLocator = locator;
    	this._signature = signature;
    }
    
    /**
     * Helper constructors. Be careful, may want to be
     * using generateAuthenticatedName.
     * @throws SignatureException 
     * @throws NoSuchAlgorithmException 
     * @throws InvalidKeyException 
     */
    public ContentAuthenticator(
    		ContentName name,
       		Integer nameComponentCount,
       		PublisherID publisher,
     		Timestamp timestamp,
    		ContentType type,
    		byte [] contentOrDigest, // may be already hashed
    		boolean isDigest, // should we digest it or is it already done?
    		KeyLocator locator,
    		PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
    	this(publisher, nameComponentCount, timestamp, type, contentOrDigest,
    		 isDigest, locator, null);
    	// Might need to be a factory method instead
    	// of a constructor, as calling class methods
    	// in a constructor is dicey.
    	sign(name, ((null != nameComponentCount) ? 
    				nameComponentCount : Integer.valueOf(name.count())), signingKey);
	}
    
    /**
     * Generate unique name and authentication information.
     * @param signingKey if null, only generates unique name
     * 	and filled-in content authenticator. Can complete using
     *  completeName.authenticator.sign(completeName.name, signingKey).
     * @return
     * @throws SignatureException 
     * @throws InvalidKeyException 
     */
	public static CompleteName generateAuthenticatedName(
	   		ContentName name,
    		PublisherID publisher,
    		Timestamp timestamp,
    		ContentType type,
    		byte [] contentOrDigest, // may be already hashed
    		boolean isDigest, // should we digest it or is it already done?
    		KeyLocator locator,
    		PrivateKey signingKey) throws SignatureException, InvalidKeyException {
		
		// Generate raw authenticator.
		ContentAuthenticator authenticator =
			new ContentAuthenticator(publisher, null, timestamp, type,
									 contentOrDigest, isDigest,
									 locator, null);
		byte [] authenticatorDigest = null;
		try {
			authenticatorDigest =
				DigestHelper.digest(
						authenticator.canonicalizeAndEncode());
		} catch (XMLStreamException e) {
			Library.handleException("Exception encoding internally-generated XML!", e);
			throw new SignatureException(e);
		}
		
		ContentName fullName = 
			new ContentName(name, authenticatorDigest);
		if (null != signingKey)
			authenticator.sign(fullName, fullName.count(), signingKey);
		return new CompleteName(fullName, authenticator);
	}
	    
    /**
     * For queries.
     * @param publisher
     */
    public ContentAuthenticator(PublisherID publisher) {
    	super();
    	this._publisher = publisher;
    }

    public ContentAuthenticator(byte [] encoded) throws XMLStreamException {
    	super(encoded);
    }
    
    public ContentAuthenticator(ContentAuthenticator other) {
    	this(other.publisherID(), 
    		 other.nameComponentCount(),
    		 other.timestamp(),
    		 other.type(), 
    		 other.contentDigest(), true,
    		 other.keyLocator(), other.signature());
    }

    public ContentAuthenticator() {}
    
    public static ContentAuthenticator unsignedCopy(ContentAuthenticator other) {
    	return new ContentAuthenticator(
    		 other.publisherID(), 
       		 other.nameComponentCount(),
       		 other.timestamp(),
       		 other.type(), 
       		 other.contentDigest(), true,
       		 other.keyLocator(), null);
    }
    
    public static ContentAuthenticator copyToVerify(ContentAuthenticator other) throws CertificateEncodingException {
    	// Need to put in for example the Merkle root if we're
    	// using a hash tree..
    	return
    		new ContentAuthenticator(
    		 other.publisherID(), 
       		 other.nameComponentCount(),
       		 other.timestamp(),
       		 other.type(), 
       		 DigestHelper.digestToSign(other.contentDigest()), true,
       		 other.keyLocator(), null);
    }
    
    public boolean empty() {
    	return (emptyPublisher() && emptySignature() && emptyContentDigest());
    }
    
    public boolean emptyPublisher() {
    	if ((null != publisherID()) && (0 != publisher().length))
    		return false;
    	return true;
    }
    
    public boolean emptySignature() {
       	if ((null != signature()) && (0 != signature().length))
    		return false;
       	return true;
    }
    
    public boolean emptyContentDigest() {
    	if ((null != contentDigest()) && (0 != contentDigest().length))
    		return false;
    	return true;   	
    }
    
    public boolean emptyContentType() { 
    	return (null == _type);
    }
    
    public boolean emptyNameComponentCount() {
    	return (null == _nameComponentCount);
    }
    
    public boolean emptyTimestamp() {
    	return (null == _timestamp);
    }
    
	public byte[] contentDigest() {
		return _contentDigest;
	}
	public void contentDigest(byte[] hash) {
		_contentDigest = hash;
	}
	public KeyLocator keyLocator() {
		return _keyLocator;
	}
	public void keyLocator(KeyLocator locator) {
		_keyLocator = locator;
	}
	public byte[] publisher() {
		return _publisher.id();
	}
	public PublisherID.PublisherType publisherType() {
		return _publisher.type();
	}
	public PublisherID publisherID() {
		return _publisher;
	}
	public void publisher(byte[] publisher, PublisherID.PublisherType publisherType) {
		this._publisher = new PublisherID(publisher, publisherType);
	}
	public byte[] signature() {
		return _signature;
	}
	public void signature(byte[] signature) {
		this._signature = signature;
	}
	public Timestamp timestamp() {
		return _timestamp;
	}
	public void timestamp(Timestamp timestamp) {
		this._timestamp = timestamp;
	}
	public ContentType type() {
		return _type;
	}
	public void type(ContentType type) {
		this._type = type;
	}
	
	public Integer nameComponentCount() { return _nameComponentCount; }
	public void nameComponentCount(Integer nameComponentCount) {
		this._nameComponentCount = nameComponentCount;
	}
	
	public String typeName() {
		return typeToName(type());
	}
	
	public static String typeToName(ContentType type) {
		if (ContentTypeNames.get(type) == null) {
			Library.logger().warning("Cannot find name for type: " + type);
		}
		return ContentTypeNames.get(type);
	}

	public static ContentType nameToType(String name) {
		return ContentNameTypes.get(name);
	}
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_contentDigest);
		result = PRIME * result + ((_keyLocator == null) ? 0 : _keyLocator.hashCode());
		result = PRIME * result + ((_publisher == null) ? 0 : _publisher.hashCode());;
		result = PRIME * result + ((_nameComponentCount == null) ? 0 : _nameComponentCount.hashCode());;
		result = PRIME * result + Arrays.hashCode(_signature);
		result = PRIME * result + ((_timestamp == null) ? 0 : _timestamp.hashCode());
		result = PRIME * result + ((_type == null) ? 0 : _type.hashCode());
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
		final ContentAuthenticator other = (ContentAuthenticator) obj;
		if (_contentDigest == null) {
			if (other._contentDigest != null)
				return false;
		} else if (!Arrays.equals(_contentDigest, other._contentDigest))
			return false;
		if (_keyLocator == null) {
			if (other._keyLocator != null)
				return false;
		} else if (!_keyLocator.equals(other._keyLocator))
			return false;
		if (_publisher == null) {
			if (other._publisher != null)
				return false;
		} else if (!_publisher.equals(other._publisher))
			return false;
		if (_nameComponentCount == null) {
			if (other._nameComponentCount != null)
				return false;
		} else if (!_nameComponentCount.equals(other._nameComponentCount))
			return false;
		if (_signature == null) {
			if (other._signature != null)
				return false;
		} else if (!Arrays.equals(_signature, other._signature))
			return false;
		if (_timestamp == null) {
			if (other._timestamp != null)
				return false;
		} else if (!_timestamp.equals(other._timestamp))
			return false;
		if (_type == null) {
			if (other.type() != null)
				return false;
		} else if (!_type.equals(other.type()))
			return false;
		return true;
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, CONTENT_AUTHENTICATOR_ELEMENT);
		
		if (XMLHelper.peekStartElement(reader, PublisherID.PUBLISHER_ID_ELEMENT)) {
			_publisher = new PublisherID();
			_publisher.decode(reader);
		}

		if (XMLHelper.peekStartElement(reader, NAME_COMPONENT_COUNT_ELEMENT)) {
			String strLength = XMLHelper.readElementText(reader, NAME_COMPONENT_COUNT_ELEMENT); 
			_nameComponentCount = Integer.valueOf(strLength);
			if (null == _nameComponentCount) {
				throw new XMLStreamException("Cannot parse name length: " + strLength);
			}
		}
			
		if (XMLHelper.peekStartElement(reader, TIMESTAMP_ELEMENT)) {
			String strTimestamp = XMLHelper.readElementText(reader, TIMESTAMP_ELEMENT);
			try {
				_timestamp = XMLHelper.parseDateTime(strTimestamp);
			} catch (ParseException e) {
				throw new XMLStreamException("Cannot parse timestamp: " + strTimestamp, e);
			}
		}

		if (XMLHelper.peekStartElement(reader, CONTENT_TYPE_ELEMENT)) {
			String strType = XMLHelper.readElementText(reader, CONTENT_TYPE_ELEMENT);
			_type = nameToType(strType);
			if (null == _type) {
				throw new XMLStreamException("Cannot parse authenticator type: " + strType);
			}
		}
		
		if (XMLHelper.peekStartElement(reader, CONTENT_HASH_ELEMENT)) {
			String strHash = XMLHelper.readElementText(reader, CONTENT_HASH_ELEMENT);
			try {
				_contentDigest = XMLHelper.decodeElement(strHash);
			} catch (IOException e) {
				throw new XMLStreamException("Cannot parse content hash: " + strHash, e);
			}
			if (null == _contentDigest) {
				throw new XMLStreamException("Cannot parse content hash: " + strHash);
			}
		}
		
		if (XMLHelper.peekStartElement(reader, KeyLocator.KEY_LOCATOR_ELEMENT)) {
			_keyLocator = new KeyLocator();
			_keyLocator.decode(reader);
		}
		
		if (XMLHelper.peekStartElement(reader, SIGNATURE_ELEMENT)) {
			String strSig = XMLHelper.readElementText(reader, SIGNATURE_ELEMENT);
			try {
				_signature = XMLHelper.decodeElement(strSig);
			} catch (IOException e) {
				throw new XMLStreamException("Cannot parse signature: " + strSig, e);
			}
			if (null == _signature) {
				throw new XMLStreamException("Cannot parse signature: " + strSig);
			}
		}
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, CONTENT_AUTHENTICATOR_ELEMENT, isFirstElement);
		
		if (!emptyPublisher()) {
			publisherID().encode(writer);
		}

		if (!emptyNameComponentCount()) {
			XMLHelper.writeElement(writer, NAME_COMPONENT_COUNT_ELEMENT, Integer.toString(nameComponentCount()));
		}

		// TODO DKS - make match correct XML timestamp format
		// dateTime	1999-05-31T13:20:00.000-05:00
		// currently writing 2007-10-23 21:36:05.828
		if (!emptyTimestamp()) {
			writer.writeStartElement(TIMESTAMP_ELEMENT);
			writer.writeCharacters(XMLHelper.formatDateTime(timestamp()));
			writer.writeEndElement();
		}
		
		if (!emptyContentType()) {
			writer.writeStartElement(CONTENT_TYPE_ELEMENT);
			writer.writeCharacters(typeName());
			writer.writeEndElement();
		}
		
		if (!emptyContentDigest()) {
			writer.writeStartElement(CONTENT_HASH_ELEMENT);
			writer.writeCharacters(XMLHelper.encodeElement(contentDigest()));
			writer.writeEndElement();   
		}
		if (null != keyLocator()) {
			keyLocator().encode(writer);
		}
		if (!emptySignature()) {
			writer.writeStartElement(SIGNATURE_ELEMENT);
			writer.writeCharacters(XMLHelper.encodeElement(signature()));
			writer.writeEndElement();   
		}
		writer.writeEndElement();   		
	}
	
	public boolean validate() {
		// any of the fields could be null when used 
		// as a partial-match pattern
		return true;
	}

	public static Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}
	
	/**
	 * Generate a signature on a name-content mapping. This
	 * signature is specific to both this content authenticator
	 * and this name. We sign the canonicalized XML
	 * of a CompleteName, with any non-provided optional
	 * components omitted. We include componentCount number
	 * of components of the name given (if that is null,
	 * we sign all of it). That allows for signing prefixes
	 * of structures, e.g. for fragmented data.
	 * DKS: TODO: want to sign something that the low level
	 * can verify easily. Might imply that XML should put
	 * back in CompleteName.
	 * @throws SignatureException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public void sign(ContentName name, 
					 Integer componentCount,
					 String digestAlgorithm, 
					 PrivateKey signingKey) 
		throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
		
		// Build XML document
		_signature = null;
		if ((null != componentCount) && (componentCount > name.count())) {
			throw new SignatureException("Cannot sign more name components than given!");
		}
		
		// TODO DKS -- optimize signing and verification.
		
		// This handles a null component count.
		_nameComponentCount = componentCount;
		// Do we reflect the componentCount in the name we make
		// or how we sign it?
		CompleteName completeName = 
			new CompleteName(name, componentCount, this);
		try {
			_signature = 
				SignatureHelper.sign(digestAlgorithm, 
									 completeName, 
									 signingKey);
		} catch (XMLStreamException e) {
			Library.handleException("Exception encoding internally-generated XML name!", e);
			throw new SignatureException(e);
		}
	}
	
	public void sign(ContentName name, 
					 Integer componentCount,
					 PrivateKey signingKey) 
			throws SignatureException, InvalidKeyException {
		try {
			sign(name, componentCount, DigestHelper.DEFAULT_DIGEST_ALGORITHM, signingKey);
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("Cannot find default digest algorithm: " + DigestHelper.DEFAULT_DIGEST_ALGORITHM);
			Library.warningStackTrace(e);
			throw new SignatureException(e);
		}
	}
	
	/**
	 * Low-level verification. Might require putting back
	 * CompleteName wrapper in ContentObject. Might not
	 * want to always verify root signature for hash tree,
	 * separate out verification of hash path from 
	 * verification of signature on root.
	 * 
	 * This doesn't tell you that you should trust the content,
	 * only that the signature is correct and the content
	 * matches. You can then reason on the name and signer
	 * to determine trust.
	 * @param verifySignature If we have a collection of blocks
	 * 	 all authenticated by the public key signature, we may
	 * 	 only need to verify that signature once. If verifySignature
	 *   is true, we do that work. If it is false, we simply verify
	 *   that this piece of content matches that signature; assuming 
	 *   that the caller has alreaday verified that signature.
	 * @throws SignatureException 
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public static boolean verify(ContentObject object,
								 boolean verifySignature) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException {
		// TODO DKS: make more efficient.
		
		// Start with the cheap part. Check that the content
		// matches the content digest, and that it fits into
		// hash tree. Takes the content and a content digest,
		// which is expected to be in the form of a DigestInfo.
		boolean result = false;
		try {
			result = verifyContentDigest(object.authenticator().contentDigest(),
					 object.content());
		} catch (CertificateEncodingException e) {
			Library.logger().info("Encoding exception attempting to verify content digest for object: " + object.name() + ". Signature verification fails.");
			return false;
		}
		
		if (!result) {
			Library.logger().info("Content object: " + object.name() + " content does not verify.");
			return result;
		}
	
		if (verifySignature) {
			result = verifyContentSignature(object);
		}
		return result;
	}

	public static boolean verifyContentDigest(
			byte[] contentDigest,
			byte[] content) throws CertificateEncodingException {
		return DigestHelper.verifyDigest(contentDigest, content);
	}
	
	/**
	 * Verify the public key signature on a content object.
	 * Does not verify that the content matches the signature,
	 * merely that the signature over the name and content
	 * authenticator is correct and was performed with the
	 * indicated public key.
	 * @return
	 * @throws SignatureException 
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public static boolean verifyContentSignature(ContentObject object) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException {
		// Get a copy of the public key.
		// Simple routers will install key manager that
		// will just pull from CCN.
		PublicKey publicKey = 
			KeyManager.getKeyManager().getPublicKey(
							object.authenticator().publisherID(),
							object.authenticator().keyLocator());
	
		if (null == publicKey) {
			throw new SignatureException("Cannot obtain public key to verify object: " + object.name() + ". Key locator: " + 
						object.authenticator().keyLocator());
		}
	
		// Format data for verification. If this
		// is a fragment, we will need to verify just the 
		// root. 
		CompleteName verifyName;
		try {
			verifyName = new CompleteName(
						 object.name(), 
						 object.authenticator().nameComponentCount(), 
						 copyToVerify(object.authenticator()));
		} catch (CertificateEncodingException e) {
			Library.logger().info("Encoding exception attempting to parse content digest for verification for object: " + object.name() + ". Signature verification fails.");
			return false;
		}
	
		// Now, check the signature.
		boolean result = 
			SignatureHelper.verify(verifyName, 
							   	   object.authenticator().signature(),
							   	   publicKey);
		return result;
		
	}
}
