/*
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

package org.ccnx.ccn.io;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNSegmenter;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Simplest interface to putting data into CCN. Puts buffers of data, optionally
 * fragmenting them if need be. Useful for writing small test programs, however
 * more complex clients will usually prefer the higher-level
 * interfaces offered by CCNOutputStream and its subclasses, or 
 * CCNNetworkObject and its subclasses.
 */
public class CCNWriter {
	
	protected CCNSegmenter _segmenter;
	
	/**
	 * Construct a writer that will write content into a certain namespace. Names specified
	 * in calls to put should be descendants of this namespace.
	 * @param namespace The parent namespace this writer will write to as a file path-style string version
	 * 		of a name (for example /org/ccnx/test).
	 * @param handle The ccn context it will use to write, if null one will be created with CCNHandle#open().
	 * @throws MalformedContentNameStringException If namespace cannot be parsed. 
	 * @throws IOException If network initialization fails.
	 */
	public CCNWriter(String namespace, CCNHandle handle) throws MalformedContentNameStringException, IOException {
		this(ContentName.fromNative(namespace), handle);
	}

	/**
	 * Construct a writer that will write content into a certain namespace. Names specified
	 * in calls to put should be descendants of this namespace.
	 * @param namespace The parent namespace this writer will write to.
	 * @param handle The ccn context it will use to write, if null one will be created with CCNHandle#open().
	 * @throws MalformedContentNameStringException If namespace cannot be parsed. 
	 * @throws IOException If network initialization fails.
	 */
	public CCNWriter(ContentName namespace, CCNHandle handle) throws IOException {
		_segmenter = new CCNSegmenter(getFlowController(namespace, handle));
	}
	
	/**
	 * Construct a writer that will decide later what namespace is should write into
	 * @param handle The ccn context it will use to write, if null one will be created with CCNHandle#open().
	 * @throws IOException If network initialization fails.
	 */
	public CCNWriter(CCNHandle handle) throws IOException {
		this((ContentName)null, handle);
	}
	
	/**
	 * Create our flow controller. Allow subclass override.
	 * @param namespace
	 * @param handle
	 * @return
	 * @throws IOException 
	 */
	protected CCNFlowControl getFlowController(ContentName namespace, CCNHandle handle) throws IOException {
		if (null != namespace) {
			return new CCNFlowControl(namespace, handle);
		}
		return new CCNFlowControl(handle);
	}

	/**
	 * Low-level constructor used by implementation.
	 * @param flowControl Output buffer.
	 */
	protected CCNWriter(CCNFlowControl flowControl) {
		_segmenter = new CCNSegmenter(flowControl);
	}
	
	/**
	 * Publish a piece of named content signed by our default identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary.
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */
	public ContentName put(String name, String content) throws SignatureException, MalformedContentNameStringException, IOException {
		return put(ContentName.fromURI(name), content.getBytes(), null, null, null, null);
	}
	
	/**
	 * Publish a piece of named content signed by our default identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary.
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */
	public ContentName put(ContentName name, String content) throws SignatureException, MalformedContentNameStringException, IOException {
		return put(name, content.getBytes(), null, null, null, null);
	}

	/**
	 * Publish a piece of named content signed by our default identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary.
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */	
	public ContentName put(ContentName name, byte[] content) throws SignatureException, IOException {
		return put(name, content, null, null, null, null);
	}

	/**
	 * Publish a piece of named content signed by our default identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary.
	 * @param keys the keys with which to encrypt the content (if non-null)
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */	
	public ContentName put(ContentName name, byte[] content, ContentKeys keys) throws SignatureException, IOException {
		return put(name, content, null, null, null, keys);
	}

	/**
	 * Publish a piece of named content signed by our default identity in 
	 * response to an already-received Interest. The first block of Data
	 * will be written immediately, if name matches this Interest; otherwise
	 * both Data and Interest will be held pending later matches.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary.
	 * @param outstandingInterest an Interest, usually received by the handleInterests
	 * 	method of a CCNFilterListener. Only one responder should write data
	 * 	in response to a given Interest. The Interest should ideally have been
	 * 	received on the same CCNHandle used by this CCNWriter to write data.
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */	
	public ContentName put(ContentName name, byte[] content, Interest outstandingInterest) throws SignatureException, IOException {
		return put(name, content, null, null, null, null, null, outstandingInterest);
	}

