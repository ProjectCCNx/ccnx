package org.ccnx.ccn.test.profiles.access;

import org.junit.Test;

import java.util.Random;
import java.util.ArrayList;
import java.security.PublicKey;

import junit.framework.Assert;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.profiles.access.AccessControlManager;
import org.ccnx.ccn.profiles.access.ACL;
import org.ccnx.ccn.profiles.access.ACL.ACLObject;

public class AccessControlManagerTestRepo {

	@Test
	public void testSetAndGetACL() throws Exception {
		Random rand = new Random();
		String directoryBase = "/test/AccessControlManagerTestRepo-";
		ContentName baseNodeName = ContentName.fromNative(directoryBase + Integer.toString(rand.nextInt(10000)));
		
		// publish my identity
		AccessControlManager acm = new AccessControlManager(baseNodeName);
		ContentName myIdentity = ContentName.fromNative("/test/parc/users/pgolle");
		acm.publishMyIdentity(myIdentity, null);	

		// set ACL
		ArrayList<Link> ACLcontents = new ArrayList<Link>();
		KeyManager km = KeyManager.getKeyManager();
		PublicKey pk = km.getDefaultPublicKey();
		PublisherID pubID = new PublisherID(pk, true);
		LinkAuthenticator la = new LinkAuthenticator(pubID);
		Link lk = new Link(myIdentity, "r", la);
		ACLcontents.add(lk);
		ACL baseACL = new ACL(ACLcontents);
		acm.initializeNamespace(baseACL);
		
		// get ACL
		ACLObject aclo = acm.getEffectiveACLObject(baseNodeName);
		ACL aclRetrieved = aclo.acl();
		Link linkRetrieved = aclRetrieved.remove(0);
		Assert.assertTrue(linkRetrieved.equals(lk));
	}

	
}
