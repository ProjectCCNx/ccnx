/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.profiles.context;

import static org.ccnx.ccn.profiles.security.KeyProfile.KEY_NAME;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.ContentObject.SimpleVerifier;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;

/**
 * The ServiceDiscovery protocol aids in finding data about local (same-machine)
 * and nearby (network neighborhood) services. We start by retrieving service keys, using:
 *  /%C1.M.S.localhost/%C1.M.SRV/<servicename>/KEY/
 *  and getting as an answer an encoded, self-signed key published under the 
 *  KeyProfile.keyName() of that service's key under that prefix. 
 */
public class ServiceDiscoveryProfile implements CCNProfile {

	public static final String STRING_LOCALHOST = "localhost";

	public static final CommandMarker LOCALHOST_SCOPE =
		CommandMarker.commandMarker(CommandMarker.COMMAND_MARKER_SCOPE, STRING_LOCALHOST);

	public static final CommandMarker SERVICE_NAME_COMPONENT_MARKER = 
		CommandMarker.commandMarker(CommandMarker.MARKER_NAMESPACE, "SRV");

	public static final int SCOPE_COMPONENT = 0;
	public static final int SERVICE_MARKER_COMPONENT = 1;
	public static final int SERVICE_NAME_COMPONENT = 3;

	// Where should these go?
	public static final String CCND_SERVICE_NAME = "ccnd";
	public static final String REPOSITORY_SERVICE_NAME = "repository";
	
	public static final ContentName localhostScopeName() {
		// could make a static variable if we expect this to get called.
		return new ContentName(LOCALHOST_SCOPE);
	}

	public static ContentName localServiceName(String service) {
		return new ContentName(LOCALHOST_SCOPE, SERVICE_NAME_COMPONENT_MARKER, service);
	}

