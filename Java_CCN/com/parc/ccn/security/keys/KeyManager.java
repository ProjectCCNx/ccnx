package com.parc.ccn.security.keys;

import java.security.PrivateKey;
import java.security.PublicKey;

import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;

/**
 * Front-end for key repository, both our keys
 * and other peoples' keys.
 * 
 * By JDK 1.6 we have ways to get at both MSCAPI
 * keys (we can use them, but not export them; it's
 * not clear we can import things into there either),
 * and PKCS#11 keys, which by derivation also gets
 * us Mozilla/Firefox keys, as well as smart cards.
 * 
 * @author smetters
 *
 */
public abstract class KeyManager {
	
	public static final String DEFAULT_DIGEST_ALGORITHM = "SHA-256";

	public abstract PublisherID getDefaultKeyID();

	public abstract PrivateKey getDefaultSigningKey();
	public abstract PublicKey getDefaultPublicKey();

	public abstract KeyLocator getKeyLocator(PrivateKey signingKey);
	
	public abstract PrivateKey getSigningKey(String alias);
	public abstract PrivateKey getSigningKey(PublisherID publisher);
	
	public abstract PublicKey getPublicKey(String alias);
	public abstract PublicKey getPublicKey(PublisherID publisher);

	public static KeyManager getDefaultKeyManager() {
		return new BasicKeyManager();
	}
}
