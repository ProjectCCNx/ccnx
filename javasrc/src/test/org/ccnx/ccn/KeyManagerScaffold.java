/*
 * A CCNx library test.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PublicKey;

import org.ccnx.ccn.impl.security.keys.PublicKeyCache;
import org.ccnx.ccn.impl.security.keys.SecureKeyCache;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

public final class KeyManagerScaffold extends KeyManager {
	
	@Override
	public void initialize() throws InvalidKeyException, IOException {
	}

	@Override
	public boolean initialized() {
		return false;
	}

	@Override
	public void clearSavedConfigurationState() throws FileNotFoundException,
			IOException {		
	}

	@Override
	public PublisherPublicKeyDigest getDefaultKeyID() {
		return SecurityBaseNoCcnd.publishers[0];
	}

	@Override
	public PublicKeyCache getPublicKeyCache() {
		return null;
	}

	@Override
	public SecureKeyCache getSecureKeyCache() {
		return null;
	}

	@Override
	public void saveSecureKeyCache() throws FileNotFoundException, IOException {
	}

	@Override
	public void saveConfigurationState() throws FileNotFoundException,
			IOException {
	}

	@Override
	public URI getConfigurationDataURI() {
		return null;
	}

	@Override
	public Key getDefaultSigningKey() {
		return null;
	}

	@Override
	public PublicKey getDefaultPublicKey() {
		return null;
	}

	@Override
	public ContentName getDefaultKeyName(PublisherPublicKeyDigest keyID) {
		return null;
	}

	@Override
	public ContentName getDefaultKeyNamePrefix() {
		return null;
	}

	@Override
	public KeyLocator getKeyLocator(PublisherPublicKeyDigest publisherKeyID) {
		for (int i = 0; i < SecurityBaseNoCcnd.KEY_COUNT; i++) {
			if (publisherKeyID.equals(SecurityBaseNoCcnd.publishers[i])) {
				return SecurityBaseNoCcnd.keyLocs[i];
			}
		}
		return null;
	}

	@Override
	public KeyLocator getKeyLocator(Key signingKey) {
		return null;
	}

	@Override
	public boolean haveStoredKeyLocator(PublisherPublicKeyDigest keyID) {
		return false;
	}

	@Override
	public KeyLocator getStoredKeyLocator(PublisherPublicKeyDigest keyID) {
		return null;
	}

	@Override
	public void clearStoredKeyLocator(PublisherPublicKeyDigest keyID) {
	}

	@Override
	public void setKeyLocator(PublisherPublicKeyDigest publisherKeyID,
			KeyLocator keyLocator) {
	}

	@Override
	public PublicKey getPublicKey(PublisherPublicKeyDigest publisher) {
		return null;
	}

	@Override
	public PublisherPublicKeyDigest getPublisherKeyID(Key signingKey) {
		return null;
	}

	@Override
	public Key getSigningKey(PublisherPublicKeyDigest publisherKeyID) {
		for (int i = 0; i < SecurityBaseNoCcnd.KEY_COUNT; i++) {
			if (publisherKeyID.equals(SecurityBaseNoCcnd.publishers[i])) {
				return SecurityBaseNoCcnd.pairs[i].getPrivate();
			}
		}
		return null;
	}

	@Override
	public Key[] getSigningKeys() {
		return null;
	}

	@Override
	public PublisherPublicKeyDigest[] getAvailableIdentities() {
		return null;
	}

	@Override
	public CCNTime getKeyVersion(PublisherPublicKeyDigest keyID) {
		return null;
	}

	@Override
	public Key getVerificationKey(PublisherPublicKeyDigest publisherKeyID,
			KeyLocator keyLocator, String type, String fileName,
			String password, long timeout) throws IOException {
		return null;
	}

	@Override
	public void saveVerificationKey(Key key, ContentName name, String type,
			String fileName, String password) throws IOException {		
	}

	@Override
	public void removeVerificationKey(Key key, String type, String fileName)
			throws IOException {		
	}

	@Override
	public PublicKeyObject getPublicKeyObject(
			PublisherPublicKeyDigest desiredKeyID, KeyLocator locator,
			long timeout) throws IOException {
		return null;
	}

	@Override
	public PublicKeyObject publishKey(ContentName keyName,
			PublicKey keyToPublish, PublisherPublicKeyDigest signingKeyID,
			KeyLocator signingKeyLocator, boolean learnKeyLocator)
			throws InvalidKeyException, IOException {
		return null;
	}

	@Override
	public PublicKeyObject publishKeyToRepository(ContentName keyName,
			PublisherPublicKeyDigest keyToPublish, long timeToWaitForPreexisting)
			throws InvalidKeyException, IOException {
		return null;
	}

	@Override
	public PublicKeyObject publishSelfSignedKeyToRepository(
			ContentName keyName, PublicKey theKey,
			PublisherPublicKeyDigest keyToPublish, long timeToWaitForPreexisting)
			throws InvalidKeyException, IOException {
		return null;
	}

	@Override
	public AccessControlManager getAccessControlManagerForName(
			ContentName contentName) {
		return null;
	}

	@Override
	public void rememberAccessControlManager(AccessControlManager acm) {		
	}

}
