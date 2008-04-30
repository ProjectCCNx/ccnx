package com.parc.ccn.data;

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
import com.parc.ccn.data.util.GenericXMLEncodable;
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
    protected static final String SIGNATURE_ELEMENT = "Signature";
	
	protected ContentName _name;
	protected ContentAuthenticator _authenticator;
    protected byte[] _signature; // DKS might want to use Signature type
    protected byte [] _content;
    
    public ContentObject(ContentName name,
    					 ContentAuthenticator authenticator,
    					 byte [] signature,
    					 byte [] content) {
    	_name = name;
    	_authenticator = authenticator;
    	_signature = signature;
    	_content = content;
    }
    
    /**
     * Generate an authenticator and a signature.
     * @throws SignatureException 
     * @throws InvalidKeyException 
     */
    public ContentObject(ContentName name, Integer componentCount,
    					 ContentAuthenticator authenticator,
    					 byte [] content, PrivateKey signingKey) throws InvalidKeyException, SignatureException {
    	this(name, authenticator, null, content);
    	_signature = sign(name, componentCount, authenticator, signingKey);
    }
    
    public ContentObject() {} // for use by decoders
    
    public ContentObject clone() {
    	return new ContentObject(_name, _authenticator, _signature.clone(), _content.clone());
    }
    
    public ContentName name() { 
    	return _name;
    }
    
    public ContentAuthenticator authenticator() { 
    	return _authenticator;
    }
    
    public byte [] content() { return _content; }
    
    public byte [] signature() { return _signature; }
    
    public CompleteName completeName() { return new CompleteName(name(), authenticator(), signature()); }

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(CONTENT_OBJECT_ELEMENT);

		_name = new ContentName();
		_name.decode(decoder);
		
		_authenticator = new ContentAuthenticator();
		_authenticator.decode(decoder);
		
		_signature = decoder.readBinaryElement(SIGNATURE_ELEMENT);

		_content = decoder.readBinaryElement(CONTENT_ELEMENT);
		
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(CONTENT_OBJECT_ELEMENT);

		name().encode(encoder);
		authenticator().encode(encoder);

		// needs to handle null content
		encoder.writeElement(SIGNATURE_ELEMENT, _signature);

		// needs to handle null content
		encoder.writeElement(CONTENT_ELEMENT, _content);

		encoder.writeEndElement();   		
	}
	
	public boolean validate() { 
		// recursive?
		// null content ok
		return ((null != name()) && (null != authenticator()) && (null != signature()) &&
				(signature().length > 0));
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
		result = PRIME * result + ((_authenticator == null) ? 0 : _authenticator.hashCode());
		result = PRIME * result + Arrays.hashCode(_signature);
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
		if (!Arrays.equals(_signature, other._signature))
			return false;
		if (!Arrays.equals(_content, other._content))
			return false;
		return true;
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
	public static byte [] sign(ContentName name, 
					 		   Integer componentCount,
					 		   ContentAuthenticator authenticator,
					 		   String digestAlgorithm, 
					 		   PrivateKey signingKey) 
		throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
		
		// Build XML document
		byte [] signature = null;
		if ((null != componentCount) && 
				(componentCount > name.count())) {
			throw new SignatureException("Cannot sign more name components than given!");
		}
		
		// TODO DKS -- optimize signing and verification.
		
		// This handles a null component count.
		authenticator.nameComponentCount(componentCount);
		
		// Do we reflect the componentCount in the name we make
		// or how we sign it? i.e. do we build a new name with
		// just that many components, or does the encoder take
		// the component count as a property?
		ContentName signedName = null;
		if ((null == componentCount) || (componentCount == name.count()))
			signedName = name;
		else
			signedName = new ContentName(componentCount, name.components());
		try {
			signature = 
				SignatureHelper.sign(digestAlgorithm, 
									 new XMLEncodable[]{signedName, authenticator},
									 signingKey);
		} catch (XMLStreamException e) {
			Library.handleException("Exception encoding internally-generated XML name!", e);
			throw new SignatureException(e);
		}
		return signature;
	}
	
	public static byte [] sign(ContentName name, 
					 		   Integer componentCount,
					 		   ContentAuthenticator authenticator,
					 		   PrivateKey signingKey) 
			throws SignatureException, InvalidKeyException {
		try {
			return sign(name, componentCount, authenticator, DigestHelper.DEFAULT_DIGEST_ALGORITHM, signingKey);
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("Cannot find default digest algorithm: " + DigestHelper.DEFAULT_DIGEST_ALGORITHM);
			Library.warningStackTrace(e);
			throw new SignatureException(e);
		}
	}
	
	public boolean verify(PublicKey publicKey) 
					throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, 
								XMLStreamException, InterruptedException {
		return verify(this, true, publicKey);
	}
	
	public static boolean verify(ContentObject object, PublicKey publicKey) 
				throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, 
								XMLStreamException, InterruptedException {
		return verify(object, true, publicKey);
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
								 boolean verifySignature,
								 PublicKey publicKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException, InterruptedException {
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
			result = verifyContentSignature(object, publicKey);
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
	 * @throws InterruptedException 
	 */
	public static boolean verifyContentSignature(
			ContentObject object,
			PublicKey publicKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException, InterruptedException {
	
		if (null == publicKey) {
			// Get a copy of the public key.
			// Simple routers will install key manager that
			// will just pull from CCN.
			try {
				publicKey = 
					KeyManager.getKeyManager().getPublicKey(
						object.authenticator().publisherKeyID(),
						object.authenticator().keyLocator());

				if (null == publicKey) {
					throw new SignatureException("Cannot obtain public key to verify object: " + object.name() + ". Key locator: " + 
						object.authenticator().keyLocator());
				}
			} catch (IOException e) {
				throw new SignatureException("Cannot obtain public key to verify object: " + object.name() + ". Key locator: " + 
						object.authenticator().keyLocator() + " exception: " + e.getMessage(), e);				
			}
		}
		
		// Verify that this key matches the publisher ID
		// in the object. 
		// DKS TODO -- this doesn't work as well
		// when not a KEY identifier.
		
		// Format data for verification. If this
		// is a fragment, we will need to verify just the 
		// root. 
		ContentName verifyName = new ContentName(
							object.authenticator().nameComponentCount(),
							object.name().components());
	
		// Now, check the signature.
		boolean result = 
			SignatureHelper.verify(new XMLEncodable[]{verifyName, object.authenticator()}, 
							   	   object.signature(),
							   	   DigestHelper.DEFAULT_DIGEST_ALGORITHM,
							   	   publicKey);
		return result;
		
	}
}
