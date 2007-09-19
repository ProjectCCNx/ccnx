package com.parc.ccn.security.keys;

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

	public PublisherID getDefaultKeyID() {
		// TODO Auto-generated method stub
		return null;
	}
	
	

}
