/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.profiles.security.access;


import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.test.Flosser;
import org.junit.BeforeClass;
import org.junit.Test;



public class AccessControlManagerTest {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@Test
	public void testPartialComponentMatch() {
		CCNHandle handle = null;
		try {
			ContentName testPrefix = ContentName.fromNative("/parc/test/content/");
			Flosser flosser = new Flosser(testPrefix);
			
			ContentName versionPrefix = VersioningProfile.addVersion(testPrefix);
			ContentName aname = ContentName.fromNative(versionPrefix, "aaaaa");
			ContentName bname = ContentName.fromNative(versionPrefix, "bbbbb");
			ContentName abname = ContentName.fromNative(versionPrefix, "aaaaa:bbbbb");
			
			CCNWriter writer = new CCNWriter(versionPrefix, CCNHandle.open());
			writer.put(bname, "Some b's.".getBytes());
			writer.put(abname, "Some a's and b's.".getBytes());
			
			handle = CCNHandle.open();
			ContentObject bobject = handle.get(bname, 1000);
			if (bobject != null) {
				System.out.println("Queried for bname, got back: " + bobject.name());
			}
			ContentObject aobject = handle.get(aname, 1000);
			if (aobject != null) {
				System.out.println("Queried for aname, got back: " + aobject.name());
			} else {
				System.out.println("Queried for aname, got back nothing.");
			}
			writer.put(aname, "Some a's.".getBytes());
			ContentObject aobject2 = handle.get(versionPrefix, 1000);
			if (aobject2 != null) {
				System.out.println("Queried for aname, again got back: " + aobject2.name());
			}
			flosser.stop();
		} catch (Exception e) {
			System.out.println("Exception : " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
		} finally {
			if (null != handle)
				handle.close();
		}
	}
}
