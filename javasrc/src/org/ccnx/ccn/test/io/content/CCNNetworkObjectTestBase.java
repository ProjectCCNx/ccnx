/*
 * A CCNx library test.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.test.Flosser;

/**
 * Common code betweeen CCNObjectTests
 */
public class CCNNetworkObjectTestBase {
	static final int UPDATE_TIMEOUT = 5000;

	static String stringObjName = "StringObject";
	static String collectionObjName = "CollectionObject";
	static String prefix = "CollectionObject-";
	static ContentName [] ns = null;
	
	static public byte [] contenthash1 = new byte[32];
	static public byte [] contenthash2 = new byte[32];
	static public byte [] publisherid1 = new byte[32];
	static public byte [] publisherid2 = new byte[32];
	static PublisherID pubID1 = null;	
	static PublisherID pubID2 = null;
	static int NUM_LINKS = 15;
	static LinkAuthenticator [] las = new LinkAuthenticator[NUM_LINKS];
	static Link [] lrs = null;
	
	static Collection small1;
	static Collection small2;
	static Collection empty;
	static Collection big;
	static String [] numbers = new String[]{"ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN"};
	static CCNHandle handle;
		
	static Level oldLevel;
	
	static Flosser flosser = null;
	
}
