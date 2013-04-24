package org.ccnx.ccn.test.repo;

import static org.ccnx.ccn.profiles.CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION;
import static org.ccnx.ccn.protocol.Component.NONCE;

import java.io.IOException;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.profiles.versioning.VersionNumber;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.Test;

public final class SimpleNameEnumerationTest {

	public SimpleNameEnumerationTest() throws Exception {}

	ContentName baseName = ContentName.fromNative("/testNE");
	CCNHandle handle = CCNHandle.getHandle();

	public VersionNumber doNameEnumerationRequest() throws IOException {
		ContentName neRequest = new ContentName(baseName, COMMAND_MARKER_BASIC_ENUMERATION);
		ContentObject co = handle.get(neRequest, 2000);
		Assert.assertNotNull(co);
		CollectionObject response = new CollectionObject(co, handle);
		return response.getVersionNumber();
	}

	@Test
	public void testNameEnumeration() throws Exception {
		// do a name enumeration request, see what version response we get
		VersionNumber first = doNameEnumerationRequest();

		// clear the ccnd cache
		Runtime.getRuntime().exec("ccnrm /");

		// do another name enumeration request, check we get the same version
		VersionNumber second = doNameEnumerationRequest();
		Assert.assertEquals(first, second);

		// write something to the repo
		ContentName freshContent = new ContentName(baseName, NONCE);
		new RepositoryOutputStream(freshContent, handle).close();

		// clear the ccnd cache
		Runtime.getRuntime().exec("ccnrm /");

		// do another name enumeration request, check we get a different version
		VersionNumber third = doNameEnumerationRequest();
		Assert.assertTrue(second != third);
	}
}
