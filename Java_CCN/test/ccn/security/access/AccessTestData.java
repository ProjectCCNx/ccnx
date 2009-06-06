package test.ccn.security.access;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * Creates and loads access control test data.
 * @author smetters
 *
 */
public class AccessTestData {
	
	public static final int NUM_USERS = 20;
	
	public static final String [] USER_NAMES = {"Bob", "Alice", "Carol", "Mary", "Oswald", "Binky",
												"Spot", "Fred", "Eve", "Harold", "Barack", "Newt",
												"Allison", "Zed", "Walter", "Gizmo", "Nick", "Michael",
												"Nathan", "Rebecca", "Diana", "Jim", "Van", "Teresa",
												"Russ", "Tim", "Sharon", "Jessica", "Elaine", "Mark"};
	
	public static final KeyPair [] userKeyPairs = new KeyPair[NUM_USERS];
	public static final String KEY_ALGORITHM = "RSA";
	public static final int KEY_LENGTH = 512;
	
	public static initializeData() {
		try {
			KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM);
			kpg.initialize(KEY_LENGTH);
			for (int i=0; i < NUM_USERS; ++i) {
				userKeyPairs[i] = kpg.generateKeyPair();
			}
	}
}
