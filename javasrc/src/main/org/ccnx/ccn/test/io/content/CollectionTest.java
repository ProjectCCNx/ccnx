/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.io.content;

import java.util.Arrays;
import java.util.LinkedList;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test the Collection data representation.
 */
public class CollectionTest {

	static final  String baseName = "test";
	static final  String subName = "smetters";
	static final  String document1 = "intro.html";	
	static final  String document2 = "key";	
	static final String document3 = "cv.txt";
	static ContentName name = null;
	static ContentName name2 = null; 
	static ContentName name3 = null;
	static ContentName name4 = null;
	static ContentName [] ns = null;
	static public byte [] contenthash1 = new byte[32];
	static public byte [] contenthash2 = new byte[32];
	static public byte [] publisherid1 = new byte[32];
	static public byte [] publisherid2 = new byte[32];
	static PublisherID pubID1 = null;	
	static PublisherID pubID2 = null;	
	static LinkAuthenticator [] las = new LinkAuthenticator[4];
	static Link [] lrs = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		name = new ContentName(baseName, subName, document1);
		name2 = new ContentName(baseName, subName, document2);
		name3 = new ContentName(baseName, subName, document3);
		name4 = ContentName.fromURI("/parc/home/briggs/collaborators.txt");
		ns = new ContentName[]{name,name2,name3,name4};
		Arrays.fill(contenthash1, (byte)2);
		Arrays.fill(contenthash2, (byte)4);
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);

		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID2 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);

		las[0] = new LinkAuthenticator(pubID1);
		las[1] = null;
		las[2] = new LinkAuthenticator(pubID2, null, null,
				SignedInfo.ContentType.DATA, contenthash1);
		las[3] = new LinkAuthenticator(pubID1, null, CCNTime.now(),
				null, contenthash1);


		lrs = new Link[4];
		for (int i=0; i < lrs.length; ++i) {
			lrs[i] = new Link(ns[i],las[i]);
		}
	}

	@Test
	public void testValidate() {
		Log.info(Log.FAC_TEST, "Starting testValidate");

		Collection cd = new Collection();
		Assert.assertTrue(cd.validate());
		cd.add(lrs[0]);
		Assert.assertTrue(cd.validate());
		cd.remove(0);
		Assert.assertTrue(cd.validate());
		
		Log.info(Log.FAC_TEST, "Completed testValidate");
	}

	@Test
	public void testCollectionData() {
		Log.info(Log.FAC_TEST, "Starting testCollectionData");

		Collection cd = new Collection();
		Assert.assertNotNull(cd);
		Assert.assertTrue(cd.validate());
		
		Log.info(Log.FAC_TEST, "Completed testCollectionData");
	}

	@Test
	public void testContents() {
		Log.info(Log.FAC_TEST, "Starting testContents");

		Collection cd = new Collection();
		Assert.assertTrue(cd.validate());
		for (int i=0; i < lrs.length; ++i) {
			cd.add(lrs[i]);
		}
		LinkedList<Link> c = cd.contents();
		Assert.assertNotNull(c);
		Assert.assertTrue(c.size() == lrs.length);
		for (int i=0; i < lrs.length; ++i) {
			Assert.assertEquals(c.get(i), lrs[i]);
		}
		
		// Test iterator
		int j=0;
		for (Link l : cd) {
			Assert.assertEquals(l, lrs[j++]);			
		}
		
		Log.info(Log.FAC_TEST, "Completed testContents");
	}

	@Test
	public void testAddGet() {
		Log.info(Log.FAC_TEST, "Starting testAddGet");

		Collection cd = new Collection();
		for (int i=0; i < lrs.length; ++i) {
			cd.add(lrs[i]);
		}
		for (int i=0; i < lrs.length; ++i) {
			Assert.assertEquals(cd.get(i), lrs[i]);
		}
		
		Log.info(Log.FAC_TEST, "Completed testAddGet");
	}

	@Test
	public void testRemoveInt() {
		Log.info(Log.FAC_TEST, "Starting testRemoveInt");

		Collection cd = new Collection();
		for (int i=0; i < lrs.length; ++i) {
			cd.add(lrs[i]);
		}
		cd.remove(0);
		Assert.assertEquals(cd.get(0), lrs[1]);
		
		Log.info(Log.FAC_TEST, "Completed testRemoveInt");
	}

	@Test
	public void testRemoveLink() {
		Log.info(Log.FAC_TEST, "Starting testRemoveLink");

		Collection cd = new Collection();
		for (int i=0; i < lrs.length; ++i) {
			cd.add(lrs[i]);
		}
		cd.remove(lrs[0]);
		Assert.assertEquals(cd.get(0), lrs[1]);
		LinkAuthenticator la2alt = new LinkAuthenticator(pubID2, null, null,
				SignedInfo.ContentType.DATA, contenthash1);

		Link lr2alt = new Link(name3, la2alt);
		cd.remove(lr2alt);
		Assert.assertEquals(cd.get(1), lrs[3]);
		
		Log.info(Log.FAC_TEST, "Completed testRemoveLink");
	}

	@Test
	public void testSize() {
		Log.info(Log.FAC_TEST, "Starting testSize");

		Collection cd = new Collection();
		for (int i=0; i < lrs.length; ++i) {
			cd.add(lrs[i]);
		}
		Assert.assertTrue(cd.size() == lrs.length);
		
		Log.info(Log.FAC_TEST, "Completed testSize");
	}

	@Test
	public void testEqualsObject() {
		Log.info(Log.FAC_TEST, "Starting testEqualsObject");

		Collection cd = new Collection();
		Collection cd2 = new Collection();
		Collection cd3 = new Collection();

		for (int i=0; i < lrs.length; ++i) {
			cd.add(lrs[i]);
			cd2.add(lrs[i]);
			cd3.add(lrs[lrs.length-i-1]);
		}
		Assert.assertEquals(cd, cd2);
		Assert.assertFalse(cd.equals(cd3));
		cd.remove(2);
		Collection cd4 = cd2.clone();
		Assert.assertFalse(cd.equals(cd2));
		Assert.assertEquals(cd4, cd2);
		cd2.remove(2);
		Assert.assertEquals(cd, cd2);

		cd2.remove(2); // remove last entry
		cd2.add(new Link(name3, las[2]));
		cd2.add(new Link(name4, las[3]));
		Assert.assertEquals(cd2, cd4);
		
		Log.info(Log.FAC_TEST, "Completed testEqualsObject");
	}

	@Test
	public void testEncodeDecodeStream() {
		Log.info(Log.FAC_TEST, "Starting testEncodeDecodeStream");

		Collection cd = new Collection();
		Collection cdec = new Collection();
		Collection bdec = new Collection();

		for (int i=0; i < lrs.length; ++i) {
			cd.add(lrs[i]);
		}
		XMLEncodableTester.encodeDecodeTest("Collection", cd, cdec, bdec);
		
		Log.info(Log.FAC_TEST, "Completed testEncodeDecodeStream");
	}

	@Test
	public void testEncodeDecodeByteArray() {
		Log.info(Log.FAC_TEST, "Starting testEncodeDecodeByteArray");

		Collection cd = new Collection();
		Collection cdec = new Collection();
		Collection bdec = new Collection();

		for (int i=0; i < lrs.length; ++i) {
			cd.add(lrs[i]);
		}
		XMLEncodableTester.encodeDecodeByteArrayTest("Collection", cd, cdec, bdec);
		
		Log.info(Log.FAC_TEST, "Completed testEncodeDecodeByteArray");
	}
}
