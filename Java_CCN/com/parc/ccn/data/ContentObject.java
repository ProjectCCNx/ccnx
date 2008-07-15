package com.parc.ccn.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.util.BinaryXMLCodec;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLCodecFactory;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.security.crypto.DigestHelper;
import com.parc.ccn.security.crypto.SignatureHelper;
import com.parc.ccn.security.keys.KeyManager;

/**
 * Utility class for grouping all of the bits associated
 * with a piece of content.
 * @author smetters
 *
 */
public class ContentObject extends GenericXMLEncodable implements XMLEncodable {
	
	protected static final String CONTENT_OBJECT_ELEMENT = "ContentObject";
	protected static final String CONTENT_ELEMENT = "Content";
	
	protected ContentName _name;
	protected ContentAuthenticator _authenticator;
    protected byte [] _content;
	protected Signature _signature; // DKS might want to use Signature type
    public String _digestAlgorithm = null; 
    
    public ContentObject(String digestAlgorithm, // prefer OID
    					 ContentName name,
    					 ContentAuthenticator authenticator,
    					 byte [] content,
    					 Signature signature
    					 ) {
    	_name = name;
    	_authenticator = authenticator;
    	_content = content;
    	_signature = signature;
    }
    
    public ContentObject(ContentName name, ContentAuthenticator authenticator, byte [] content,
    					 Signature signature) {
    	this(DigestHelper.DEFAULT_DIGEST_ALGORITHM, name, authenticator, content, signature);
    }
    
    /**
     * Helper function
     */
    public ContentObject(CompleteName completeName, byte [] content) {
    	this(completeName.name(), completeName.authenticator(), content, completeName.signature());
    }
    
    /**
     * Generate an authenticator and a signature.
     * @throws SignatureException 
     * @throws InvalidKeyException 
     */
    public ContentObject(ContentName name, 
    					 ContentAuthenticator authenticator,
    					 byte [] content, PrivateKey signingKey) throws InvalidKeyException, SignatureException {
    	this(name, authenticator, content, (Signature)null);
    	_signature = sign(name, authenticator, content, signingKey);
    }
    
    public ContentObject() {} // for use by decoders
    
    public ContentObject clone() {
    	return new ContentObject(_name, _authenticator, _content.clone(), _signature.clone());
    }
    
    public ContentName name() { 
    	return _name;
    }
    
    public ContentAuthenticator authenticator() { 
    	return _authenticator;
    }
    
    public byte [] content() { return _content; }
    
    public Signature signature() { return _signature; }
    
    public void signature(Signature signature) { _signature = signature; }
    
    public CompleteName completeName() { return new CompleteName(name(), authenticator(), signature()); }

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(CONTENT_OBJECT_ELEMENT);

		_signature = new Signature();
		_signature.decode(decoder);
		
		_name = new ContentName();
		_name.decode(decoder);
		
		_authenticator = new ContentAuthenticator();
		_authenticator.decode(decoder);
		
		_content = decoder.readBinaryElement(CONTENT_ELEMENT);

		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(CONTENT_OBJECT_ELEMENT);

		signature().encode(encoder);
		name().encode(encoder);
		authenticator().encode(encoder);

		// needs to handle null content
		encoder.writeElement(CONTENT_ELEMENT, _content);

