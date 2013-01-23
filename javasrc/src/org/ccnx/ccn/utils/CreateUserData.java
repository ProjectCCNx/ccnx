/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.utils;

import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.HashMap;
import java.util.Set;
import java.util.SortedSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;
import org.ccnx.ccn.impl.security.keys.NetworkKeyManager;
import org.ccnx.ccn.impl.security.keys.RepositoryKeyManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.Flosser;




/**
 * The standard CCNx mechanisms try to generate a keystore for each user that uses
 * them. This tool allows you to make keys for additional users, on the command line
 * or programmatically. It is primarily useful for tests, and for generating credentials
 * that will be prepared offline and then given to their intended users.
 * 
 * Creates and loads a set of simulated users. Will store them into
 * a repository if asked, or to files and then will reload them from there the next time.
 * 
 * As long as you are careful to create your CCNHandle objects pointing at these
 * users' keystores, you can create data as any of these users.
 */
public class CreateUserData {
	
	/**
	 * Our users are named, in order, from this list, with 1 attached the first time, and 2 the
	 * second, and so on. This allows them to be enumerated without requiring them to be stored
	 * in a repo.
	 */
	public static final String [] USER_NAMES = {"Alice", "Bob", "Carol", "Dave", "Oswald", "Binky",
												"Spot", "Fred", "Eve", "Harold", "Barack", "Newt",
												"Allison", "Zed", "Walter", "Gizmo", "Nick", "Michael",
												"Nathan", "Rebecca", "Diana", "Jim", "Van", "Teresa",
												"Russ", "Tim", "Sharon", "Jessica", "Elaine", "Mark",
												"Weasel", "Ralph", "Junior", "Beki", "Darth", "Cauliflower",
												"Pico", "Eric", "Eric", "Eric", "Erik", "Richard"};
	
	protected HashMap<String, ContentName> _userContentNames = new HashMap<String,ContentName>();
	protected HashMap<String, File> _userKeystoreDirectories = new HashMap<String,File>();
	protected HashMap<String, BasicKeyManager> _userKeyManagers = new HashMap<String, BasicKeyManager>();
	
		
	/**
	 * Read/write constructor to write keystores as CCN data. 
	 * Makes extra new users if necessary. Expects names to come as above.
	 * Will incur timeouts the first time, as it checks for data first, and will take time
	 * to generate keys.
	 * TODO eventually use this "for real" with real passwords.
	 * @param userKeyStorePrefix
	 * @param userNames list of user names to use, if null uses built-in list
	 * @param userCount
	 * @param storeInRepo
	 * @param password
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 */
	public CreateUserData(ContentName userKeyStorePrefix, String [] userNames,
			int userCount, boolean storeInRepo, char [] password) throws ConfigurationException, IOException, InvalidKeyException {
	
		ContentName childName = null;
		String friendlyName = null;
		BasicKeyManager userKeyManager = null;
		if (null == userNames) {
			userNames = USER_NAMES;
		}
				
		for (int i=0; i < userCount; ++i) {
			friendlyName = userNames[i % userNames.length];
			if (i >= userNames.length) {
				friendlyName += Integer.toString(1 + i/userNames.length);
			}

			childName = new ContentName(userKeyStorePrefix, friendlyName);
			Log.info("Loading user: " + friendlyName + " from " + childName);
			
			if (storeInRepo) {
				// This only matters the first time through, when we save the user's data.
				// but it makes no difference in other cases anyway.
				userKeyManager = new RepositoryKeyManager(friendlyName, childName, null, password);
			} else {
				userKeyManager = new NetworkKeyManager(friendlyName, childName, null, password);
			}
			userKeyManager.initialize();
			_userContentNames.put(friendlyName, childName);
			_userKeyManagers.put(friendlyName, userKeyManager);
			
		}
	}
	
	/**
	 * Backwards compatibility constructor
	 */
	public CreateUserData(ContentName userKeyStorePrefix, 
			int userCount, boolean storeInRepo, char [] password) throws ConfigurationException, IOException, InvalidKeyException {
		this(userKeyStorePrefix, null, userCount, storeInRepo, password);
	}
	
