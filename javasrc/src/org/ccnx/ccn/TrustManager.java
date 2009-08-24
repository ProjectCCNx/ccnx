package org.ccnx.ccn;

import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.security.keys.BasicTrustManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


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
public abstract class TrustManager {
	

	public static TrustManager getDefaultTrustManager() throws ConfigurationException {
		return new BasicTrustManager();
	}
	
	public static TrustManager getTrustManager() {
		try {
			return getDefaultTrustManager();
		} catch (ConfigurationException e) {
			Log.warning("Configuration exception attempting to get TrustManager: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot get TrustManager.",e);
		}
	}
	
	/**
	 * A PublisherID can specify not only a key, but a role (subject or issuer).
	 * This is the start of an API to do the calculation of whether a given key
	 * matches a desired role.
	 * @param desiredRole
	 * @param thisKey
	 * @return
	 */
	public abstract boolean matchesRole(PublisherID desiredRole, PublisherPublicKeyDigest thisKey);

}
