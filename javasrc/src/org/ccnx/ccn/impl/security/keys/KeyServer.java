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
import java.security.InvalidKeyException;
import java.security.PublicKey;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.DataUtils.Tuple;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.KeyName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.KeyLocator.KeyLocatorType;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;

/**
 * This class publishes our keys, only if someone asks for them. It doesn't make its own
 * handle, unless really asked to.
 */
public class KeyServer {

	protected CCNHandle _handle = null;
	protected boolean _ourHandle = false;
	protected CCNFlowServer _keyServer = null;
	protected PublicKeyCache _publicKeyCache = null;

	/**
	 * Constructor; uses existing handle.
	 * @param handle
	 */
	public KeyServer(PublicKeyCache publicKeyCache, CCNHandle handle) {
		_publicKeyCache = publicKeyCache;
		_handle = handle;
	}

	public CCNHandle handle() { return _handle; }
	
	public PublicKeyCache publicKeyCache() { return _publicKeyCache; }

	public synchronized void initializeKeyServer() throws IOException {
		if (keyServerIsInitialized()) {
			return;
		}
		// everyone can ask us for keys even if we have no repository
		// make a buffered server to return key data
		_keyServer = new CCNFlowServer(null, true, handle());
	}

	public synchronized boolean keyServerIsInitialized() {
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
	protected PublicKeyObject publishKey(ContentName keyName, PublicKey keyToPublish,
						   				PublisherPublicKeyDigest signingKeyID, KeyLocator signingKeyLocator,
						   				SaveType saveType, CCNFlowControl flowController) 

	throws IOException {

		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(keyToPublish);
		
		// Set up key server if it hasn't been set up already
		initializeKeyServer();

		// See if this key is on the network
		ContentObject keySegment = CCNReader.isContentAvailable(keyName, ContentType.KEY, 
				keyDigest.digest(), null, SystemConfiguration.getDefaultTimeout(), _handle);
		if (null != keySegment) {
			Log.info("Key {0} is already available as {1}, not re-publishing.", keyName, keySegment.name());
			// A little dangerous, handing up our handle.
			return new PublicKeyObject(keySegment, _handle);
		}
		
		// Now, finally; it's not published, so make an object to write it
		// with. We've already tried to pull it, so don't try here. Will
		// set publisher info below.
		
		// Need a key locator to stick in data entry for
		// locator. Could use key itself, but then would have
		// key both in the content for this item and in the
		// key locator, which is redundant. Use naming form
		// that allows for self-referential key names -- the
		// CCN equivalent of a "self-signed cert". Means that
		// we will refer to only the base key name and the publisher ID.
		if (null == signingKeyID) {
			signingKeyID = handle().keyManager().getDefaultKeyID();
		}

		if (null == signingKeyLocator) {
			KeyLocator existingLocator = handle().keyManager().getKeyLocator(signingKeyID);
			if ((existingLocator.type() == KeyLocatorType.KEY) && 
					(signingKeyID.equals(keyDigest))) {
				// Make a self-referential key locator. For now do not include the
				// version.
				existingLocator = new KeyLocator(new KeyName(keyName, signingKeyID));
				Log.finer("Overriding constructed key locator of type KEY, making self-referential locator {0}", existingLocator);
			}
			signingKeyLocator = existingLocator;
		}	
		
		// Here is where we get tricky. We might really want the key to be of a particular
		// version. In general, as we use the network objects to write versioned versioned stuff,
		// we might not be able to take the last component of a name, if versioned, as the version
		// to use to save -- might really want <name>/<version1>/<version2>. So unless we want to 
		// make that impossible to achieve, we need to not have the network objects take the 
		// name <name>/<version1> and save to <version1> (though they read from <version1> just
		// fine given the same). You always want to save to a new version, unless someone tells you
		// something different from the outside. 
		// Come up with a contorted option. If you want to publish <version>/<version> stuff, you
		// need to pass in the second version...
		
		CCNTime keyVersion = null; // do we force a version?
		Tuple<ContentName, byte []> nameAndVersion = VersioningProfile.cutTerminalVersion(keyName);

		PublicKeyObject keyObject = null;
		if ((null != flowController) || (null == saveType)) {
			// Either a flow controller given or neither flow controller nor saveType specified.
			// If a flow controller was specified, use that, otherwise use the internal _keyServer
			keyObject = new PublicKeyObject(nameAndVersion.first(), keyToPublish, 
											signingKeyID, signingKeyLocator, 
											((null != flowController) ? flowController : _keyServer));
		} else {
			// No flow controller given, use specified saveType.
			keyObject = new PublicKeyObject(nameAndVersion.first(), keyToPublish, saveType,
											signingKeyID, signingKeyLocator, _handle);
		}
		
		if (null != nameAndVersion.second()) {
			keyVersion = VersioningProfile.getVersionComponentAsTimestamp(nameAndVersion.second());
		}
		Log.info("publishKey: key not previously published, making new key object {0} with version {1} displayed as {2}", 
				keyObject.getVersionedName(), keyVersion, 
				((null != nameAndVersion.second()) ? ContentName.componentPrintURI(nameAndVersion.second()) : "<no version>"));

		// Eventually may want to find something already published and link to it, but be simple here.

		if (!keyObject.save(keyVersion)) {
			Log.info("Not saving key when we thought we needed to: desired key value {0}, have key value {1}, " +
					keyToPublish, new PublisherPublicKeyDigest(keyObject.publicKey()));
		} else {
			Log.info("Published key {0} to name {1} with key locator {2}.", keyToPublish, keyObject.getVersionedName(), signingKeyLocator);
		}
		publicKeyCache().remember(keyObject);
		return keyObject;
	}

	/**
	 * Publish a signed record for this key if one doesn't exist.
	 * (if it does exist, pulls it at least to our ccnd, and optionally
	 * makes it available).
	 * 
	 * @param keyName the key's content name. Will add a version when saving if it doesn't
	 * 	have one already. If it does have a version, will use that one (see below for effect
	 * 	of version on the key locator). (Note that this is not
	 * 		standard behavior for saveable network content, which needs its version explicitly
	 * 		set.)
	 * @param keyToPublish the public key to publish
	 * @param keyID the publisher id
	 * @param signingKeyID the key id of the key pair to sign with
	 * @param signingKeyLocator the key locator to use if we save this key (if it is not already published).
	 * 	If not specified, we look for the default locator for the signing key. If there is none,
	 * 	and we are signing with the same key we are publishing, we build a
	 * 	self-referential key locator, using the name passed in (versioned or not).
	 * @return the published information about this key, whether we published it or someone else had
	 * @throws IOException
	 */
	public PublicKeyObject publishKey(ContentName keyName, PublicKey keyToPublish,
			   PublisherPublicKeyDigest signingKeyID, KeyLocator signingKeyLocator) throws IOException {
		return publishKey(keyName, keyToPublish, signingKeyID, signingKeyLocator, null, _keyServer);
	}
	
	/**
	 * @return the published information about the key, whether we published it or it was already published
	 * @throws IOException if there is an error in publishing, or if we do not have a copy of the key to publish
	 */
	public PublicKeyObject publishKey(ContentName keyName, PublisherPublicKeyDigest keyToPublish, 
							  PublisherPublicKeyDigest signingKey, KeyLocator signingKeyLocator) 
	throws IOException {

		PublicKey theKey = publicKeyCache().getPublicKeyFromCache(keyToPublish);
		if (null == theKey) {
			throw new IOException("PublicKey corresponding to " + keyToPublish + " unknown, cannot publish!");
		}
		
		return publishKey(keyName, theKey, signingKey, signingKeyLocator);
	}

	/**
	 * Publish my public key to repository
	 * @param keyName content name of the public key to publish under (adds a version)
	 * @param keyToPublish public key digest
	 * @return the published information about this key, whether we published it or someone else had
	 * @throws IOException 
	 * @throws IOException
	 * @throws InvalidKeyException 
	 * @throws InvalidKeyException
	 * @throws ConfigurationException
	 */
	public PublicKeyObject publishKeyToRepository(ContentName keyName, 
									      		  PublisherPublicKeyDigest keyToPublish,
									      		  PublisherPublicKeyDigest signingKeyID, KeyLocator signingKeyLocator) throws IOException {

		// To avoid repeating work, we first see if this content is available on the network, then
		// if it's in a repository. That's because if it's not in a repository, we need to know if
		// it's on the network, and this way we save doing that work twice (as the repo-checking code
		// also needs to know if it's on the network).
		ContentObject availableContent = CCNReader.isContentAvailable(keyName, ContentType.KEY, keyToPublish.digest(), 
															null, SystemConfiguration.SHORT_TIMEOUT, _handle);

		if (null != availableContent) {
			// See if some repository has this key already
			if (null != CCNReader.isContentInRepository(availableContent, SystemConfiguration.SHORT_TIMEOUT, _handle)) {
				Log.info("publishKeyToRepository: key {0} is already in a repository; not re-publishing.", keyName);
			} else {

				// Otherwise, we just need to trick the repo into pulling it.
				ContentName streamName = SegmentationProfile.segmentRoot(availableContent.name());
				RepositoryFlowControl rfc = new RepositoryFlowControl(streamName, handle());
				// This will throw an IOException if there is no repository there to read it.
				rfc.startWrite(streamName, Shape.STREAM);
				// OK, once we've emitted the interest, we don't actually need that flow controller anymore.
				Log.info("Key {0} published to repository.", keyName);
				rfc.close();
			}
			return new PublicKeyObject(availableContent, _handle);
			
		} else {		
			// We need to write this content ourselves, nobody else has it.
			PublicKey theKey = publicKeyCache().getPublicKeyFromCache(keyToPublish);
			if (null == theKey) {
				throw new IOException("PublicKey corresponding to " + keyToPublish + " unknown, cannot publish to the repository!");
			}
			return publishKey(keyName, theKey, signingKeyID, signingKeyLocator, SaveType.REPOSITORY, null);
		}
	}
}
