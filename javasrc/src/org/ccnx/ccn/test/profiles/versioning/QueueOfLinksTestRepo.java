package org.ccnx.ccn.test.profiles.versioning;

import java.util.Arrays;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.versioning.VersioningInterest;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.test.profiles.versioning.VersioningHelper.ReceivedData;
import org.ccnx.ccn.test.profiles.versioning.VersioningHelper.TestListener;
import org.junit.Test;

public class QueueOfLinksTestRepo {
	Random rnd = new Random();
	
	@Test
	public void testLinkQueue() throws Exception {
		String prefix = String.format("/test_%016X", rnd.nextLong());
		String queuenamestring = prefix + "/queue";
		String objnamestring = prefix + "/obj";
		ContentName queuename = ContentName.fromNative(queuenamestring);
		ContentName objname = ContentName.fromNative(objnamestring);
		int objsize = 1024*600;
		CCNHandle recvhandle = CCNHandle.getHandle();
		CCNHandle sendhandle = CCNHandle.open(recvhandle.keyManager());
		
		char [] buf = new char[objsize];
		Arrays.fill(buf, 'x');
		String objfill = String.valueOf(buf);
		
		VersioningInterest vi = new VersioningInterest(recvhandle);
		TestListener listener = new TestListener();
		vi.expressInterest(queuename, listener);
		
		Thread.sleep(1000);
		
		CCNStringObject so = new CCNStringObject(objname, objfill, SaveType.LOCALREPOSITORY, sendhandle);
		so.save();
		so.close();
		
		Link link = new Link(so.getVersionedName());
		LinkObject lo = new LinkObject(queuename, link, SaveType.LOCALREPOSITORY, sendhandle);
		lo.save();
		lo.close();
		
		sendhandle.close();
		
		// now see if we got it in the TestListener
		
		Assert.assertTrue(listener.cl.waitForValue(1, 60000));
		
		ReceivedData rd = listener.received.get(0);
		
		ContentObject co = rd.object;
		
		CCNStringObject so2 = new CCNStringObject(co.name(), recvhandle);
		
		Assert.assertEquals(so, so2);
	}
}
