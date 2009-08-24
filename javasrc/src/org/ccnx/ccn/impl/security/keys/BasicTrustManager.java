package org.ccnx.ccn.impl.security.keys;

import org.ccnx.ccn.TrustManager;
import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;



public class BasicTrustManager extends TrustManager {

	public BasicTrustManager() {
	}

	@Override
	public boolean matchesRole(PublisherID desiredRole, PublisherPublicKeyDigest thisKey) {
		if (desiredRole.type() != PublisherID.PublisherType.KEY) {
			Library.logger().info("Cannot yet handle trust match for anything more complicated than a KEY!");
			throw new UnsupportedOperationException("Cannot handle trust match for anything more complicated than a KEY yet!");
		}
		if (thisKey.equals(desiredRole)) {
			return true;
		}
		
		return false;
	}

}
