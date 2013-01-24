/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2010, 2013 Palo Alto Research Center, Inc.
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.crypto.util.CryptoUtil;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.KeyLocator.KeyLocatorType;
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

	/**
	 * Write constructor.
	 * @param name
	 * @param data
	 * @param handle
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, PublicKey data, SaveType saveType, CCNHandle handle) throws IOException {
		super(PublicKey.class, false, name, data, saveType, handle);
	}
	
	/**
	 * Write constructor.
	 * @param name
	 * @param data
	 * @param publisher
	 * @param locator
	 * @param handle
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, PublicKey data, SaveType saveType,
							PublisherPublicKeyDigest publisher, 
							KeyLocator locator, CCNHandle handle) throws IOException {
		super(PublicKey.class, false, name, data, saveType, publisher, locator, handle);
	}

	/**
	 * Read constructor.
	 * @param name
	 * @param handle
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(PublicKey.class, false, name, (PublisherPublicKeyDigest)null, handle);
	}
	
	/**
	 * Read constructor.
	 * @param name
	 * @param publisher
	 * @param handle
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public PublicKeyObject(ContentName name, PublisherPublicKeyDigest publisher, 
							CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(PublicKey.class, false, name, publisher, handle);
	}
	
	/**
	 * Read constructor if you already have a block.
	 * @param firstBlock
	 * @param handle
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
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

	/**
	 * Copy constructor.
	 */
	public PublicKeyObject(CCNNetworkObject<? extends PublicKey> other) {
		super(PublicKey.class, other);
	}

				
	@Override
	public ContentType contentType() { return ContentType.KEY; }

	public PublicKey publicKey() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
	
	public PublisherPublicKeyDigest publicKeyDigest() throws ContentNotReadyException, ContentGoneException, ErrorStateException {
		PublicKey key = publicKey();
		return new PublisherPublicKeyDigest(key);
	}

	@Override
	protected PublicKey readObjectImpl(InputStream input) throws ContentDecodingException, IOException {
		// assume we read until we have all the bytes, then decode.
		// Doesn't give us a good opportunity to check whether it's of type KEY. TODO
		try {
			byte [] contentBytes = DataUtils.getBytesFromStream(input);
			return CryptoUtil.getPublicKey(contentBytes);
		} catch (CertificateEncodingException e) {
			Log.severe("Cannot decode public key " + e.getClass().getName() + ": " + e.getMessage());
			Log.severe("Blockname : " + ((CCNInputStream)input).currentSegmentName());
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
	
	/**
	 * Many cryptographic providers don't implement equals() correctly.
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ErrorStateException 
	 */
	public boolean equalsKey(PublicKey otherKey) throws ContentNotReadyException, ContentGoneException, ErrorStateException {
		if (!available())
			throw new ContentNotReadyException("No data available to compare!");
		
		return equalsKey(publicKey(), otherKey);
	}
	
	public boolean equalsKey(PublicKeyObject otherKeyObject) throws ContentNotReadyException, ContentGoneException, ErrorStateException {
		return this.equalsKey(otherKeyObject.publicKey());
	}
	
	public static boolean equalsKey(PublicKey thisKey, PublicKey thatKey) {
		if (null == thisKey) {
			if (null == thatKey) {
				return true;
			}
			return false;
		} else if (null == thatKey) {
			return false;
		}
		if (thisKey.equals(thatKey))
			return true;
		// might be that the provider doesn't implement equals()
		return Arrays.equals(thisKey.getEncoded(), thatKey.getEncoded());
		
	}
	
	public boolean isSelfSigned() throws ContentNotReadyException, IOException {
		if (!isSaved()) {
			throw new ContentNotReadyException("No content retrieved -- cannot check if self-signed!");
		}
		return isSelfSigned(getVersionedName(), publicKey(), getPublisherKeyLocator());
	}
	
	public static boolean isSelfSigned(ContentName versionedKeyName, PublicKey theKey, KeyLocator publisherKeyLocator) {
		
		if (publisherKeyLocator.type() == KeyLocatorType.KEY) {
			if (!equalsKey(theKey, publisherKeyLocator.key())) {
				return false;
			} else {
				return true;
			}
		}
		if (publisherKeyLocator.type() == KeyLocatorType.NAME) {
			if (!publisherKeyLocator.name().name().isPrefixOf(versionedKeyName)) {
				return false;
			} else {
				return true;
			}
		}
		// For now, stop there
		return false;
	}
	
	public static boolean isSelfSigned(ContentName versionedKeyName, PublisherPublicKeyDigest keyDigest, KeyLocator publisherKeyLocator) {
		
		if (publisherKeyLocator.type() == KeyLocatorType.KEY) {
			if (!keyDigest.equals(new PublisherPublicKeyDigest(publisherKeyLocator.key()))) {
				return false;
			} else {
				return true;
			}
		}
		if (publisherKeyLocator.type() == KeyLocatorType.NAME) {
			if (!publisherKeyLocator.name().name().isPrefixOf(versionedKeyName)) {
				return false;
			} else {
				return true;
			}
		}
		// For now, stop there
		return false;
	}

}
