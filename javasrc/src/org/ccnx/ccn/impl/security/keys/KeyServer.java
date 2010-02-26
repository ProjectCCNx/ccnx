/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.impl.security.keys;

import java.io.IOException;
import java.security.PublicKey;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;

/**
 * This class publishes our keys, only if someone asks for them. It doesn't make its own
 * handle, unless really asked to.
 */
public class KeyServer {

	protected CCNHandle _handle = null;
	protected boolean _ourHandle = false;
	protected CCNFlowServer _keyServer = null;

	/**
	 * Constructor; uses existing handle.
	 * @param handle
	 */
	public KeyServer(CCNHandle handle) {
		_handle = handle;
	}

	public CCNHandle handle() { 
		return _handle; 
	}
	
	public synchronized void initialize() throws IOException {
		if (isInitialized()) {
			return;
		}
		// everyone can ask us for keys even if we have no repository
		// make a buffered server to return key data
		_keyServer = new CCNFlowServer(null, true, handle());
	}

	public synchronized boolean isInitialized() {
		return (null != _keyServer);
	}

	/**
	 * Publish a signed record for this key if one doesn't exist.
	 * (if it does exist, pulls it at least to our ccnd, and optionally
	 * makes it available). (TODO: decide what to do if it's published by someone
	 * else... need another option for that.) By default (if both saveType and flowController)
	 * are null, will publish to the internal key server. If a flow controller is specified
	 * (e.g. the internal key server), will publish using it, and will ignore any saveType set.
	 * Otherwise will use default network object publishing behavior based on the saveType.
	 * Dovetail it this way in order to have only one place where defaulting of signing
	 * information is done, to avoid ending up having different behaviors depending on the
	 * path by which you publish a key.
	 * 
	 * @param keyName the key's content name. Will add a version when saving if it doesn't
	 * 	have one already. If it does have a version, will use that one (see below for effect
	 * 	of version on the key locator). (Note that this is not
	 * 		standard behavior for savable network content, which needs its version explicitly
	 * 		set.)
	 * @param keyToPublish the public key to publish
	 * @param keyID the publisher id
	 * @param signingKeyID the key id of the key pair to sign with
	 * @param signingKeyLocator the key locator to use if we save this key (if it is not already published).
	 * 	If not specified, we look for the default locator for the signing key. If there is none,
	 * 	and we are signing with the same key we are publishing, we build a
	 * 	self-referential key locator, using the name passed in (versioned or not).
	 * @param saveType -- if we don't want to hand in a special-purpose flow controller, set saveType to RAW
	 *   or REPO to get standard publishing behavior.
	 * @param flowController flow controller to use. If non-null, saveType is ignored.
	 * @return the published information about this key, whether we published it or someone else had
	 * @throws IOException
	 */
	public PublicKeyObject serveKey(ContentName keyName, PublicKey keyToPublish,
						   			   PublisherPublicKeyDigest signingKeyID, 
						   			   KeyLocator signingKeyLocator) 

	throws IOException {

		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(keyToPublish);
		
		// Set up key server if it hasn't been set up already
		initialize();

		// See if this key is on the network
		ContentObject keySegment = CCNReader.isContentAvailable(keyName, ContentType.KEY, 
				keyDigest.digest(), null, SystemConfiguration.getDefaultTimeout(), _handle);
		if (null != keySegment) {
			Log.info("Key {0} is already available as {1}, not re-publishing.", keyName, keySegment.name());
			// A little dangerous, handing up our handle.
			return new PublicKeyObject(keySegment, _handle);
		}
		
		// Otherwise it's not published, so make an object to write it
		// with. We've already tried to pull it, so don't try here. Will
		// set publisher info below.
		return KeyManager.publishKey(keyName, keyToPublish, signingKeyID, signingKeyLocator, 
				_keyServer, null, null, _handle.keyManager());
	}
}
