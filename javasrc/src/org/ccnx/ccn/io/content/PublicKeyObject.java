/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.io.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.security.crypto.util.CryptoUtil;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


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
	 * @param name
	 * @param data
	 * @param handle
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, PublicKey data, CCNHandle handle) throws IOException {
		super(PublicKey.class, name, data, handle);
	}
	
	public PublicKeyObject(ContentName name, PublicKey data, PublisherPublicKeyDigest publisher, KeyLocator locator, CCNHandle handle) throws IOException {
		super(PublicKey.class, name, data, publisher, locator, handle);
	}

	/**
	 * Read constructor -- opens existing object.
	 * @param name
	 * @param handle
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ClassNotFoundException 
	 */
	public PublicKeyObject(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) throws IOException, XMLStreamException {
		super(PublicKey.class, name, publisher, handle);
	}
	
	public PublicKeyObject(ContentName name, CCNHandle handle) throws IOException, XMLStreamException {
		super(PublicKey.class, name, (PublisherPublicKeyDigest)null, handle);
	}
	
	public PublicKeyObject(ContentObject firstBlock, CCNHandle handle) throws IOException, XMLStreamException {
		super(PublicKey.class, firstBlock, handle);
	}
	
	/**
	 * Subclasses that need to write an object of a particular type can override.
	 * DKS TODO -- verify type on read, modulo that ENCR overrides everything.
	 * @return
	 */
	public ContentType contentType() { return ContentType.KEY; }

	public PublicKey publicKey() throws ContentNotReadyException, ContentGoneException { return data(); }

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
			Log.warning("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
			throw new IOException("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
		} catch (InvalidKeySpecException e) {
			Log.warning("Cannot decode public key from block: " + ((CCNInputStream)input).currentSegmentName() + "  " + e.getClass().getName() + ": " + e.getMessage());
			throw new IOException("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			Log.warning("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
			throw new IOException("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
		}
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws IOException,
			XMLStreamException {
		if (null == data())
			throw new ContentNotReadyException("No content available to save for object " + getBaseName());
		byte [] encoded = data().getEncoded();
		output.write(encoded);
	}
}
