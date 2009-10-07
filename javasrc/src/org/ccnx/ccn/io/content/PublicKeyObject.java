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
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.security.crypto.util.CryptoUtil;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * A CCNNetworkObject subclass specialized for reading and writing PublicKeys.
 * PublicKeys are Serializable. So we could use a subclass of CCNSerializableObject
 * to serialize them to CCN. But, we want to control their on-the-wire data format --
 * using their serialization interface, the output will contain metadata only
 * readable via the Java serialization interface. We want to write raw encoded
 * keys. So have to override the serialization behavior.
 * 
 * This class also serves as an example of how to write a CCNNetworkObject
 * subclass that needs to implement its own serialization.
 */
public class PublicKeyObject extends CCNNetworkObject<PublicKey> {

	public PublicKeyObject(ContentName name, PublicKey data, CCNHandle handle) throws IOException {
		super(PublicKey.class, false, name, data, handle);
	}
	
	public PublicKeyObject(ContentName name, PublicKey data, PublisherPublicKeyDigest publisher, KeyLocator locator, CCNHandle handle) throws IOException {
		super(PublicKey.class, false, name, data, publisher, locator, handle);
	}

	public PublicKeyObject(ContentName name, PublisherPublicKeyDigest publisher, 
							CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(PublicKey.class, false, name, publisher, handle);
	}
	
	public PublicKeyObject(ContentName name, CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(PublicKey.class, false, name, (PublisherPublicKeyDigest)null, handle);
	}
	
	public PublicKeyObject(ContentObject firstBlock, CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(PublicKey.class, false, firstBlock, handle);
	}
	
	/**
	 * Internal constructor used by low-level network operations. Don't use unless you know what 
	 * you are doing.
	 * @param name name under which to save data
	 * @param data data to save when save() is called; or null if the next call will be updateInBackground()
	 * @param publisher key (identity) to use to sign the content (null for default)
	 * @param locator key locator to use to tell people where to find our key, should match publisher, (null for default for key)
	 * @param flowControl flow controller to use for network output
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, PublicKey data, 
			PublisherPublicKeyDigest publisher, 
			KeyLocator locator,
			CCNFlowControl flowControl) throws IOException {
		super(PublicKey.class, false, name, data, publisher, locator, flowControl);
	}
		
	/**
	 * Internal constructor used by low-level network operations. Don't use unless you know what 
	 * you are doing.
	 * @param name name under which to save data
	 * @param data data to save when save() is called; or null if the next call will be updateInBackground()
	 * @param publisher key (identity) to use to sign the content (null for default)
	 * @param locator key locator to use to tell people where to find our key, should match publisher, (null for default for key)
	 * @param flowControl flow controller to use for network output
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, PublisherPublicKeyDigest publisher,
						   CCNFlowControl flowControl) throws ContentDecodingException, IOException {
		super(PublicKey.class, false, name, publisher, flowControl);
	}

	/**
	 * Internal constructor used by low-level network operations. Don't use unless you know what 
	 * you are doing.
	 * @param name name under which to save data
	 * @param data data to save when save() is called; or null if the next call will be updateInBackground()
	 * @param publisher key (identity) to use to sign the content (null for default)
	 * @param locator key locator to use to tell people where to find our key, should match publisher, (null for default for key)
	 * @param flowControl flow controller to use for network output
	 * @throws IOException
	 */
	public PublicKeyObject(ContentObject firstSegment, CCNFlowControl flowControl) 
					throws ContentDecodingException, IOException {
		super(PublicKey.class, false, firstSegment, flowControl);
	}

				
	@Override
	public ContentType contentType() { return ContentType.KEY; }

	public PublicKey publicKey() throws ContentNotReadyException, ContentGoneException { return data(); }

	@Override
	protected PublicKey readObjectImpl(InputStream input) throws ContentDecodingException, IOException {
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
		}
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws ContentEncodingException, IOException {
		if (null == data())
			throw new ContentNotReadyException("No content available to save for object " + getBaseName());
		byte [] encoded = data().getEncoded();
		output.write(encoded);
	}
}
