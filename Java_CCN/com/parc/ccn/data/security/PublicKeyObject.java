package com.parc.ccn.data.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.data.util.CCNNetworkObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.security.crypto.certificates.CryptoUtil;

/**
 * PublicKeys are Serializable. So we could use a subclass of CCNSerializableObject
 * to serialize them to CCN. But, we want to control their on-the-wire data format --
 * using their serialization interface, the output will contain metadata only
 * readable via the Java serialization interface. We want to write raw encoded
 * keys. So have to override the serialization behavior.
 * 
 * TODO what to do with LINKs that point to public keys.
 * @author smetters
 *
 */
public class PublicKeyObject extends CCNNetworkObject<PublicKey> {

	/**
	 * Write constructor. Doesn't save until you call save, in case you want to tweak things first.
<<<<<<< HEAD:Java_CCN/com/parc/ccn/data/security/PublicKeyObject.java
<<<<<<< HEAD:Java_CCN/com/parc/ccn/data/security/PublicKeyObject.java
=======
	 * @param type
>>>>>>> Reordered constructors and comments to make things clearer:Java_CCN/com/parc/ccn/data/security/PublicKeyObject.java
=======
>>>>>>> Allow specification of write publisher. Needs some normalizing with read
publisher.:Java_CCN/com/parc/ccn/data/security/PublicKeyObject.java
	 * @param name
	 * @param data
	 * @param library
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, PublicKey data, CCNLibrary library) throws IOException {
		super(PublicKey.class, name, data, library);
	}
	
	public PublicKeyObject(ContentName name, PublicKey data, PublisherPublicKeyDigest publisher, KeyLocator locator, CCNLibrary library) throws IOException {
		super(PublicKey.class, name, data, publisher, locator, library);
	}

	/**
	 * Read constructor -- opens existing object.
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ClassNotFoundException 
	 */
	public PublicKeyObject(ContentName name, PublisherPublicKeyDigest publisher, CCNLibrary library) throws IOException, XMLStreamException {
		super(PublicKey.class, name, publisher, library);
	}
	
	public PublicKeyObject(ContentName name, CCNLibrary library) throws IOException, XMLStreamException {
		super(PublicKey.class, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public PublicKeyObject(ContentObject firstBlock, CCNLibrary library) throws IOException, XMLStreamException {
		super(PublicKey.class, firstBlock, library);
	}
	
	/**
	 * Subclasses that need to write an object of a particular type can override.
	 * DKS TODO -- verify type on read, modulo that ENCR overrides everything.
	 * @return
	 */
	public ContentType contentType() { return ContentType.KEY; }

	public PublicKey publicKey() { return data(); }

	@Override
	protected PublicKey readObjectImpl(InputStream input) throws IOException, XMLStreamException {
		// assume we read until we have all the bytes, then decode.
		// Doesn't give us a good opportunity to check whether it's of type KEY. TODO
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte [] buf = new byte[1024];
		int byteCount = 0;
		byteCount = input.read(buf);
		while (byteCount > 0) {
			baos.write(buf, 0, byteCount);
			byteCount = input.read(buf);
		}
		try {
			return CryptoUtil.getPublicKey(baos.toByteArray());
		} catch (CertificateEncodingException e) {
			Library.logger().warning("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
			throw new IOException("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
		} catch (InvalidKeySpecException e) {
			Library.logger().warning("Cannot decode public key from block: " + ((CCNInputStream)input).currentBlockName() + "  " + e.getClass().getName() + ": " + e.getMessage());
			throw new IOException("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
			throw new IOException("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
		}
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws IOException,
			XMLStreamException {
		if (null == data()) {
			return;
		}
		byte [] encoded = data().getEncoded();
		output.write(encoded);
	}
}
