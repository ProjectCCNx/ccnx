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

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test encoding and decoding Links.
 */
public class LinkTest {

	static final  String baseName = "test";
	static final  String linkBaseName = "link";
	static final  String subName = "smetters";
	static final  String document1 = "intro.html";	
	static final  String document2 = "key";	
	static final String document3 = "cv.txt";
	static ContentName name = null;
	static ContentName name2 = null;
	static ContentName name3 = null;
	static ContentName name4 = null;
	static ContentName [] ns = null;
	static ContentName linkName = null;
	static ContentName linkName2 = null;
	static ContentName linkName3 = null;
	static ContentName linkName4 = null;
	static ContentName [] ls = null;
	static public byte [] contenthash1 = new byte[32];
	static public byte [] contenthash2 = new byte[32];
	static public byte [] publisherid1 = new byte[32];
	static public byte [] publisherid2 = new byte[32];
	static PublisherID pubID1 = null;	
	static PublisherID pubID2 = null;	
	static LinkAuthenticator [] las = new LinkAuthenticator[4];
	static String labels[];

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		name = new ContentName(baseName, subName, document1);
		name2 = new ContentName(baseName, subName, document2);
		name3 = new ContentName(baseName, subName, document3);
		name4 = ContentName.fromURI("/parc/home/briggs/collaborators.txt");
		ns = new ContentName[]{name,name2,name3,name4};
		labels = new String[]{"This is a label", "", null, "BigLabel"};
		linkName = new ContentName(linkBaseName, subName, document1);
		linkName2 = new ContentName(linkBaseName, subName, document2);
		linkName3 = new ContentName(linkBaseName, subName, document3);
		linkName4 = ContentName.fromURI("/link/home/briggs/collaborators.txt");
		ls = new ContentName[]{linkName,linkName2,linkName3,linkName4};
		Arrays.fill(contenthash1, (byte)2);
		Arrays.fill(contenthash2, (byte)4);
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);
		
		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID1 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);
	
		las[0] = new LinkAuthenticator(pubID1);
		las[1] = new LinkAuthenticator();
		las[2] = new LinkAuthenticator(pubID2, null, CCNTime.now(),
									   null, contenthash2);
		las[3] = new LinkAuthenticator(pubID1, null, CCNTime.now(),
				   SignedInfo.ContentType.DATA, contenthash1);
		
	}

	@Test
	public void testEncodeOutputStream() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testEncodeOutputStream");

		for (int i=0; i < ns.length; ++i) {
			Link l = new Link(ls[i], labels[i], las[i]);
			Link ldec = new Link();
			Link lbdec = new Link();
			XMLEncodableTester.encodeDecodeTest("LinkReference_" + i, l, ldec, lbdec);
		}
		
		Log.info(Log.FAC_TEST, "Completed testEncodeOutputStream");
	}

}