	/**
	 * Publish a piece of named content signed by a particular identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary.
	 * @param publisher selects one of our identities to publish under
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */
	public ContentName put(ContentName name, byte[] content, 
							PublisherPublicKeyDigest publisher) throws SignatureException, IOException {
		return put(name, content, null, publisher, null, null);
	}
	
	/**
	 * Publish a piece of named content signed by our default identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary.
	 * @param freshnessSeconds how long the content should be considered valid in the cache.
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */	
	public ContentName put(ContentName name, String content, Integer freshnessSeconds) throws SignatureException, MalformedContentNameStringException, IOException {
		return put(name, content.getBytes(), null, null, freshnessSeconds, null);
	}
	
	/**
	 * Publish a piece of named content signed by a particular identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary.
	 * @param type type to specify for content. If null, DATA will be used. (see ContentType).
	 * @param publisher selects one of our identities to publish under
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */
	public ContentName put(ContentName name, byte[] content, 
							SignedInfo.ContentType type,
							PublisherPublicKeyDigest publisher,
							ContentKeys keys) throws SignatureException, IOException {
		return put(name, content, null, publisher, null, keys);
	}
	
	/**
	 * Publish a piece of named content signed by a particular identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary
	 * @param type type to specify for content. If null, DATA will be used. (see ContentType).
	 * @param publisher selects one of our identities to publish under
	 * @param freshnessSeconds how long the content should be considered valid in the cache.
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */
	public ContentName put(ContentName name, byte[] content, 
			SignedInfo.ContentType type,
			PublisherPublicKeyDigest publisher,
			Integer freshnessSeconds,
			ContentKeys keys) throws SignatureException, IOException {
		return put(name, content, type, publisher, null, freshnessSeconds, keys, null);
	}
	
	/**
	 * Publish a piece of named content signed by a particular identity.
	 * @param name name for content.
	 * @param content content to publish; will be fragmented if necessary
	 * @param type type to specify for content. If null, DATA will be used. (see ContentType).
	 * @param publisher selects one of our identities to publish under
	 * @param freshnessSeconds how long the content should be considered valid in the cache.
	 * @param outstandingInterest an interest this data is being written in response to. If the
	 * 	name matches the Interest, the first Data segment of the content will be written immediately.
	 *   Otherwise both Interest and Data will be cached.
	 * @throws SignatureException if there is a problem signing.
	 * @throws IOException if there is a problem writing data.
	 */
	public ContentName put(ContentName name, byte[] content, 
			SignedInfo.ContentType type,
			PublisherPublicKeyDigest publisher,
			KeyLocator locator, 
			Integer freshnessSeconds,
			ContentKeys keys,
			Interest outstandingInterest) throws SignatureException, IOException {
		try {
			addOutstandingInterest(outstandingInterest);
			_segmenter.getFlowControl().addNameSpace(name);
			_segmenter.getFlowControl().startWrite(name, Shape.STREAM); // Streams take care of this for the non-gone case.
			_segmenter.put(name, content, 0, ((null == content) ? 0 : content.length),
								  true, type, freshnessSeconds, locator, publisher, keys);
			_segmenter.getFlowControl().beforeClose();
			_segmenter.getFlowControl().afterClose();
			return name;
		} catch (InvalidKeyException e) {
			Log.info(Log.FAC_IO, "InvalidKeyException using key for publisher " + publisher + ".");
			throw new SignatureException(e);
		} catch (SignatureException e) {
			Log.info(Log.FAC_IO, "SignatureException using key for publisher " + publisher + ".");
			throw e;
		} catch (NoSuchAlgorithmException e) {
			Log.info(Log.FAC_IO, "NoSuchAlgorithmException using key for publisher " + publisher + ".");
			throw new SignatureException(e);
		} catch (InvalidAlgorithmParameterException e) {
			throw new IOException("Cannot encrypt content -- bad algorithm parameter!: " + e.getMessage());
		} 
	}

