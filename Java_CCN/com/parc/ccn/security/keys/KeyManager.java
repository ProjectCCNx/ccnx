package com.parc.ccn.security.keys;

import java.security.PrivateKey;

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
public class KeyManager {
	
	public static final String DEFAULT_DIGEST_ALGORITHM = "SHA-256";

	public PublisherID getDefaultKeyID() {
		// TODO Auto-generated method stub
		return null;
	}

	public PrivateKey getDefaultSigningKey() {
		// TODO Auto-generated method stub
		return null;
	}

	public KeyLocator getKeyLocator(PrivateKey signingKey) {
		// TODO Auto-generated method stub
		return null;
	}
	
	

}
