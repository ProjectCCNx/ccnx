/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.SignatureException;
import java.util.List;
import java.util.Random;

import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.InterestTable;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.ccnx.ccn.test.CCNTestBase;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests InterestTable, the core implementation of interest matching
 * and dispatching.
 */
public class InterestTableTest extends CCNTestBase {


	static public PublisherID ids[] = new PublisherID[3];
	static public PublisherPublicKeyDigest keyids[] = new PublisherPublicKeyDigest[3];

	// IDs are parameters that establish test condition so same 
	// code can be run under different conditions
	static public PublisherPublicKeyDigest activeKeyID = null;
	static public PublisherID activeID = null;
	
	// removeByMatch controls whether remove operations are tested
	// via the removeMatch* (true) or the removeValue* (false) methods 
	// of the InterestTable.  This global parameter permits the conditions
	// to be varied for different runs of the same code.
	static public boolean removeByMatch = true;
	
	// additionalComponents controls the number of additionalComponents
	// to use during the additionalComponents testing
	static public Integer additionalComponents = 1;
	
	// prefixCount controls the prefixCount
	// to use during the prefixCount testing
	static public Integer prefixCount = 1;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		CCNTestBase.setUpBeforeClass();
		byte [] publisher = new byte[32];

		try {
			Random rnd = new Random();

			for (int i = 0; i < ids.length; i++) {
				rnd.nextBytes(publisher);
				// Note that when this was first written, trust matching
				// could not handle anything more complicated than a KEY
				ids[i] = new PublisherID(publisher, PublisherType.KEY);
				keyids[i] = new PublisherPublicKeyDigest(publisher);
			}
			
		} catch (Exception ex) {
			ex.printStackTrace();
			Log.info(Log.FAC_TEST, "Unable To Initialize Test!!!");
			fail();
		}	
	}
	
	public static void setID(int i) {
		if (i >= 0) {
			activeID = ids[i];
			activeKeyID = keyids[i];
		} else {
			activeID = null;
			activeKeyID = null;
		}
	}

	@Before
	public void setUp() throws Exception {
	}
	
	@Test
	public void testAdd() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testAdd");

		Interest intA = new Interest("/a/b/c");
		Interest intB = new Interest("/a/b/c");
		Interest intC = new Interest("/a/b/c/d");
		
		ContentName namA = ContentName.fromNative("/a/b/c");
		ContentName namB = ContentName.fromNative("/a/b/c");
		ContentName namC = ContentName.fromNative("/a/b/c/d");
		
		InterestTable<Object> interests = new InterestTable<Object>();
		interests.add(intA, null);
		interests.add(intB, null);
		interests.add(intC, null);
		interests.add(intA, null);
		try {
			interests.add((Interest)null, null);
			fail();
		} catch (NullPointerException ex) {
			// good
		}
		
		assertEquals(4, interests.size());
		assertEquals(2, interests.sizeNames());
		
		InterestTable<Object> names = new InterestTable<Object>();
		names.add(namA, null);
		names.add(namB, null);
		names.add(namC, null);
		names.add(namA, null);
		try {
			names.add((ContentName)null, null);
			fail();
		} catch (NullPointerException ex) {
			// good
		}
		
		assertEquals(4, names.size());
		assertEquals(2, names.sizeNames());
		
		Log.info(Log.FAC_TEST, "Completed testAdd");
	}
		
	private ContentObject getContentObject(ContentName name) throws ConfigurationException, InvalidKeyException, SignatureException, MalformedContentNameStringException {
		return getContentObject(name, activeKeyID);
	}
	
	/*
	 * Note: This method appends an automatically generated random element onto the end of the
	 * input content name
	 */
	private ContentObject getContentObject(ContentName name, PublisherPublicKeyDigest pub) throws ConfigurationException, InvalidKeyException, SignatureException, MalformedContentNameStringException {
		// contents = current date value
		CCNTime now = CCNTime.now();
		ByteBuffer bb = ByteBuffer.allocate(Long.SIZE/Byte.SIZE);
		bb.putLong(now.getTime());
		byte[] contents = bb.array();
		// security bits
		KeyLocator locator = new KeyLocator(ContentName.fromNative("/key/" + DataUtils.printBytes(pub.digest())));
		SignedInfo si = new SignedInfo(pub, now, SignedInfo.ContentType.DATA, locator);
		// unique name		
		return new ContentObject(
				new ContentName(name, Long.toString(now.getTime())), si, contents, fakeSignature);
	}
	
	private ContentObject getContentObject(ContentName name, int value) throws InvalidKeyException, SignatureException, MalformedContentNameStringException, ConfigurationException {
		ContentObject cn = getContentObject(name);
		return new ContentObject(cn.name(), cn.signedInfo(), new Integer(value).toString().getBytes(), cn.signature());
	}
	
	private void match(InterestTable<Integer> table, ContentName name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		// Test both methods
		assertEquals(v, (null == activeKeyID) ? table.getMatch(name).value().intValue() :
												 table.getMatch(getContentObject(name, v)).value().intValue());
		assertEquals(v, (null == activeKeyID) ? table.getValue(name).intValue() :
			 									table.getValue(getContentObject(name,v)).intValue());
	}

	private void match(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		match(table, ContentName.fromNative(name), v);
	}
	
	private void removeMatch(InterestTable<Integer> table, ContentName name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		if (removeByMatch) {
			assertEquals(v, table.removeMatch(getContentObject(name)).value().intValue());
		} else {
			assertEquals(v, table.removeValue(getContentObject(name)).intValue());
		}
	}
	
	private void removeMatch(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		removeMatch(table, ContentName.fromNative(name), v);
	}
	
	private void remove(InterestTable<Integer> table, ContentName name, int v) {
		if (null == activeKeyID) {
			assertEquals(v, table.remove(name, new Integer(v)).value().intValue());
		} else {
			assertEquals(v, table.remove(new Interest(name, activeID), v).value().intValue());
		}
	}

	private void remove(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException {
		remove(table, ContentName.fromNative(name), v);
	}
	
	private void noRemove(InterestTable<Integer> table, ContentName name, int v) {
		if (null == activeKeyID) {
			assertNull(table.remove(name, new Integer(v)));
		} else {
			assertNull(table.remove(new Interest(name, activeID), v));
		}
	}
	
	private void noRemove(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException {
		noRemove(table, ContentName.fromNative(name), v);
	}

	private void noRemoveMatch(InterestTable<Integer> table, ContentName name) throws InvalidKeyException, SignatureException, MalformedContentNameStringException, ConfigurationException {
		assertNull(table.removeMatch(getContentObject(name)));
		assertNull(table.removeValue(getContentObject(name)));
	}
	
	private void noRemoveMatch(InterestTable<Integer> table, String name) throws InvalidKeyException, SignatureException, MalformedContentNameStringException, ConfigurationException {
		noRemoveMatch(table, ContentName.fromNative(name));
	}
	
	private void noMatch(InterestTable<Integer> table, ContentName name) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		assertNull((null == activeKeyID) ? table.getMatch(name) :
									       table.getMatch(getContentObject(name, 0)));
		assertNull((null == activeKeyID) ? table.getValue(name) :
		       							   table.getValue(getContentObject(name, 0)));
	}

	private void noMatch(InterestTable<Integer> table, String name) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		noMatch(table, ContentName.fromNative(name));
	}

	private void matches(InterestTable<Integer> table, ContentName name, ContentName[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		List<InterestTable.Entry<Integer>> result = (null == activeKeyID) ? table.getMatches(name) :
																			 table.getMatches(getContentObject(name, 0));
		assertEquals(v.length, result.size());
		for (int i = 0; i < v.length; i++) {
			assertEquals(v[i], result.get(i).value().intValue());
			assertEquals(n[i], result.get(i).name());
		}
		
		List<Integer> values = (null == activeKeyID) ? table.getValues(name) :
			 								table.getValues(getContentObject(name, 0));
		assertEquals(v.length, values.size());
		for (int i=0; i < v.length; i++) {
			assertEquals(v[i], values.get(i).intValue());
		}
	}
	
	private void matches(InterestTable<Integer> table, String name, String[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		ContentName[] cn = new ContentName[n.length];
		for (int i = 0; i < n.length; i++) {
			cn[i] = ContentName.fromNative(n[i]);
		}
		matches(table, ContentName.fromNative(name), cn, v);
	}
	
	private void removeMatches(InterestTable<Integer> table, ContentName name, ContentName[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		if (removeByMatch) {
			List<InterestTable.Entry<Integer>> result = table.removeMatches(getContentObject(name, 0));
			assertEquals(v.length, result.size());
			for (int i = 0; i < v.length; i++) {
				assertEquals(v[i], result.get(i).value().intValue());
				assertEquals(n[i], result.get(i).name());
			}
		} else {
			List<Integer> result = table.removeValues(getContentObject(name, 0));
			assertEquals(v.length, result.size());
			for (int i = 0; i < v.length; i++) {
				assertEquals(v[i], result.get(i).intValue());
			}
		}
	}

	private void removeMatches(InterestTable<Integer> table, String name, String[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		ContentName[] cn = new ContentName[n.length];
		for (int i = 0; i < n.length; i++) {
			cn[i] = ContentName.fromNative(n[i]);
		}
		removeMatches(table, ContentName.fromNative(name), cn, v);
	}
	
	private void addEntry(InterestTable<Integer> table, ContentName name, Integer value) throws MalformedContentNameStringException {
		if (null == activeKeyID) {
			table.add(name, value);
		} else {
			table.add(new Interest(name, activeID), value);
		}
	}
	
	private void addEntry(InterestTable<Integer> table, String name, Integer value) throws MalformedContentNameStringException {
		addEntry(table, ContentName.fromNative(name), value);
	}

	private void sizes(InterestTable<Integer> table, int s, int n) {
		assertEquals(s, table.size());
		assertEquals(n, table.sizeNames());
		assertEquals(s, table.values().size());
	}
	
	final String a = "/a";
	final String ab = "/a/b";
	final String a_bb = "/a/bb";
	final String abc = "/a/b/c";
	final String abb = "/a/b/b";
	final String b = "/b";
	final String c = "/c";
	final String _aa = "/aa";
	final ContentName zero = new ContentName(new byte[]{0x00, 0x02, 0x03, 0x04});
	final ContentName one = new ContentName(new byte[]{0x01, 0x02, 0x03, 0x04});
	final ContentName onethree = new ContentName(new byte[]{0x01, 0x02, 0x03, 0x04}, new byte[]{0x03});

	private InterestTable<Integer> initTable() throws MalformedContentNameStringException {
		InterestTable<Integer> table = new InterestTable<Integer>();
		addEntry(table, a, new Integer(1));
		addEntry(table, ab, new Integer(2));
		addEntry(table, c, new Integer(3));
		addEntry(table, b, new Integer(4));
		addEntry(table, a_bb, new Integer(5));
		addEntry(table, _aa, new Integer(6));
		addEntry(table, abc, new Integer(7));
		addEntry(table, zero, new Integer(8));
		addEntry(table, onethree, new Integer(9));
		addEntry(table, one, new Integer(10));
		addEntry(table, ab, new Integer(45));

		sizes(table, 11, 10);
		return table;
	}
	
	private void runMatchName() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		
		InterestTable<Integer> names = initTable();

		match(names, a_bb, 5);
		match(names, abc, 7);
		match(names, ab, 2);
		match(names, "/a/b/d", 2);
		match(names, "/a/b/c/d", 7);
		match(names, abb, 2);
		match(names, b, 4);
		match(names, c, 3);
		match(names, one, 10);
		match(names, zero, 8);
		match(names, onethree, 9);
		match(names, "/a/b/b/a", 2);
		match(names, _aa, 6);
		
		matches(names, _aa, new String[] {_aa}, new int[] {6});
		matches(names, c, new String[] {c}, new int[] {3});
		matches(names, "/a/b/c/d", new String[] {abc, ab, ab, a}, new int[] {7, 2, 45, 1});
		matches(names, abc, new String[] {abc, ab, ab, a}, new int[] {7, 2, 45, 1});
		matches(names, ab, new String[] {ab, ab, a}, new int[] {2, 45, 1});
		matches(names, a, new String[] {a}, new int[] {1});
		matches(names, a_bb, new String[] {a_bb, a}, new int[] {5, 1});
		matches(names, "/a/b/d", new String[] {ab, ab, a}, new int[] {2, 45, 1});
		matches(names, zero, new ContentName[] {zero}, new int[] {8});
		matches(names, onethree, new ContentName[] {onethree, one}, new int[] {9, 10});
		matches(names, one, new ContentName[] {one}, new int[] {10});

		addEntry(names, abb, new Integer(11));
		sizes(names, 12, 11);
		
		match(names, abb, 11);
		match(names, abc, 7);
		match(names, "/a/b/c/d", 7);
		
		matches(names, "/a/b/c/d", new String[] {abc, ab, ab, a}, new int[] {7, 2, 45, 1});
		matches(names, "/a/b/b/a", new String[] {abb, ab, ab, a}, new int[] {11, 2, 45, 1});

		noMatch(names, "/q");
		
		remove(names, ab, 2);
		sizes(names, 11, 11);
		matches(names, "/a/b/b/a", new String[] {abb, ab, a}, new int[] {11, 45, 1});

	}
	
	@Test
	public void testMatchName() throws InvalidKeyException, MalformedContentNameStringException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testMatchName");

		// First test names matching against names, no ContentObject
		setID(-1);
		runMatchName();
		
		// Next run ContentObject against names in table
		setID(0);
		runMatchName();
		
		Log.info(Log.FAC_TEST, "Completed testMatchName");
	}
	
	public InterestTable<Integer> initPub() throws MalformedContentNameStringException {
		InterestTable<Integer> table = new InterestTable<Integer>();
		
		setID(0);
		addEntry(table, a, new Integer(1));
		addEntry(table, b, new Integer(2));
		addEntry(table, a_bb, new Integer(3));
		addEntry(table, abb, new Integer(4));
		
		setID(1);
		addEntry(table, ab, new Integer(5));
		addEntry(table, abc, new Integer(6));
		addEntry(table, _aa, new Integer(7));
		
		setID(2);
		addEntry(table, abb, new Integer(8));
		
		sizes(table, 8, 7);
		return table;
	}
	
	@Test
	public void testMatchPub() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testMatchPub");

		InterestTable<Integer> names = initPub();
		
		setID(2);
		match(names, abb, 8);
		match(names, "/a/b/b/a", 8);
		matches(names, "/a/b/b/a", new String[] {abb}, new int[] {8});
		noMatch(names, a_bb);
		noMatch(names, abc);
		match(names, abb, 8);
		
		setID(0);
		noMatch(names, _aa);
		match(names, "/a/b/b/a", 4);
		match(names, abc, 1);
		matches(names, abc, new String[] {a}, new int[] {1});
		match(names, "/a/b/c/d", 1);
		matches(names, "/a/b/b/a", new String[] {abb, a}, new int[] {4, 1});
		match(names, abb, 4);
		matches(names, abb, new String[] {abb, a}, new int[] {4, 1});
		
		setID(1);
		noMatch(names, b);
		match(names, abc, 6);
		matches(names, abc, new String[] {abc, ab}, new int[] {6, 5});
		match(names, "/a/b/b/a", 5);
		noMatch(names, a_bb);
		
		setID(-1);
		match(names, a, 1);
		matches(names, "/a/b/b/a", new String[] {abb, abb, ab, a}, new int[] {4, 8, 5, 1});
		
		Log.info(Log.FAC_TEST, "Completed testMatchPub");
	}


	@Test
	public void testSimpleRemoves() throws InvalidKeyException, MalformedContentNameStringException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testSimpleRemoves");

		removeByMatch = true;
		runSimpleRemoves();
		
		removeByMatch = false;
		runSimpleRemoves();
		
		Log.info(Log.FAC_TEST, "Completed testSimpleRemoves");
	}
	
	private void runSimpleRemoves() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		
		setID(0);
		InterestTable<Integer> names = initTable();
		
		noRemoveMatch(names, "/q");
		
		removeMatch(names, a_bb, 5);
		sizes(names, 10, 9);
		
		removeMatch(names, a_bb, 1);
		sizes(names, 9, 8);

		noRemoveMatch(names, a_bb);

		removeMatches(names, abc, new String[] {abc, ab, ab}, new int[] {7, 2, 45});
		sizes(names, 6, 6);
		noRemoveMatch(names, abc);

		removeMatch(names, onethree, 9);
		removeMatch(names, onethree, 10);
		noRemoveMatch(names, onethree);
		noRemoveMatch(names, one);
		sizes(names, 4, 4);
		
		removeMatch(names, zero, 8);
		noRemoveMatch(names, zero);
		sizes(names, 3, 3);
		
		removeMatch(names, "/aa/b", 6);
		noRemoveMatch(names, "/aa/b");
		noRemoveMatch(names, "/aa/c");
		sizes(names, 2, 2);
		
		removeMatch(names, "/c/d", 3);
		noRemoveMatch(names, "/c");
		
		removeMatch(names, "/b/d/d/a", 4);
		noRemoveMatch(names, "/b");
		
		sizes(names, 0, 0);
		
		noRemoveMatch(names, "/a");
		sizes(names, 0, 0);
	}

	@Test
	public void testRemovesPub() throws InvalidKeyException, MalformedContentNameStringException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testRemovesPub");

		removeByMatch = true;
		runRemovesPub();
		
		removeByMatch = false;
		runRemovesPub();
		
		Log.info(Log.FAC_TEST, "Completed testRemovesPub");
	}
	
	private void runRemovesPub() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		
		InterestTable<Integer> names = initPub();
		setID(2);
		
		removeMatch(names, abb, 8);
		noRemoveMatch(names, "/a/b/b/a");
		sizes(names, 7, 7);
		noMatch(names, a_bb);
		noRemoveMatch(names, a_bb);
		noMatch(names, abc);
		noRemoveMatch(names, abc);

		names = initPub();
		setID(0);
		
		noMatch(names, _aa);
		noRemoveMatch(names, _aa);
		removeMatch(names, "/a/b/b/a", 4);
		sizes(names, 7, 7);
		
		match(names, abc, 1);
		removeMatches(names, abc, new String[] {a}, new int[] {1});
		sizes(names, 6, 6);
		noMatch(names, "/a/b/c/d");
	    noRemoveMatch(names, "/a/b/b/a");
	    
	    noRemove(names, abb, 8); // Can't remove id 0 version
	    setID(2);
	    remove(names, abb, 8); // Can remove id 2 version
		
	    names = initPub();
	    setID(1);
	    
		noRemoveMatch(names, b);
		match(names, abc, 6);
		removeMatches(names, abc, new String[] {abc, ab}, new int[] {6, 5});
		noRemoveMatch(names, "/a/b/b/a");
		sizes(names, 6, 5);
	}
	
	private enum InterestType {Next, Last, MaxSuffixComponents, Exclude};
	
	private InterestTable<Integer> initInterest(InterestType type) throws MalformedContentNameStringException {
		InterestTable<Integer> table = new InterestTable<Integer>();
		addEntry(table, a, type, new Integer(1));
		addEntry(table, ab, type, new Integer(2));
		addEntry(table, c, type, new Integer(3));
		addEntry(table, b, type, new Integer(4));
		addEntry(table, a_bb, type, new Integer(5));
		addEntry(table, _aa, type, new Integer(6));
		addEntry(table, abc, type, new Integer(7));

		sizes(table, 7, 7);
		return table;
	}
	
	private void addEntry(InterestTable<Integer> table, String name, InterestType type, Integer value) throws MalformedContentNameStringException {
		Interest i = new Interest(ContentName.fromNative(name));
		switch (type) {
		case Next:
			break;
		case Last:
			i.childSelector(Interest.CHILD_SELECTOR_RIGHT);
			break;
		case MaxSuffixComponents:
			i.maxSuffixComponents(additionalComponents);
			break;
		}
		table.add(i, value);
	}
	
	@Test
	public void testMatchNext() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testMatchNext");
		matchNextOrLast(InterestType.Next);
		Log.info(Log.FAC_TEST, "Completed testMatchNext");
	}
	
	@Test
	public void testMatchLast() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testMatchLast");
		matchNextOrLast(InterestType.Last);
		Log.info(Log.FAC_TEST, "Completed testMatchLast");
	}

	public void matchNextOrLast(InterestType type) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		InterestTable<Integer> names = initInterest(type);
		
		setID(1);
		noMatch(names, zero);
		match(names, "/a/b/b/a", 2);
		matches(names, "/a/b/b/a", new String[] {ab, a}, new int[] {2, 1});
		noMatch(names, "/d");
		match(names, "/c/c", 3);
	}
	
	private void runRemovesNextOrLast(InterestType type) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		InterestTable<Integer> names = initInterest(type);
		
		noRemoveMatch(names, zero);
		removeMatch(names, "/a/b/b/a", 2);
		sizes(names, 6, 6);
		noRemoveMatch(names, "/d");
		removeMatch(names, "/c/c", 3);
	}
	
	@Test
	public void testRemovesNext() throws InvalidKeyException, MalformedContentNameStringException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testRemovesNext");

		setID(0);
		removeByMatch = true;
		runRemovesNextOrLast(InterestType.Next);
		
		removeByMatch = false;
		runRemovesNextOrLast(InterestType.Next);
		
		Log.info(Log.FAC_TEST, "Completed testRemovesNext");
	}
	
	@Test
	public void testRemovesLast() throws InvalidKeyException, MalformedContentNameStringException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testRemovesLast");

		setID(0);
		removeByMatch = true;
		runRemovesNextOrLast(InterestType.Last);
		
		removeByMatch = false;
		runRemovesNextOrLast(InterestType.Last);
		
		Log.info(Log.FAC_TEST, "Completed testRemovesLast");
	}
	
	@Test
	public void testLRU() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		Log.info(Log.FAC_TEST, "Starting testLRU");

		InterestTable<Integer> table = new InterestTable<Integer>();
		table.setCapacity(6);
		addEntry(table, a, new Integer(1));
		addEntry(table, ab, new Integer(2));
		addEntry(table, c, new Integer(3));
		addEntry(table, b, new Integer(4));
		addEntry(table, a_bb, new Integer(5));
		addEntry(table, _aa, new Integer(6));
		addEntry(table, ab, new Integer(45));
		addEntry(table, abc, new Integer(7));
		addEntry(table, zero, new Integer(8));
		addEntry(table, onethree, new Integer(9));
		addEntry(table, one, new Integer(10));
		
		match(table, abc, 7);
		matches(table, ab, new String[] {ab, ab}, new int[] {2, 45});
		noMatch(table, a);
		
		Log.info(Log.FAC_TEST, "Completed testLRU");
	}
}
