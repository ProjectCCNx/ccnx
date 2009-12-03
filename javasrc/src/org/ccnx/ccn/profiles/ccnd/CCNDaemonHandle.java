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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.XMLCodecFactory;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;

/**
 * Helper class to access CCND information.
 *
 */
public class CCNDaemonHandle {
	
	protected CCNHandle _handle;
	protected PublisherPublicKeyDigest _ccndId = null;
	
	public CCNDaemonHandle() {
	}
	
	public CCNDaemonHandle(CCNHandle handle)  throws CCNDaemonException {
		_handle = handle;
	}
		
	public CCNDaemonHandle(CCNHandle handle, PublisherPublicKeyDigest ccndId) throws CCNDaemonException {
		_handle = handle;
		if (null == ccndId) {
			_ccndId = this.getCCNDaemonId();
		} else {
			_ccndId = ccndId;
		}
	}
	
	public boolean ping() {
		try {
			ContentObject pingBack = pingIt();
			if (null == pingBack) {
				return false;
			} else {
				return true;
			}
		} catch (CCNDaemonException e) {
			String reason = e.getMessage();
			Log.info("CCNDeamonException (" + reason + ") during call to ping()");
			return false;
		}
	}
	
	public PublisherPublicKeyDigest getCCNDaemonId() throws CCNDaemonException {
		if (null != _ccndId) {
			return _ccndId;
		}
		
		ContentObject contented = pingIt();
		SignedInfo signed = contented.signedInfo();
		if (null == signed) {
			String msg = ("signedInfo in content object returned by daemon is null");
			Log.fine(msg);
			throw new CCNDaemonException(msg);
		}
		PublisherPublicKeyDigest keyed = signed.getPublisherKeyID();
		if (null == keyed) {
			String msg = ("publisherKeyID in signedInfo in content object returned by daemon is null");
			Log.fine(msg);
			throw new CCNDaemonException(msg);
		}
		_ccndId = keyed;
		return keyed;
	}/* getCCNDaemonId() */

	
	public static String idToString(PublisherPublicKeyDigest digest) {
		byte [] digested;
		digested = digest.digest();
		return ContentName.componentPrintURI(digested);
	}
	
	protected ContentObject pingIt() throws CCNDaemonException {
		Interest interested;
		ContentObject contented;
		final String ping = "ccnx:/ccnx/ping/";
		try {
			interested = new Interest(ping);
		} catch (MalformedContentNameStringException e) {
			String reason = e.getMessage();
			Log.warningStackTrace(e);
			String msg = ("Unexpected MalformedContentNameStringException in call creating: " + ping + " reason: " + reason);
			Log.fine(msg);
			throw new CCNDaemonException(msg);
		}
		interested.nonce(Interest.generateNonce());
		interested.scope(1);
		
		try {
			contented = _handle.get(interested, 4500);
		} catch (IOException e) {
			String reason = e.getMessage();
			Log.warningStackTrace(e);
			String msg = ("Unexpected IOException in call getting ping Interest reason: " + reason);
			Log.fine(msg);
			throw new CCNDaemonException(msg);
		}
		if (null == contented) {
			String msg = ("Fetch of content from ping uri failed due to timeout");
			Log.fine(msg);
			throw new CCNDaemonException(msg);
		}
		return contented;
	}
	
	protected byte[] sendIt(ContentName interestNamePrefix, byte[] payloadIn) throws CCNDaemonException {
		KeyManager km = _handle.keyManager();
		PublicKey publicKey = km.getDefaultPublicKey();
		PrivateKey privateKey = km.getDefaultSigningKey();
		PublisherPublicKeyDigest publicKeyDigest = km.getDefaultKeyID();


		/*
		 * We need to build a ContentObject that contains the faceBits as the content, a KeyLocator
		 * and SignedInfo.  First get our KeyManager from the handle.  From the KeyManager, get our 
		 * PublicKey.  From there, we create a KeyLocator. From there we make a SignedInfo. We then
		 * make a content object that has a null name.
		 */
		KeyLocator kl = new KeyLocator(publicKey);
		SignedInfo signedInfo = new SignedInfo(publicKeyDigest, SignedInfo.ContentType.DATA, kl, null, /* finalBlockID */ null);
		ContentObject contentOut = null;
		ContentName nullName = new ContentName();
		try {
			contentOut = new ContentObject(nullName, signedInfo, payloadIn, privateKey);
		} catch (InvalidKeyException e1) {
			String reason = e1.getMessage();
			String msg = ("Unexpected InvalidKeyException in call creating ContentObject reason: " + reason);
			Log.fine(msg);
			Log.warningStackTrace(e1);
			throw new CCNDaemonException(msg);
		} catch (SignatureException e1) {
			String reason = e1.getMessage();
			String msg = ("Unexpected SignatureException in call creating ContentObject reason: " + reason);
			Log.fine(msg);
			Log.warningStackTrace(e1);
			throw new CCNDaemonException(msg);
		}
		/*
		 * OK, we've got our content object, we now need to encode it into the binary form,
		 * since that's what ccnd is going to expect.
		 * TODO - This is broken for any other encoding besides ccnb format.
		 */
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		XMLEncoder encoder = XMLCodecFactory.getEncoder(BinaryXMLCodec.CODEC_NAME);
		try {
			encoder.beginEncoding(baos);
			contentOut.encode(encoder);
			encoder.endEncoding();	
		} catch (ContentEncodingException e) {
			String reason = e.getMessage();
			String msg = ("Unexpected ContentEncodingException in call encoding ContentObject to binary reason: " + reason);
			Log.fine(msg);
			Log.warningStackTrace(e);
			throw new CCNDaemonException(msg);
		}
		byte[] contentOutBits = baos.toByteArray();

		/*
		 * Add the contentOut bits to the name that's passed in.
		 */
		interestNamePrefix = ContentName.fromNative(interestNamePrefix, contentOutBits);
		Interest interested = new Interest(interestNamePrefix);
		interested.nonce(Interest.generateNonce());
		interested.scope(1);
		ContentObject contentIn;

		try {
			contentIn = _handle.get(interested, 1000);
		} catch (IOException e) {
			String reason = e.getMessage();
			String msg = ("Unexpected IOException in call getting FaceInstance return value, reason: " + reason);
			Log.fine(msg);
			Log.warningStackTrace(e);
			throw new CCNDaemonException(msg);
		}
		if (null == contentIn) {
			String msg = ("Fetch of content from face registration call failed due to timeout.");
			Log.fine(msg);
			throw new CCNDaemonException(msg);
		}

		byte[] payloadOut = contentIn.content();
		return payloadOut; 
	} /* protected byte[] sendIt(ContentName interestNamePrefix, byte[] payloadIn) throws CCNDaemonException */

}
