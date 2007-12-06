package com.parc.ccn.data.security;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;
import com.parc.ccn.security.crypto.Digest;
import com.parc.ccn.security.crypto.MerkleTree;
import com.parc.ccn.security.crypto.SignatureHelper;

public class ContentAuthenticator extends GenericXMLEncodable implements XMLEncodable {

	public enum ContentType {FRAGMENT, LINK, CONTAINER, LEAF, SESSION, HEADER};
    protected static final HashMap<ContentType, String> ContentTypeNames = new HashMap<ContentType, String>();
    protected static final HashMap<String, ContentType> ContentNameTypes = new HashMap<String, ContentType>();
    public static final String CONTENT_AUTHENTICATOR_ELEMENT = "ContentAuthenticator";
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
    protected Timestamp		_timestamp;
    protected ContentType 	_type;
    // long	  	_size; // signed, must cope
    protected byte []		_contentDigest; // encoded DigestInfo
    protected KeyLocator  	_keyLocator;
    protected byte[]		_signature; // DKS might want to use Signature type

    public ContentAuthenticator(
    		byte[] publisher,
    		PublisherID.PublisherType publisherType,
    		Timestamp timestamp, 
    		ContentType type, 
    		// DKS may need to add structure to signature and hash, 
    		// partic contentDigest as it must also do hash trees
    		byte[] contentDigest, 
    		KeyLocator locator, 
    		byte[] signature) {
		this(new PublisherID(publisher, publisherType), 
				timestamp, type, contentDigest,
				locator, signature);
	}
    
    public ContentAuthenticator(
    		PublisherID publisher, 
			Timestamp timestamp, 
			ContentType type, 
			byte[] contentDigest, 
			KeyLocator locator, 
			byte[] signature) {
    	super();
    	this._publisher = publisher;
    	this._timestamp = timestamp;
    	this._type = type;
    	_contentDigest = contentDigest;
    	_keyLocator = locator;
    	this._signature = signature;
    }
    
    /**
     * Helper constructors. 
     * @throws SignatureException 
     * @throws NoSuchAlgorithmException 
     * @throws InvalidKeyException 
     */
    public ContentAuthenticator(
    		ContentName name,
    		PublisherID publisher,
    		ContentType type,
    		byte [] content, // will be hashed
    		KeyLocator locator,
    		PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
    	this(name, publisher, now(), type, content,
    			false, locator, signingKey);
   }
    
 	public ContentAuthenticator(
    		ContentName name,
    		PublisherID publisher,
    		ContentType type,
       		byte [] contentOrDigest, // may be already hashed
    		boolean isDigest, // should we digest it or is it already done?
    		KeyLocator locator,
    		PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
    	this(name, publisher, now(), type, contentOrDigest,
    			isDigest, locator, signingKey);
    }

    public ContentAuthenticator(
    		ContentName name,
    		PublisherID publisher,
    		Timestamp timestamp,
    		ContentType type,
    		byte [] contentOrDigest, // may be already hashed
    		boolean isDigest, // should we digest it or is it already done?
    		KeyLocator locator,
    		PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
    	super();
    	this._publisher = publisher;
    	this._timestamp = timestamp;
    	this._type = type;
    	if (isDigest)
    		_contentDigest = contentOrDigest;
    	else
    		_contentDigest = Digest.hash(contentOrDigest);
    	_keyLocator = locator;
    	// Might need to be a factory method instead
    	// of a constructor, as calling class methods
    	// in a constructor is dicey.
    	sign(name, signingKey);
     }

    /**
     * The content authenticators for a set of fragments consist of:
     * The authenticator of the header block, which contains both
     * the root hash of the Merkle tree and the hash of the content.
     * This is a normal authenticator, signing the header block 
     * as content.
     * Then for each fragment, the authenticator contains the signature
     * on the root hash as the signature, and the set of hashes necessary
     * to verify the Merkle structure as the content hash.
     * 
     * The signature on the root node is actually a signature
     * on a content authenticator containing the root hash as its
     * content item. That way we also sign the publisher ID, type,
     * etc.
     * 
     * This method returns an array of ContentAuthenticators, one
     * per block of content.
     * @throws SignatureException 
     * @throws NoSuchAlgorithmException 
     * @throws InvalidKeyException 
     */
    public static ContentAuthenticator []
    	authenticatedHashTree(ContentName name,
    						  PublisherID publisher,
    						  Timestamp timestamp,
        					  ContentType type,
        					  MerkleTree tree,
        					  KeyLocator locator,
        					  PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
    	
    	// Need to sign the root node, along with the other supporting
    	// data.
    	ContentAuthenticator rootAuthenticator = 
    			new ContentAuthenticator(name, publisher, timestamp, type, tree.root(), 
    									 true, locator, signingKey);

    	ContentAuthenticator [] authenticators = new ContentAuthenticator[tree.numLeaves()];
    	
    	// Now build the authenticator structures.
    	for (int i=0; i < authenticators.length; ++i) {
    		authenticators[i] = 
    			new ContentAuthenticator(publisher, timestamp, 
    									 type, tree.derEncodedPath(i),
    									 locator, rootAuthenticator.signature());
    	}
    	return authenticators;
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

    public ContentAuthenticator() {}
    
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

		if (XMLHelper.peekStartElement(reader, TIMESTAMP_ELEMENT)) {
			String strTimestamp = XMLHelper.readElementText(reader, TIMESTAMP_ELEMENT);
			_timestamp = Timestamp.valueOf(strTimestamp);
			if (null == _timestamp) {
				throw new XMLStreamException("Cannot parse timestamp: " + strTimestamp);
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
		// TODO DKS - make match correct XML timestamp format
		// dateTime	1999-05-31T13:20:00.000-05:00
		// currently writing 2007-10-23 21:36:05.828
		if (null != timestamp()) {
			writer.writeStartElement(TIMESTAMP_ELEMENT);
			writer.writeCharacters(timestamp().toString());
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
	 * components omitted. 
	 * @throws SignatureException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public void sign(ContentName name, String digestAlgorithm, PrivateKey signingKey) 
		throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
		
		// Build XML document
		CompleteName completeName = new CompleteName(name, this);
		_signature = SignatureHelper.sign(digestAlgorithm, completeName, signingKey);
	}
	
	public void sign(ContentName name, PrivateKey signingKey) 
			throws SignatureException, InvalidKeyException {
		try {
			sign(name, Digest.DEFAULT_DIGEST, signingKey);
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("Cannot find default digest algorithm: " + Digest.DEFAULT_DIGEST);
			Library.warningStackTrace(e);
			throw new SignatureException(e);
		}
	}

}
