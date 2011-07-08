/*
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
import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * A small class to handle publishing keys to CCNx, without requiring a repository
 * to be present. This class publishes our keys, only if someone asks for them. It doesn't make its own
 * handle, unless really asked to. 
 * TODO move to use the default handle, which requires making sure that the caller has the opportunity
 * to provide signing key information to us if they don't want to have it pulled from the default
 * handle.
 */
public class KeyServer {

	protected CCNHandle _handle = null;
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
	 * Start serving signed records for this key. 
	 * We used to check to see if this key was already on the network before publishing
	 * it. That was silly -- the interest mechanism would do this for us. Much better way
	 * to handle things is to just listen for interests for this key, and only publish it
	 * if someone asks (we really want to be the server of last resort, so we should answer
	 * late, but don't know how to do that). So we could make an interest handler and then
	 * publish the key on first interest (and assume it's cached thereafter?). If the cost
	 * of signing is negligible, we can be cheezier. Just publish to our key server, and
	 * if someone comes looking, they'll get it. If not....
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
	 * @return the published information about this key
	 * @throws IOException
	 */
	public PublicKeyObject serveKey(ContentName keyName, PublicKey keyToPublish,
						   			PublisherPublicKeyDigest signingKeyID, 
						   			KeyLocator signingKeyLocator) throws IOException {
		
		// Set up key server if it hasn't been set up already
		initialize();
		
		// Use the key publishing framework in KeyManager to format this key, but use our _keyServer
		// as the flow controller to serve it.
		_keyServer.addNameSpace(keyName);
		return KeyManager.publishKey(keyName, keyToPublish, signingKeyID, signingKeyLocator, 
				_keyServer, null, null, _handle.keyManager());
	}

	/**
	 * Handle cases where people query us with short prefixes. The flow controller
	 * will suppress duplicate or unnecessary registrations.
	 * @param keyPrefix
	 * @throws IOException 
	 */
	public void respondToKeyRequests(ContentName keyPrefix) throws IOException {
		// Set up key server if it hasn't been set up already
		initialize();
		
		_keyServer.addNameSpace(keyPrefix);
	}
}