		encoder.writeEndElement();   		
	}
	
	public boolean validate() { 
		// recursive?
		// null content ok
		return ((null != name()) && (null != authenticator()) && (null != signature()));
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
		result = PRIME * result + ((_authenticator == null) ? 0 : _authenticator.hashCode());
		result = PRIME * result + ((_signature == null) ? 0 : _signature.hashCode());
		result = PRIME * result + Arrays.hashCode(_content);
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
		final ContentObject other = (ContentObject) obj;
		if (_name == null) {
			if (other.name() != null)
				return false;
		} else if (!_name.equals(other.name()))
			return false;
		if (_authenticator == null) {
			if (other.authenticator() != null)
				return false;
		} else if (!_authenticator.equals(other.authenticator()))
			return false;
		if (_signature == null) {
			if (other.signature() != null)
				return false;
		} else if (!_signature.equals(other.signature()))
			return false;
		if (!Arrays.equals(_content, other._content))
			return false;
		return true;
	}
	
	/**
	 * Generate a signature on a name-content mapping. This
	 * signature is specific to both this content authenticator
	 * and this name. The ContentAuthenticator no longer contains
	 * a proxy for the content, so we sign the content itself
	 * directly.  This is used with simple algorithms that don't
	 * generate a witness.
	 * DKS -- TODO - do we sign the content or a hash of the content?
	 * @throws SignatureException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public static Signature sign(ContentName name, 
					 		   ContentAuthenticator authenticator,
					 		   byte [] content,
					 		   String digestAlgorithm, 
					 		   PrivateKey signingKey) 
		throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
		
		// Build XML document
		byte [] signature = null;
		
		try {
			signature = 
				SignatureHelper.sign(digestAlgorithm, 
									 prepareContent(name, authenticator,content),
									 signingKey);
		} catch (XMLStreamException e) {
			Library.handleException("Exception encoding internally-generated XML name!", e);
			throw new SignatureException(e);
		}
		return new Signature(digestAlgorithm, null, signature);
	}
	
	public static Signature sign(ContentName name, 
					 		   ContentAuthenticator authenticator,
					 		   byte [] content,
					 		   PrivateKey signingKey) 
			throws SignatureException, InvalidKeyException {
		try {
			return sign(name, authenticator, content, DigestHelper.DEFAULT_DIGEST_ALGORITHM, signingKey);
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("Cannot find default digest algorithm: " + DigestHelper.DEFAULT_DIGEST_ALGORITHM);
			Library.warningStackTrace(e);
			throw new SignatureException(e);
		}
	}
	
	public boolean verify(PublicKey publicKey) 
					throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, 
								XMLStreamException, InterruptedException {
		return verify(this, publicKey);
	}
	
	/**
	 * Want to verify a content object. First compute the 
	 * witness result (e.g. Merkle path root, or possibly content
	 * proxy), and make it available to the caller if caller just
	 * needs to check whether it matches a previous round. Then
	 * verify the actual signature.
	 * 
	 * @param verifySignature If we have a collection of blocks
	 * 	 all authenticated by the public key signature, we may
	 * 	 only need to verify that signature once. If verifySignature
	 *   is true, we do that work. If it is false, we simply verify
	 *   that this piece of content matches that signature; assuming 
	 *   that the caller has already verified that signature. If you're
	 *   not sure what all this means, you shouldn't be calling this
	 *   one; use the simple verify above.
	 * @param publicKey If the caller already knows a public key
	 *   that should be used to verify the signature, they can
	 *   pass it in. Otherwise, the key locator in the object
	 *   will be used to find the key.
	 * @throws SignatureException 
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws InterruptedException 
	 */
	public static boolean verify(ContentObject object,
								 PublicKey publicKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException, InterruptedException {
		
		// Start with the cheap part. Derive the content proxy that was signed. This is
		// either the root of the MerkleHash tree, the content itself, or the digest of
		// the content. 
		byte [] contentProxy = null;
		try {
			// Callers that think they don't need to recompute the signature can just compute
			// the proxy and check.
			// The proxy may be dependent on the whole object. If there is a proxy, signature
			// is over that. Otherwise, signature is over hash of the content and name and authenticator.
			contentProxy = object.computeProxy();
		} catch (CertificateEncodingException e) {
			Library.logger().info("Encoding exception attempting to verify content digest for object: " + object.name() + ". Signature verification fails.");
			return false;
		}
		
		if (null != contentProxy) {
			return SignatureHelper.verify(contentProxy, object.signature().signature(), object.signature().digestAlgorithm(), publicKey);
		}
	
		return verify(object.name(), object.authenticator(), object.content(), object.signature(), publicKey);
	}

	/**
	 * Verify the public key signature on a content object.
	 * Does not verify that the content matches the signature,
	 * merely that the signature over the name and content
	 * authenticator is correct and was performed with the
	 * indicated public key.
	 * @param contentProxy the proxy for the content that was signed. This could
	 * 	be the content itself, a digest of the content, or the root of a Merkle hash tree.
	 * @return
	 * @throws SignatureException 
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws InterruptedException 
	 */
	public static boolean verify(
			ContentName name,
			ContentAuthenticator authenticator,
			byte [] content,
			Signature signature,
			PublicKey publicKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException, InterruptedException {
	
		if (null == publicKey) {
			// Get a copy of the public key.
			// Simple routers will install key manager that
			// will just pull from CCN.
			try {
				publicKey = 
					KeyManager.getKeyManager().getPublicKey(
						authenticator.publisherKeyID(),
						authenticator.keyLocator());

				if (null == publicKey) {
					throw new SignatureException("Cannot obtain public key to verify object: " + name + ". Key locator: " + 
						authenticator.keyLocator());
				}
			} catch (IOException e) {
				throw new SignatureException("Cannot obtain public key to verify object: " + name + ". Key locator: " + 
						authenticator.keyLocator() + " exception: " + e.getMessage(), e);				
			}
		}
	
		// Now, check the signature.
		boolean result = 
			SignatureHelper.verify(prepareContent(name, authenticator, content),
					signature.signature(),
					(signature.digestAlgorithm() == null) ? DigestHelper.DEFAULT_DIGEST_ALGORITHM : signature.digestAlgorithm(),
							publicKey);
		return result;
		
	}

	public static boolean verify(byte[] proxy, byte [] signature, ContentAuthenticator authenticator,
			String digestAlgorithm, PublicKey publicKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException {
		if (null == publicKey) {
			// Get a copy of the public key.
			// Simple routers will install key manager that
			// will just pull from CCN.
			try {
				publicKey = 
					KeyManager.getKeyManager().getPublicKey(
						authenticator.publisherKeyID(),
						authenticator.keyLocator());

				if (null == publicKey) {
					throw new SignatureException("Cannot obtain public key to verify object. Key locator: " + 
						authenticator.keyLocator());
				}
			} catch (IOException e) {
				throw new SignatureException("Cannot obtain public key to verify object. Key locator: " + 
						authenticator.keyLocator() + " exception: " + e.getMessage(), e);				
			}
		}
	
		// Now, check the signature.
		boolean result = 
			SignatureHelper.verify(proxy,
					signature,
					(digestAlgorithm == null) ? DigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm,
							publicKey);
		return result;
	}

	public byte [] computeProxy() throws CertificateEncodingException, XMLStreamException {
		// Given a witness and an object, compute the proxy.
		if (null == content())
			return null;
		if ((null == signature()) || (null == signature().witness())) {
			return null;
		}
		// Have to eventually handle various forms of witnesses...
		byte [] blockDigest =
			DigestHelper.digest(
				DigestHelper.DEFAULT_DIGEST_ALGORITHM, 
				prepareContent(name(), authenticator(),content()));
		return signature().computeProxy(blockDigest, true);
	}
	
	/**
	 * Prepare digest for signature.
	 * @return
	 */
	public static byte [] prepareContent(ContentName name, 
										ContentAuthenticator authenticator, byte [] content) throws XMLStreamException {
		if ((null == name) || (null == authenticator) || (null == content)) {
			Library.logger().info("Name, authenticator and content must not be null.");
			throw new XMLStreamException("prepareContent: name, authenticator and content must not be null.");
		}
		
		// Do setup. Binary codec doesn't write a preamble or anything.
		// If allow to pick, text encoder would sometimes write random stuff...
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		XMLEncoder encoder = XMLCodecFactory.getEncoder(BinaryXMLCodec.CODEC_NAME);
		encoder.beginEncoding(baos);
		
		// We include the tags in what we verify, to allow routers to merely
		// take a chunk of data from the packet and sign/verify it en masse
		name.encode(encoder);
		authenticator.encode(encoder);
		// We treat content as a blob according to the binary codec. Want to always
		// sign the same thing, plus it's really hard to do the automated codec
		// stuff without doing a whole document, unless we do some serious
		// rearranging.
		encoder.writeElement(CONTENT_ELEMENT, content);

		encoder.endEncoding();	

		return baos.toByteArray();
	}

}