	/**
	 * General read constructor. Expects names to be available in repo, and so enumerable.
	 * i.e. something must be there. Uses NetworkKeyManager to read them out, though.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 */
	public CreateUserData(ContentName userKeystoreDataPrefix, char [] password, CCNHandle handle) throws IOException, ConfigurationException, InvalidKeyException {
		
		EnumeratedNameList userDirectory = new EnumeratedNameList(userKeystoreDataPrefix, handle);
		userDirectory.waitForChildren(); // will block
		
		SortedSet<ContentName> availableChildren = userDirectory.getChildren();
		if ((null == availableChildren) || (availableChildren.size() == 0)) {
			Log.warning("No available user keystore data in directory " + userKeystoreDataPrefix + ", giving up.");
			throw new IOException("No available user keystore data in directory " + userKeystoreDataPrefix + ", giving up.");
		}
		String friendlyName;
		ContentName childName;
		BasicKeyManager userKeyManager;
		while (null != availableChildren) {
			for (ContentName child : availableChildren) {
				friendlyName = Component.printNative(child.lastComponent());
				if (null != getUser(friendlyName)) {
					Log.info("Already loaded data for user: " + friendlyName + " from name: " + _userContentNames.get(friendlyName));
					continue;
				}
				childName = new ContentName(userKeystoreDataPrefix, child.lastComponent());
				Log.info("Loading user: " + friendlyName + " from " + childName);
				userKeyManager = new NetworkKeyManager(friendlyName, childName, null, password);
				userKeyManager.initialize();
				_userContentNames.put(friendlyName, childName);
				_userKeyManagers.put(friendlyName, userKeyManager);
				
			}
			availableChildren = null;
			if (userDirectory.hasNewData()) {
				// go around opportunistically
				availableChildren = userDirectory.getNewData();
			}
		}
	}
	
	/**
	 * Read/write constructor to write keystores as files. 
	 * Makes extra new users if necessary. Expects names to come as above.
	 * Will incur timeouts the first time, as it checks for data first, and will take time
	 * to generate keys.
	 * TODO eventually use this "for real" with real passwords.
	 * @param userKeystoreDirectory a directory under which to put each user's information;
	 * 	segregated into subdirectories by user name, e.g. <userKeystoreDirectory>/<userName>.
	 * @param userNames list of user names to use, if null uses built-in list
	 * @param userCount
	 * @param storeInRepo
	 * @param password
	 * @param handle
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 * @throws InvalidKeyException 
	 */
	public CreateUserData(File userKeystoreDirectory, String [] userNames,
						int userCount, char [] password,
						boolean clearSavedState) throws ConfigurationException, IOException, InvalidKeyException {
	
		String friendlyName = null;
		BasicKeyManager userKeyManager = null;
		File userDirectory = null;
		File userKeystoreFile = null;
		
		if (!userKeystoreDirectory.exists()) {
			userKeystoreDirectory.mkdirs();
		} else if (!userKeystoreDirectory.isDirectory()) {
			Log.severe("Specified path {0} must be a directory!", userKeystoreDirectory);
			throw new IllegalArgumentException("Specified path " + userKeystoreDirectory + " must be a directory!");
		}
		
		for (int i=0; i < userCount; ++i) {
			friendlyName = userNames[i % userNames.length];
			if (i >= userNames.length) {
				friendlyName += Integer.toString(1 + i/userNames.length);
			}
			
			userDirectory = new File(userKeystoreDirectory, friendlyName);
			if (!userDirectory.exists()) {
				userDirectory.mkdirs();
			}
			
			userKeystoreFile = new File(userDirectory, UserConfiguration.keystoreFileName());
			if (userKeystoreFile.exists()) {
				Log.info("Loading user: " + friendlyName + " from " + userKeystoreFile.getAbsolutePath());
			} else {
				Log.info("Creating user's: " + friendlyName + " keystore in file " + userKeystoreFile.getAbsolutePath());
			}
			
			userKeyManager = new BasicKeyManager(friendlyName, userDirectory.getAbsolutePath(), 
												null, null, 
												null, null, password);
			if (clearSavedState) {
				userKeyManager.clearSavedConfigurationState();
			}
			userKeyManager.initialize();
			_userKeyManagers.put(friendlyName, userKeyManager);
			_userKeystoreDirectories.put(friendlyName, userDirectory.getAbsoluteFile());

		}
	}
	
