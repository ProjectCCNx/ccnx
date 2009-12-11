/**
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.profiles.security;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.HashMap;
import java.util.Set;
import java.util.SortedSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.keys.NetworkKeyManager;
import org.ccnx.ccn.impl.security.keys.RepositoryKeyManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.Flosser;




/**
 * Creates and loads a set of simulated users. Will store them into
 * a repository if asked, and then will reload them from there the next time.
 * 
 * As long as you are careful to create your CCNHandle objects pointing at these
 * users' keystores, you can create data as any of these users.
 * @author smetters
 *
 */
public class TestUserData {
	
	/**
	 * Our users are named, in order, from this list, with 1 attached the first time, and 2 the
	 * second, and so on. This allows them to be enumerated without requiring them to be stored
	 * in a repo.
	 */
	public static final String [] USER_NAMES = {"Bob", "Alice", "Carol", "Mary", "Oswald", "Binky",
												"Spot", "Fred", "Eve", "Harold", "Barack", "Newt",
												"Allison", "Zed", "Walter", "Gizmo", "Nick", "Michael",
												"Nathan", "Rebecca", "Diana", "Jim", "Van", "Teresa",
												"Russ", "Tim", "Sharon", "Jessica", "Elaine", "Mark",
												"Weasel", "Ralph", "Junior", "Beki", "Darth", "Cauliflower",
												"Pico", "Eric", "Eric", "Eric", "Erik", "Richard"};
	
	protected HashMap<ContentName, KeyManager> _userData = new HashMap<ContentName,KeyManager>();
	protected HashMap<String,ContentName> _userFriendlyNames = new HashMap<String, ContentName>();	
	
		
	/**
	 * Read/write constructor. Makes extra new users if necessary. Expects names to come as above.
	 * Will incur timeouts the first time, as it checks for data first, and will take time
	 * to generate keys.
	 * TODO eventually use this "for real" with real passwords.
	 * @param userKeyStorePrefix
	 * @param userCount
	 * @param storeInRepo
	 * @param password
	 * @param handle
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 */
	public TestUserData(ContentName userKeyStorePrefix,
			int userCount, boolean storeInRepo, char [] password, CCNHandle handle) throws ConfigurationException, IOException, InvalidKeyException {
	
		ContentName childName = null;
		String friendlyName = null;
		KeyManager userKeyManager = null;
		
				
		for (int i=0; i < userCount; ++i) {
			friendlyName = USER_NAMES[i % USER_NAMES.length] + Integer.toString(1 + i/USER_NAMES.length);

			childName = ContentName.fromNative(userKeyStorePrefix, friendlyName);
			Log.info("Loading user: " + friendlyName + " from " + childName);
			
			if (storeInRepo) {
				// This only matters the first time through, when we save the user's data.
				// but it makes no difference in other cases anyway.
				userKeyManager = new RepositoryKeyManager(friendlyName, childName, null, password, handle);
			} else {
				userKeyManager = new NetworkKeyManager(friendlyName, childName, null, password, handle);
			}
			userKeyManager.initialize();
			_userData.put(childName, userKeyManager);
			_userFriendlyNames.put(friendlyName, childName);
			
		}
	}
	
	
	
	/**
	 * General read constructor. Expects names to be available in repo, and so enumerable.
	 * i.e. something must be there. Uses NetworkKeyManager to read them out, though.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 */
	public TestUserData(ContentName userKeystoreDataPrefix, char [] password, CCNHandle handle) throws IOException, ConfigurationException, InvalidKeyException {
		
		EnumeratedNameList userDirectory = new EnumeratedNameList(userKeystoreDataPrefix, handle);
		userDirectory.waitForData(); // will block
		
		SortedSet<ContentName> availableChildren = userDirectory.getChildren();
		if ((null == availableChildren) || (availableChildren.size() == 0)) {
			Log.warning("No available user keystore data in directory " + userKeystoreDataPrefix + ", giving up.");
			throw new IOException("No available user keystore data in directory " + userKeystoreDataPrefix + ", giving up.");
		}
		String friendlyName;
		ContentName childName;
		KeyManager userKeyManager;
		while (null != availableChildren) {
			for (ContentName child : availableChildren) {
				friendlyName = ContentName.componentPrintNative(child.lastComponent());
				if (null != getUser(friendlyName)) {
					Log.info("Already loaded data for user: " + friendlyName + " from name: " + _userFriendlyNames.get(friendlyName));
					continue;
				}
				childName = new ContentName(userKeystoreDataPrefix, child.lastComponent());
				Log.info("Loading user: " + friendlyName + " from " + childName);
				userKeyManager = new NetworkKeyManager(friendlyName, childName, null, password, handle);
				userKeyManager.initialize();
				_userData.put(childName, userKeyManager);
				_userFriendlyNames.put(friendlyName, childName);
				
			}
			availableChildren = null;
			if (userDirectory.hasNewData()) {
				// go around opportunistically
				availableChildren = userDirectory.getNewData();
			}
		}
	}
	
	public void publishUserKeysToRepository(ContentName userNamespace) throws IOException{
		for (String friendlyName: _userFriendlyNames.keySet()) {
			ContentName keyStoreName = _userFriendlyNames.get(friendlyName);
			KeyManager userKM = _userData.get(keyStoreName);
			ContentName keyName = ContentName.fromNative(userNamespace, friendlyName);
			PublicKeyObject pko = 
				new PublicKeyObject(keyName, userKM.getDefaultPublicKey(), 
									SaveType.REPOSITORY,
									getHandleForUser(friendlyName));
			pko.save(); 
		} 
	}
	
	public boolean hasUser(String friendlyName) {
		return _userFriendlyNames.containsKey(friendlyName);
	}
	
	public KeyManager getUser(String friendlyName) {
		return getUser(_userFriendlyNames.get(friendlyName));
	}
	
	public KeyManager getUser(ContentName userName) {
		if (null == userName)
			return null;
		return _userData.get(userName);
	}
	
	public CCNHandle getHandleForUser(String friendlyName) throws IOException {
		KeyManager km = getUser(friendlyName);
		if (null == km)
			return null;
		return CCNHandle.open(km);
	}

	public Set<String> friendlyNames() {
		return _userFriendlyNames.keySet();
	}

	public int count() {
		return _userData.size();
	}
	
	public static void usage() {
		System.out.println("usage: TestUserData [-r] <ccn uri for keystores> <user count> <password> (-r == use repo)");
	}
	
	/**
	 * Command-line driver to generate key data.
	 */
	public static void main(String [] args) {
		boolean useRepo = false;
		int arg = 0;
		if (args.length < 3) {
			usage();
			return;
		}
		if (args[arg].equals("-r")) {
			arg++;
			useRepo = true;
			if (args.length < 4) {
				usage();
				return;
			}
		}
		// Generate and write to repo
		TestUserData td;
		Flosser flosser = null;
		try {
			ContentName userNamespace = ContentName.fromURI(args[arg]);
			if (!useRepo) {
				flosser = new Flosser(userNamespace);
			}
			td = new TestUserData(userNamespace, Integer.valueOf(args[arg+1]),
									useRepo,
									args[arg+2].toCharArray(), CCNHandle.open());
			System.out.println("Generated/retrieved " + td.count() + " user keystores, for users : " + td.friendlyNames());

		} catch (Exception e) {
			System.out.println("Exception generating/reading user data: " + e);
			e.printStackTrace();
		} finally {
			if (null != flosser)
				flosser.stop();
		}
	}
}