	public static String getLocalServiceName(ContentName nameWithServicePrefix) {

		// /%C1.M.S.localhost/%C1.M.SRV/<servicename>
		if (nameWithServicePrefix.count() < 3) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINER)) {
				Log.finer(Log.FAC_KEYS, "Cannot get local service name, {0} does not have enough components.",
						nameWithServicePrefix);
			}
		}

		if (!ServiceDiscoveryProfile.LOCALHOST_SCOPE.isMarker(nameWithServicePrefix.component(SCOPE_COMPONENT))) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINER)) {
				Log.finer(Log.FAC_KEYS, "Cannot get local service name, {0} does not begin with local service prefix {1}.",
						nameWithServicePrefix, ServiceDiscoveryProfile.LOCALHOST_SCOPE);
			}
			return null;
		}

		if (!SERVICE_NAME_COMPONENT_MARKER.isMarker(
				nameWithServicePrefix.component(SERVICE_MARKER_COMPONENT))) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINER)) {
				Log.finer(Log.FAC_KEYS, "Cannot get local service name, {0} does not contain a service name component {1}.",
						nameWithServicePrefix, 
						Component.printURI(nameWithServicePrefix.component(SERVICE_MARKER_COMPONENT)));
			}
			return null;			
		}

		byte [] serviceNameComponent = 
			nameWithServicePrefix.component(SERVICE_NAME_COMPONENT);
		return Component.printNative(serviceNameComponent);
	}

	/**
	 * We want to get a list of local implementors of this service and their keys; until the
	 * timeout. We use excludes to get all of them within the timeout. We get whole keys because
	 * they're usually a single object, so there is no reason not to load them. Rather than decoding
	 * them all, though, we just hand back the CO to the caller to decide what to do.
	 * 
	 * Start by taking a timeout to use as the inter-response interval; if we haven't heard anything
	 * in that long, we stop. 
	 * @param service
	 * @param serviceKey
	 * @param keyManager
	 * @throws IOException 
	 */
	public static ArrayList<ContentObject> getLocalServiceKeys(String service, long timeout, CCNHandle handle) throws IOException {

		ContentName serviceKeyName = new ContentName(localServiceName(service), KEY_NAME);

		// Construct an interest in anything below this that has the right form -- this prefix, a component
		// for the key id, and then a version and segments. Might be more expensive to apply the filters than
		// to throw things away and go around again...

		Interest theInterest = Interest.lower(serviceKeyName, 4, null);
		theInterest.answerOriginKind(0); // bypass the cache

		ArrayList<ContentObject> results = new ArrayList<ContentObject>();
		ContentObject theResult = null;
		int keyidComponent = serviceKeyName.count();

		ArrayList<byte[]> excludeList = new ArrayList<byte[]>();

		// We need a verifier that checks the match between publisher and public key.
		ContentVerifier verifier = new SimpleVerifier(null, handle.keyManager());

		do {

			if (excludeList.size() > 0) {
				//we have explicit excludes, add them to this interest
				byte [][] e = new byte[excludeList.size()][];
				excludeList.toArray(e);
				if (null != theInterest.exclude()) {
					theInterest.exclude().add(e);
				} else {
					Exclude theExclude = new Exclude(e);
					theInterest.exclude(theExclude);
				}
			}
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "getLocalServiceKeys, interest: {0}", theInterest);
			}

			theResult = handle.get(theInterest, timeout);

			if (null != theResult) {
				// Verify theResult (should go into handle.get)
				// Check to see if theResult matches criteria
				if (verifier.verify(theResult) && (ContentType.KEY == theResult.signedInfo().getType())) {
					// it's a key, remember it, and see if we can find any others.
					results.add(theResult);
					excludeList.add(theResult.name().component(keyidComponent));

					if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
						Log.info(Log.FAC_KEYS, "Got key for service {0}: {1}", service, theResult.name());
					}

				} else {
					// we don't want to exclude other things with this next component, but
					// do want to exclude this one; need digest exclude
				}
			}

		} while (null != theResult);

		return results;
	}

	/**
	 * Query for local service keys when we believe there is only a single instance of a
	 * service. Returns as soon as it gets a first response, or on timeout.
	 * @param service
	 * @param timeout
	 * @param handle
	 * @return
	 * @throws IOException
	 */
	public static PublicKeyObject getLocalServiceKey(String service, long timeout, CCNHandle handle) throws IOException {

		ContentName serviceKeyName = new ContentName(localServiceName(service), KEY_NAME);

		// Construct an interest in anything below this that has the right form -- this prefix, a component
		// for the key id, and then a version and segments. Might be more expensive to apply the filters than
		// to throw things away and go around again...

		Interest theInterest = Interest.lower(serviceKeyName, 4, null);
		theInterest.answerOriginKind(0); // bypass the cache

		ContentObject theResult = null;
		int keyidComponent = serviceKeyName.count();

		ArrayList<byte[]> excludeList = new ArrayList<byte[]>();

		// We need a verifier that checks the match between publisher and public key.
		ContentVerifier verifier = new SimpleVerifier(null, handle.keyManager());

		if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
			Log.info(Log.FAC_KEYS, "getLocalServiceKey, interest: {0}", theInterest);
		}
		
		long stopTime = System.currentTimeMillis() + timeout;

		do {

			if (excludeList.size() > 0) {
				//we have explicit excludes, add them to this interest
				byte [][] e = new byte[excludeList.size()][];
				excludeList.toArray(e);
				if (null != theInterest.exclude()) {
					theInterest.exclude().add(e);
				} else {
					Exclude theExclude = new Exclude(e);
					theInterest.exclude(theExclude);
				}
			}
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "getLocalServiceKeys, interest: {0}", theInterest);
			}

			theResult = handle.get(theInterest, timeout);

			if (null != theResult) {
				// Verify theResult (should go into handle.get)
				// Check to see if theResult matches criteria
				if (verifier.verify(theResult) && (ContentType.KEY == theResult.signedInfo().getType())) {
					// it's a key, remember it, and see if we can find any others.

					if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
						Log.info(Log.FAC_KEYS, "Got key for service {0}: {1}", service, theResult.name());
					}
					return new PublicKeyObject(theResult, handle);

				} else {
					// we don't want to exclude other things with this next component, but
					// do want to exclude this one; need digest exclude
					// for now, exclude the keyid, which is wrong TODO FIX
					excludeList.add(theResult.name().component(keyidComponent));
				}
			}

		} while (System.currentTimeMillis() < stopTime);

		return null;
	}

	public static void publishLocalServiceKey(String service, PublisherPublicKeyDigest serviceKey, KeyManager keyManager) throws InvalidKeyException, IOException {
		if (null == serviceKey) {
			serviceKey = keyManager.getDefaultKeyID();
		}

		ContentName serviceKeyPrefix = new ContentName(localServiceName(service), KEY_NAME);
		ContentName serviceKeyName = 
			KeyProfile.keyName(serviceKeyPrefix, serviceKey);

		// Need a way to override any stored key locator. Don't remember this key locator.
		keyManager.publishSelfSignedKey(serviceKeyName, serviceKey, false);
		// Register a filter for the interest we are likely to actually get. 
		keyManager.respondToKeyRequests(serviceKeyPrefix);
	}
}
