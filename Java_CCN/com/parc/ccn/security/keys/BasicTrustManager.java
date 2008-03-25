package com.parc.ccn.security.keys;

import com.parc.ccn.Library;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;

public class BasicTrustManager extends TrustManager {

	public BasicTrustManager() {
	}

	@Override
	public boolean matchesRole(PublisherID desiredRole, PublisherKeyID thisKey) {
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