	/**
	 * Publishes a piece of content as a new version of a given name.
	 * @param name The (unversioned) name to publish under.
	 * @param content The content to publish, which will be segmented if necessary.
	 * @throws SignatureException if cannot sign
	 * @throws InvalidKeyException if cannot sign with specified key.
	 * @throws NoSuchAlgorithmException if algorithm specified does not exist.
	 * @throws IOException if cannot write data successfully.
	 * @throws InvalidAlgorithmParameterException if there is a problem with the cryptographic parameters.
	 */
	public ContentName newVersion(ContentName name,
								    byte[] content) throws SignatureException, IOException, InvalidKeyException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
		return newVersion(name, content, null, null);
	}

	/**
	 * Publishes a piece of content as a new version of a given name.
	 * @param name The (unversioned) name to publish under.
	 * @param content The content to publish, which will be segmented if necessary.
	 * @param publisher Specifies what key the content should be signed by.
	 * @throws SignatureException if cannot sign
	 * @throws InvalidKeyException if cannot sign with specified key.
	 * @throws NoSuchAlgorithmException if algorithm specified does not exist.
	 * @throws IOException if cannot write data successfully.
	 * @throws InvalidAlgorithmParameterException if there is a problem with the cryptographic parameters.
	 */
	public ContentName newVersion(
			ContentName name, 
			byte[] content,
			PublisherPublicKeyDigest publisher,
			ContentKeys keys) throws SignatureException, IOException, InvalidKeyException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
		return newVersion(name, content, null, null, publisher, keys);
	}
	
	/**
	 * Publishes a piece of content as a new version of a given name.
	 * @param name The (unversioned) name to publish under.
	 * @param content The content to publish, which will be segmented if necessary.
	 * @param type The type to publish the content as.
	 * @param locator The key locator to used to help consumers find the key used to sign.
	 * @param publisher Specifies what key the content should be signed by.
	 * @throws SignatureException if cannot sign
	 * @throws InvalidKeyException if cannot sign with specified key.
	 * @throws NoSuchAlgorithmException if algorithm specified does not exist.
	 * @throws IOException if cannot write data successfully.
	 * @throws InvalidAlgorithmParameterException if there is a problem with the cryptographic parameters.
	 */
	public ContentName newVersion(
			ContentName name, byte [] content,
			ContentType type,
			KeyLocator locator, PublisherPublicKeyDigest publisher,
			ContentKeys keys) throws SignatureException, 
			InvalidKeyException, NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException {
		
		// Construct new name
		// <name>/<VERSION_MARKER><version_number>
		ContentName versionedName = VersioningProfile.addVersion(name);

		// put result; segmenter will fill in defaults
		return put(versionedName, content, type, publisher, locator, null, keys, null);
	}
	
	/**
	 * Method for writers used by CCNFilterListeners to output a block
	 * in response to an Interest callback.
	 * We've received an Interest prior to setting up this writer. Use
	 * a method to push this Interest, rather than passing it in in the 
	 * constructor to make sure we have completed initializing the writer,
	 * and to limit the number of constructor types. (Similarly, we don't
	 * want to have to repeat each put() in versions that either do or don't
	 * take an Interest argument, or add potentially confusing Interest arguments
	 * to some/all of the put() methods that should usually be null. So
	 * start with this as the simplest option.)
	 * If the Interest doesn't match this writer's content, 
	 * no initial block will be output; the writer will wait for matching Interests prior
	 * to writing its blocks. The Interest will be cached in case future
	 * content written to this CCNWriter does match it.
	 * 
	 * @param outstandingInterest An interest received prior to constructing
	 *   this writer, ideally on the same CCNHandle that the stream is using
	 *   for output. Only one block should be put() in response
	 *   to this Interest; it is up to the caller to make sure that is the case.
	 */
	public void addOutstandingInterest(Interest outstandingInterest) {
		_segmenter.getFlowControl().handleInterest(outstandingInterest);
	}
	
	/**
	 * @return internal flow buffer.
	 */
	protected CCNFlowControl getFlowControl() {
		return _segmenter.getFlowControl();
	}
	
	/**
	 * Turn off flow control.
	 * Warning - calling this risks packet drops. It should only
	 * be used for tests or other special circumstances in which
	 * you "know what you are doing".
	 */
	public void disableFlowControl() {
		getFlowControl().disable();
	}
	
	/**
	 * Close this writer, ensuring all buffers are clear.
	 * @throws IOException If readers do not empty the buffer.
	 */
	public void close() throws IOException {
		_segmenter.getFlowControl().beforeClose();
		_segmenter.getFlowControl().afterClose();
	}
	
	/**
	 * Set the default timeout for this writer.
	 * Default is 10 seconds
	 * @param timeout in msec.
	 */
	public void setTimeout(int timeout) {
		getFlowControl().setTimeout(timeout);
	}
}