	public CreateUserData(File userKeystoreDirectory, String [] userNames,
			int userCount, char [] password) throws ConfigurationException, IOException, InvalidKeyException {
		this(userKeystoreDirectory, userNames, userCount, password, false);
	}

	
	/**
	 * For writing apps that run "as" a particular user. 
	 * @param userKeystoreDirectory This is the path to this particular user's keystore directory,
	 *   not the path above it where a bunch of users might have been generated. Assumes keystore
	 *   file has default name in that directory. If you give it a path that doesn't exist, it
	 *   takes it as a directory and makes a keystore there making the parent directories if necessary.
	 * @return
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 */
	public static KeyManager loadKeystoreFile(File userKeystoreFileOrDirectory, String friendlyName, char [] password) throws ConfigurationException, IOException, InvalidKeyException {

		// Could actually easily generate this... probably should do that instead.
		if (!userKeystoreFileOrDirectory.exists())  {
			userKeystoreFileOrDirectory.mkdirs();
		}

		File userKeystoreFile = userKeystoreFileOrDirectory.isDirectory() ? 
				new File(userKeystoreFileOrDirectory, UserConfiguration.keystoreFileName()) :
				userKeystoreFileOrDirectory;

		if (userKeystoreFile.exists()) {
			Log.info("Loading user: from " + userKeystoreFile.getAbsolutePath());
		} else {
			Log.info("Creating user's: keystore in file " + userKeystoreFile.getAbsolutePath());
		}

		KeyManager userKeyManager = new BasicKeyManager(friendlyName, userKeystoreFile.getParent(), 
				null, null, null, null, password);
		userKeyManager.initialize();
		return userKeyManager;

	}
	
	/**
	 * Load a set of user data from an existing generated set of file directories. Don't
	 * force user to know names or count, enumerate them.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 */
	public static CreateUserData readUserDataDirectory(File userDirectory, char [] keystorePassword) throws ConfigurationException, IOException, InvalidKeyException {
		
		if (!userDirectory.exists()) {
			Log.warning("Asked to read data from user directory {0}, but it does not exist!", userDirectory);
			return null;
		}
		
		if (!userDirectory.isDirectory()) {
			Log.warning("Asked to read data from user directory {0}, but it isn't a directory!", userDirectory);
			return null;
		}
		// Right now assume everything below here is a directory.
		String [] children = userDirectory.list();
		
		return new CreateUserData(userDirectory, children, children.length, keystorePassword);
	}
	
	public void closeAll() {
		for (BasicKeyManager km : _userKeyManagers.values()) {
			if (null != km) {
				km.close();
			}
		}
	}
	
	public void publishUserKeysToRepository() throws IOException{
		for (String friendlyName: _userKeyManagers.keySet()) {
			System.out.println(friendlyName);
			CCNHandle userHandle = getHandleForUser(friendlyName);
			try {
				userHandle.keyManager().publishKeyToRepository();
				ContentName keyName = userHandle.keyManager().getDefaultKeyNamePrefix();
				keyName = keyName.cut(keyName.count()-1);
				// Won't publish if it's already there.
				userHandle.keyManager().publishKeyToRepository(keyName, null);
			} catch (Exception e) {
				e.printStackTrace();
			}
		} 
	}
	
