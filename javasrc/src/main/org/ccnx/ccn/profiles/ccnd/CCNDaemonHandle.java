/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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

import static org.ccnx.ccn.protocol.ContentName.ROOT;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.CCNNetworkManager.RegisteredPrefix;
import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.Component;
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
	
	public CCNDaemonHandle() { }
	
	public CCNDaemonHandle(CCNNetworkManager manager)  throws CCNDaemonException {
		_manager = manager;
	}
		
	public CCNDaemonHandle(CCNHandle handle)  throws CCNDaemonException {
		_manager = handle.getNetworkManager();
	}
			
	public static String idToString(PublisherPublicKeyDigest digest) {
		byte [] digested;
		digested = digest.digest();
		return Component.printURI(digested);
	}
	
	/**
	 * Send the request for the prefix registration or deregistration to ccnd
	 * 
	 * @param interestNamePrefix
	 * @param encodeMe
	 * @param prefix contains callback for asynchronous requests
	 * @param wait if true wait for return content from ccnd
	 * @return data returned from ccnd in "no wait" case
	 * 
	 * @throws CCNDaemonException
	 */
	protected byte[] sendIt(ContentName interestNamePrefix, GenericXMLEncodable encodeMe, RegisteredPrefix prefix, boolean wait) throws CCNDaemonException {
		byte[] encoded;
		try {
			encoded = encodeMe.encode(BinaryXMLCodec.CODEC_NAME);
		} catch (ContentEncodingException e) {
			String reason = e.getMessage();
			Log.info("Unexpected error encoding encodeMe parameter.  reason: " + e.getMessage());
			throw new IllegalArgumentException("Unexpected error encoding encodeMe parameter.  reason: " + reason);
		}
		KeyManager keyManager = _manager.getKeyManager();
		ContentObject contentOut = ContentObject.buildContentObject(ROOT, SignedInfo.ContentType.DATA, 
														encoded, 
														keyManager.getDefaultKeyID(), 
														new KeyLocator(keyManager.getDefaultPublicKey()), keyManager, 
														/* finalBlockID */ null);
		byte[] contentOutBits;
		try {
			contentOutBits = contentOut.encode(BinaryXMLCodec.CODEC_NAME);
		} catch (ContentEncodingException e) {
			String msg = ("Unexpected ContentEncodingException, reason: " + e.getMessage());
			Log.info(msg);
			throw new CCNDaemonException(msg);
		}
		
		/*
		 * Add the contentOut bits to the name that's passed in.
		 */
		interestNamePrefix = new ContentName(interestNamePrefix, contentOutBits);
		Interest interested = new Interest(interestNamePrefix);
		interested.scope(1);
		ContentObject contentIn = null;

		try {
			if (wait) {
				contentIn = _manager.get(interested, SystemConfiguration.CCND_OP_TIMEOUT);
			} else {
				if (null != prefix) {
					_manager.expressInterest(this, interested, prefix);
				} else
					_manager.write(interested);
			}
		} catch (IOException e) {
			String msg = ("Unexpected IOException in call getting CCNDaemonHandle.sendIt return value, reason: " + e.getMessage());
			Log.info(msg);
			throw new CCNDaemonException(msg);
		} catch (InterruptedException e) {
			String msg = ("Unexpected InterruptedException in call getting CCNDaemonHandle.sendIt return value, reason: " + e.getMessage());
			Log.info(msg);
			throw new CCNDaemonException(msg);
		}
		
		if (wait) {
			if (null == contentIn) {
				String msg = ("Fetch of content from face or prefix registration call failed due to timeout.");
				Log.info(msg);
				throw new CCNDaemonException(msg);
			}
			
			PublisherPublicKeyDigest sentID = contentIn.signedInfo().getPublisherKeyID();
			ContentVerifier verifyer = new ContentObject.SimpleVerifier(sentID, _manager.getKeyManager());
			if (!verifyer.verify(contentIn)) {
				String msg = ("CCNDIdGetter: Fetch of content reply failed to verify.");
				Log.severe(msg);
				throw new CCNDaemonException(msg);
			}
			
			if (contentIn.isNACK()) {
				String msg = ("Received NACK in response to registration/unregistration request");
				Log.fine(msg);
				throw new CCNDaemonException(msg);  // FIX THIS TO GET THE CODE/MESSAGE from the StatusResponse
			}

			byte[] payloadOut = contentIn.content();
			return payloadOut;
		}
		return null;
	} /* protected byte[] sendIt(ContentName interestNamePrefix, byte[] payloadIn) throws CCNDaemonException */

}
