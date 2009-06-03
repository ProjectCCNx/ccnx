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
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.util.CCNNetworkObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNVersionedInputStream;
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

	public PublicKeyObject() throws ConfigurationException, IOException {
		super(PublicKey.class);
	}
	
	public PublicKeyObject(CCNLibrary library) {
		super(PublicKey.class, library);
	}
	
	public PublicKeyObject(ContentName name) throws XMLStreamException, IOException, ConfigurationException {
		this(name, CCNLibrary.open());
	}
	
	public PublicKeyObject(ContentName name, PublicKey publicKey, CCNLibrary library) {
		super(PublicKey.class, name, publicKey, library);
	}

	public PublicKeyObject(ContentName name, PublicKey publicKey) throws ConfigurationException, IOException {
		this(name, publicKey, CCNLibrary.open());
	}
	
	public PublicKeyObject(ContentObject content, CCNLibrary library) throws XMLStreamException, IOException {
		super(PublicKey.class, library);
		CCNVersionedInputStream is = new CCNVersionedInputStream(content, library);
		is.seek(0); // In case we start with something other than the first fragment.
		update(is);
	}
	
	/**
	 * Ambiguous. Are we supposed to pull this object based on its name,
	 *   or merely attach the name to the object which we will then construct
	 *   and save. Let's assume the former, and allow the name to be specified
	 *   for save() for the latter.
	 * @param type
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, 
			PublisherPublicKeyDigest publisher, CCNLibrary library) throws XMLStreamException, IOException {
		super(PublicKey.class, library);
		CCNVersionedInputStream is = new CCNVersionedInputStream(name, publisher, library);
		update(is);
	}
	
	/**
	 * Read constructor -- opens existing object.
	 * @param type
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, (PublisherPublicKeyDigest)null, library);
	}
	
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
			Library.logger().warning("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
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