	/**
	 * Publishes self-referential key objects under user namespace prefix.
	 * @param userNamespace
	 * @return
	 * @throws IOException
	 * @throws InvalidKeyException 
	 */
	public PublicKeyObject [] publishUserKeysToRepository(ContentName userNamespace) throws IOException, InvalidKeyException{
		PublicKeyObject [] results = new PublicKeyObject[_userKeyManagers.size()];
		int i=0;
		for (String friendlyName: _userKeyManagers.keySet()) {
			CCNHandle userHandle = getHandleForUser(friendlyName);
			ContentName keyName = new ContentName(userNamespace, friendlyName);
			results[i++] = userHandle.keyManager().publishSelfSignedKeyToRepository(
					keyName, userHandle.keyManager().getDefaultPublicKey(),
					userHandle.keyManager().getDefaultKeyID(), SystemConfiguration.getDefaultTimeout());
		} 
		return results;
	}
	
	public PublicKeyObject [] publishUserKeysToRepositorySetLocators(ContentName userNamespace) throws InvalidKeyException, IOException {
		PublicKeyObject [] results = publishUserKeysToRepository(userNamespace);
		
		int i=0;
		for (String friendlyName: _userKeyManagers.keySet()) {
			CCNHandle userHandle = getHandleForUser(friendlyName);
			userHandle.keyManager().setKeyLocator(null, results[i].getPublisherKeyLocator());
			i++;
		}
		return results;
	}
	
	public boolean hasUser(String friendlyName) {
		return _userKeyManagers.containsKey(friendlyName);
	}
	
	public BasicKeyManager getUser(String friendlyName) {
		return _userKeyManagers.get(friendlyName);
	}
	
	public File getUserDirectory(String friendlyName) {
		return _userKeystoreDirectories.get(friendlyName);
	}
	
	public CCNHandle getHandleForUser(String friendlyName) throws IOException {
		BasicKeyManager km = getUser(friendlyName);
		if (null == km)
			return null;
		return km.handle();
	}

	public Set<String> friendlyNames() {
		return _userKeyManagers.keySet();
	}

	public int count() {
		return _userKeyManagers.size();
	}
	
	/**
	 * Helper method for other programs that want to use TestUserData. Takes an args
	 * array, and an offset int it, at which it expects to find (optionally)
	 * [-as keystoreDirectoryorFilePath [-name friendlyName]]. If the latter refers to
	 * a file, it takes it as the keystore file. If it refers to a directory, it looks
	 * for the default keystore file name under that directory. If the friendly name
	 * argument is given, it uses that as the friendly name, otherwise it uses the last
	 * component of the keystoreDirectoryOrFilePath. It returns a Tuple of a handle
	 * opened under that user, and the count of arguments read, or null if the argument
	 * at offset was not -as.
	 */
	public static Tuple<Integer, CCNHandle> handleAs(String [] args, int offset) throws ConfigurationException, 
			IOException, InvalidKeyException {
		
		Tuple<Integer, KeyManager> t = keyManagerAs(args, offset);
		
		if (null == t)
			return null;
		
		return new Tuple<Integer, CCNHandle>(t.first(), CCNHandle.open(t.second()));
	}
	
	public static Tuple<Integer, KeyManager> keyManagerAs(String [] args, int offset) throws ConfigurationException, 
					IOException, InvalidKeyException {

		String friendlyName = null;
		String keystoreFileOrDirectoryPath = null;
		int argsUsed = 0;
		
		if (args.length < offset + 2)
			return null; // no as
		
		if (args.length >= offset+2) {
			if (!args[offset].equals("-as")) {
				return null; // caller must print usage()
			} else {
				keystoreFileOrDirectoryPath = args[offset+1];
				argsUsed += 2;
			}
		}

		if (args.length >= offset+5) {
			if (args[offset+2].equals("-name")) {
				friendlyName = args[offset+3];
				argsUsed += 2;
			}
		}
		
		if (null == keystoreFileOrDirectoryPath)
			return null;	// no as

		File keystoreFileOrDirectory = new File(keystoreFileOrDirectoryPath);
		if ((null == friendlyName) && 
			((keystoreFileOrDirectory.exists() && (keystoreFileOrDirectory.isDirectory())) || 
			  (!keystoreFileOrDirectory.exists()))) {
			// if its a directory, or it doesn't exist yet and we're going to make it as one, 
			// use last component as user name
			friendlyName = keystoreFileOrDirectory.getName();
		}
		
		Log.info("handleAs: loading data for user {0} from location {1}", friendlyName, keystoreFileOrDirectory);
		
		KeyManager manager = CreateUserData.loadKeystoreFile(keystoreFileOrDirectory, friendlyName,
				UserConfiguration.keystorePassword().toCharArray());
		
		return new Tuple<Integer, KeyManager>(argsUsed, manager);
	}
	
