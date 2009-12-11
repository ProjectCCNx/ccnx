/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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

package	org.ccnx.ccn.profiles.ccnd;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;

/**
 * Helper class to access CCND information.
 *
 */
public class CCNDaemonHandle {
	
	protected CCNNetworkManager _manager;
	
	public CCNDaemonHandle() {
	}
	
	public CCNDaemonHandle(CCNNetworkManager manager)  throws CCNDaemonException {
		_manager = manager;
	}
		
	public CCNDaemonHandle(CCNHandle handle)  throws CCNDaemonException {
		_manager = handle.getNetworkManager();
	}
			
	public static String idToString(PublisherPublicKeyDigest digest) {
		byte [] digested;
		digested = digest.digest();
		return ContentName.componentPrintURI(digested);
	}
	
	public byte[] getBinaryEncoding(GenericXMLEncodable encodeMe) {
		// Do setup. Binary codec doesn't write a preamble or anything.
		// If allow to pick, text encoder would sometimes write random stuff...
//		ByteArrayOutputStream baos = new ByteArrayOutputStream();
//		XMLEncoder encoder = XMLCodecFactory.getEncoder(BinaryXMLCodec.CODEC_NAME);
//		try {
//			encoder.beginEncoding(baos);
//			encode(encoder);
//			encoder.endEncoding();	
//		} catch (ContentEncodingException e) {
//			String reason = e.getMessage();
//			Log.fine("Unexpected error encoding allocated FaceInstance.  reason: " + reason + "\n");
//			Log.warningStackTrace(e);
//			throw new IllegalArgumentException("Unexpected error encoding allocated FaceInstance.  reason: " + reason);
//		}
//		return baos.toByteArray();
		
		
		byte[] contentOutBits;
		try {
			contentOutBits = encodeMe.encode(BinaryXMLCodec.CODEC_NAME);
		} catch (ContentEncodingException e) {
			String reason = e.getMessage();
			Log.fine("Unexpected error encoding allocated FaceInstance.  reason: " + reason + "\n");
			Log.warningStackTrace(e);
			throw new IllegalArgumentException("Unexpected error encoding allocated FaceInstance.  reason: " + reason);
		}
		return contentOutBits;

	}
	
	protected byte[] sendIt(ContentName interestNamePrefix, GenericXMLEncodable encodeMe) throws CCNDaemonException {
		byte[] out = getBinaryEncoding(encodeMe);
		return sendIt(interestNamePrefix, out);
	}
	
	protected byte[] sendIt(ContentName interestNamePrefix, byte[] payloadIn) throws CCNDaemonException {
		ContentObject contentOut = null;
		KeyManager keyManager = _manager.getKeyManager();
		
// 		PublicKey publicKey = km.getDefaultPublicKey();
//		KeyLocator kl = new KeyLocator(km.getDefaultPublicKey());
//		PublisherPublicKeyDigest publicKeyDigest = km.getDefaultKeyID();
//		PrivateKey privateKey = km.getDefaultSigningKey();
//
//		/*
//		 * We need to build a ContentObject that contains the faceBits as the content, a KeyLocator
//		 * and SignedInfo.  First get our KeyManager from the handle.  From the KeyManager, get our 
//		 * PublicKey.  From there, we create a KeyLocator. From there we make a SignedInfo. We then
//		 * make a content object that has a null name.
//		 */
//		SignedInfo signedInfo = new SignedInfo(publicKeyDigest, SignedInfo.ContentType.DATA, kl, null, /* finalBlockID */ null);
//		ContentName nullName = new ContentName();
//		try {
//			contentOut = new ContentObject(nullName, signedInfo, payloadIn, privateKey);
//		} catch (InvalidKeyException e1) {
//			String reason = e1.getMessage();
//			String msg = ("Unexpected InvalidKeyException in call creating ContentObject reason: " + reason);
//			Log.fine(msg);
//			Log.warningStackTrace(e1);
//			throw new CCNDaemonException(msg);
//		} catch (SignatureException e1) {
//			String reason = e1.getMessage();
//			String msg = ("Unexpected SignatureException in call creating ContentObject reason: " + reason);
//			Log.fine(msg);
//			Log.warningStackTrace(e1);
//			throw new CCNDaemonException(msg);
//		}
//		Log.info("Original CO: {0}", contentOut);
		
		contentOut = ContentObject.buildContentObject(new ContentName(), SignedInfo.ContentType.DATA, payloadIn, 
														keyManager.getDefaultKeyID(), 
														new KeyLocator(keyManager.getDefaultPublicKey()), keyManager, 
														/* finalBlockID */ null);
		Log.finest("sendIt  CO for payLoadIn: {0}", contentOut);

		byte[] contentOutBits;
		try {
			contentOutBits = contentOut.encode(BinaryXMLCodec.CODEC_NAME);
		} catch (ContentEncodingException e) {
			String reason = e.getMessage();
			String msg = ("Unexpected ContentEncodingException, reason: " + reason);
			Log.fine(msg);
			Log.warningStackTrace(e);
			throw new CCNDaemonException(msg);
		}
		
		/*
		 * Add the contentOut bits to the name that's passed in.
		 */
		interestNamePrefix = ContentName.fromNative(interestNamePrefix, contentOutBits);
		Interest interested = new Interest(interestNamePrefix);
		interested.nonce(Interest.generateNonce());
		interested.scope(1);
		ContentObject contentIn;

		try {
			contentIn = _manager.get(interested, 1000);
		} catch (IOException e) {
			String reason = e.getMessage();
			String msg = ("Unexpected IOException in call getting FaceInstance return value, reason: " + reason);
			Log.fine(msg);
			Log.warningStackTrace(e);
			throw new CCNDaemonException(msg);
		} catch (InterruptedException e) {
			String reason = e.getMessage();
			String msg = ("Unexpected InterruptedException in call getting FaceInstance return value, reason: " + reason);
			Log.fine(msg);
			Log.warningStackTrace(e);
			throw new CCNDaemonException(msg);
		}
		if (null == contentIn) {
			String msg = ("Fetch of content from face or prefix registration call failed due to timeout.");
			Log.fine(msg);
			throw new CCNDaemonException(msg);
		}

		
		PublisherPublicKeyDigest sentID = contentIn.signedInfo().getPublisherKeyID();
		// TODO: This needs to be fixed once the KeyRepository is fixed to provide a KeyManager
		ContentVerifier verifyer = new ContentObject.SimpleVerifier(sentID, _manager.getKeyManager());
		if (!verifyer.verify(contentIn)) {
			String msg = ("CCNDIdGetter: Fetch of content reply from ping failed to verify.");
			Log.severe(msg);
			throw new CCNDaemonException(msg);
		}

		byte[] payloadOut = contentIn.content();
		return payloadOut; 
	} /* protected byte[] sendIt(ContentName interestNamePrefix, byte[] payloadIn) throws CCNDaemonException */

}
