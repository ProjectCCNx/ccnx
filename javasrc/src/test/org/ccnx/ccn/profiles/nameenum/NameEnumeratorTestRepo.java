/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011, 2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.test.profiles.nameenum;

import java.util.ArrayList;

import junit.framework.Assert;

import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Test;

public class NameEnumeratorTestRepo extends CCNTestBase implements BasicNameEnumeratorListener {
	public static final int NFILES = 1000;
	public static final int TIMEOUT = 60000;
	protected int _NESize = 0;
	protected ArrayList<ContentName> _seenNames = new ArrayList<ContentName>();

	static CCNTestHelper testHelper = new CCNTestHelper(NameEnumeratorTestRepo.class);
	static String fileNameBase = "NETest";

	/**
	 * This tests a name enumeration where the NE Object spans more than one ContentObject.
	 * Tests a real world problem that occurred once.
	 *
	 * @throws Exception
	 */
	@Test
	public void testSpanningEnumeration() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSpanningEnumeration");

		ContentName baseName = testHelper.getClassNamespace();
		
		putHandle.registerFilter(baseName, new DummyFilterListener());

		for (int i = 0; i < NFILES; i++) {
			RepositoryOutputStream ros = new RepositoryOutputStream(
					new ContentName(baseName, fileNameBase + i), putHandle);
			ros.write("NE test".getBytes(), 0, "NE test".getBytes().length);
			ros.close();
		}

		CCNNameEnumerator ccnNE = new CCNNameEnumerator(getHandle, this);
		ccnNE.registerPrefix(baseName);

		long startTime = System.currentTimeMillis();
		synchronized (this) {
			while (_NESize < NFILES && (System.currentTimeMillis() - startTime) < TIMEOUT) {
				wait(SystemConfiguration.MEDIUM_TIMEOUT);
			}
		}
		Assert.assertEquals("NameEnumeration returned incorrect number of files", NFILES, _NESize);

		Log.info(Log.FAC_TEST, "Completed testSpanningEnumeration");
	}

	public int handleNameEnumerator(ContentName prefix,
			ArrayList<ContentName> names) {
		int additions = 0;
		Log.info(Log.FAC_TEST, "Saw NE response with {0} names", names.size());
		for (ContentName incomingName : names) {
			boolean nameSeen = false;
			for (ContentName seenName : _seenNames) {
				if (incomingName.equals(seenName)) {
					nameSeen = true;
					break;
				}
			}
			if (!nameSeen) {
				additions++;
				_seenNames.add(incomingName);
			}
		}
		synchronized (this) {
			_NESize += additions;
			if (_NESize >= NFILES) {
				notifyAll();
			}
		}
		return 0;
	}
	
	class DummyFilterListener implements CCNInterestHandler {
		public boolean handleInterest(Interest interest) {
			return false;
		}	
	}	
}