	public static KeyManager keyManagerAs(String keystoreFileOrDirectoryPath, String friendlyName) throws InvalidKeyException, ConfigurationException, IOException {
		File keystoreFileOrDirectory = new File(keystoreFileOrDirectoryPath);
		if ((null == friendlyName) && 
			((keystoreFileOrDirectory.exists() && (keystoreFileOrDirectory.isDirectory())) || 
			  (!keystoreFileOrDirectory.exists()))) {
			// if its a directory, or it doesn't exist yet and we're going to make it as one, 
			// use last component as user name
			friendlyName = keystoreFileOrDirectory.getName();
		}
		
		Log.info("keyManagerAs: loading data for user {0} from location {1}", friendlyName, keystoreFileOrDirectory);
		
		KeyManager manager = CreateUserData.loadKeystoreFile(keystoreFileOrDirectory, friendlyName,
				UserConfiguration.keystorePassword().toCharArray());
		
		return manager;
	}
	
	public static void usage() {
		System.out.println("usage: CreateUserData [[-f <file directory for keystores>] | [-r] <ccn uri for keystores>] [\"comma-separated user names\"] <user count> [<password>] [-p] (-r == use repo, -f == use files)");
	}
	
	/**
	 * Command-line driver to generate key data.
	 */
	public static void main(String [] args) {
		boolean useRepo = false;
		File directory = null;
		ContentName userNamespace = null;
		boolean publishKeysToRepo = true;
		Flosser flosser = null;
		
		String [] userNames = null;
		
		CreateUserData td = null;

		int arg = 0;
		if (args.length < 2) {
			usage();
			return;
		}
		
		if (args[arg].equals("-f")) {
			arg++;
			if (args.length < 3) {
				usage();
				return;
			}
			
			directory = new File(args[arg++]);
			
		} else {
			if (args[arg].equals("-r")) {
				arg++;
				useRepo = true;
				if (args.length < 3) {
					usage();
					return;
				}
			}
			// Generate and write to repo
			try {
				userNamespace = ContentName.fromURI(args[arg]);
				if (!useRepo) {
					flosser = new Flosser(userNamespace);
				}
			} catch (Exception e) {
				System.out.println("Exception parsing user namespace " + args[arg]);
				e.printStackTrace();
				return;
			} 
			arg++;
		}
		
		if ((args.length - arg) >= 2) {
			String userNamesString = args[arg++];
			userNames = userNamesString.split(",");
		}
		else userNames = USER_NAMES;
		
		int count = Integer.valueOf(args[arg++]);
		
		String password = UserConfiguration.keystorePassword();
		if (arg < args.length) {
			password = args[arg++];
		}
		
		try {
			if (null != directory) {
				td = new CreateUserData(directory, userNames, count,
						password.toCharArray());
				if (publishKeysToRepo) {
					td.publishUserKeysToRepository();
				}
			} else {
				td = new CreateUserData(userNamespace, userNames, count,
						useRepo,
						password.toCharArray());
				if (publishKeysToRepo) {
					td.publishUserKeysToRepository();
				}
			}
			System.out.println("Generated/retrieved " + td.count() + " user keystores, for users : " + td.friendlyNames());
		} catch (Exception e) {
			System.out.println("Exception generating/reading user data: " + e);
			e.printStackTrace();
		} finally {
			if (null != flosser)
				flosser.stop();
		}
		if (null != td) {
			td.closeAll();
		}
		System.out.println("Finished.");
		System.exit(0);
	}
}
