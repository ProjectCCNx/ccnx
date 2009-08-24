package test.ccn.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Set;
import java.util.SortedSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.security.keys.NetworkKeyManager;
import org.ccnx.ccn.impl.security.keys.RepositoryKeyManager;
import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.profiles.access.AccessControlManager;
import org.ccnx.ccn.profiles.access.AccessControlProfile;
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
	 * @param userKeystoreDataPrefix
	 * @param userCount
	 * @param storeInRepo
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 */
	public TestUserData(ContentName testStorePrefix, int userCount, boolean storeInRepo, char [] password, CCNHandle library) throws ConfigurationException, IOException, InvalidKeyException {
	
		ContentName childName = null;
		String friendlyName = null;
		KeyManager userKeyManager = null;
		
		AccessControlManager _acm = new AccessControlManager(testStorePrefix);
		ContentName userKeystoreDataPrefix = AccessControlProfile.userNamespaceName(testStorePrefix);
		
		for (int i=0; i < userCount; ++i) {
			friendlyName = USER_NAMES[i % USER_NAMES.length] + Integer.toString(1 + i/USER_NAMES.length);
			childName = ContentName.fromNative(userKeystoreDataPrefix, friendlyName);
			Library.logger().info("Loading user: " + friendlyName + " from " + childName);
			
			if (storeInRepo) {
				// This only matters the first time through, when we save the user's data.
				// but it makes no difference in other cases anyway.
				userKeyManager = new RepositoryKeyManager(friendlyName, childName, null, password, library);
			} else {
				userKeyManager = new NetworkKeyManager(friendlyName, childName, null, password, library);
			}
			userKeyManager.initialize();
			_userData.put(childName, userKeyManager);
			_userFriendlyNames.put(friendlyName, childName);
			
			_acm.publishUserIdentity(friendlyName, userKeyManager.getDefaultPublicKey());
			
/*			KeyPairGenerator kpg = null;
			try {
				kpg = KeyPairGenerator.getInstance(AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM);
			} catch (NoSuchAlgorithmException e) {
				throw new ConfigurationException("Specified user public key algorithm " +  AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM + " not found. " + e.getMessage());
			}
			kpg.initialize(AccessControlManager.DEFAULT_GROUP_KEY_LENGTH);
			KeyPair pair = kpg.generateKeyPair();
			PublicKeyObject pko = new PublicKeyObject(childName, pair.getPublic(), library);
			pko.saveToRepository();*/
		}
	}
	
	
	
	/**
	 * General read constructor. Expects names to be available in repo, and so enumerable.
	 * i.e. something must be there. Uses NetworkKeyManager to read them out, though.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 */
	public TestUserData(ContentName userKeystoreDataPrefix, char [] password, CCNHandle library) throws IOException, ConfigurationException, InvalidKeyException {
		
		EnumeratedNameList userDirectory = new EnumeratedNameList(userKeystoreDataPrefix, library);
		userDirectory.waitForData(); // will block
		
		SortedSet<ContentName> availableChildren = userDirectory.getChildren();
		if ((null == availableChildren) || (availableChildren.size() == 0)) {
			Library.logger().warning("No available user keystore data in directory " + userKeystoreDataPrefix + ", giving up.");
			throw new IOException("No available user keystore data in directory " + userKeystoreDataPrefix + ", giving up.");
		}
		String friendlyName;
		ContentName childName;
		KeyManager userKeyManager;
		while (null != availableChildren) {
			for (ContentName child : availableChildren) {
				friendlyName = ContentName.componentPrintNative(child.lastComponent());
				if (null != getUser(friendlyName)) {
					Library.logger().info("Already loaded data for user: " + friendlyName + " from name: " + _userFriendlyNames.get(friendlyName));
					continue;
				}
				childName = new ContentName(userKeystoreDataPrefix, child.lastComponent());
				Library.logger().info("Loading user: " + friendlyName + " from " + childName);
				userKeyManager = new NetworkKeyManager(friendlyName, childName, null, password, library);
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
	
	public CCNHandle getLibraryForUser(String friendlyName) {
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
