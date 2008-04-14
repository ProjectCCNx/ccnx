package test.ccn.data.util;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.PublisherID.PublisherType;
import com.parc.ccn.data.util.InterestTable;

public class InterestTableTest {


	static public PublisherID ids[] = new PublisherID[3];
	static public PublisherKeyID keyids[] = new PublisherKeyID[3];

	// IDs are parameters that establish test condition so same 
	// code can be run under different conditions
	static public PublisherKeyID activeKeyID = null;
	static public PublisherID activeID = null;
	
	// removeByMatch controls whether remove operations are tested
	// via the removeMatch* (true) or the removeValue* (false) methods 
	// of the InterestTable.  This global parameter permits the conditions
	// to be varied for different runs of the same code.
	static public boolean removeByMatch = true;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		byte [] publisher = new byte[32];

		try {
			Random rnd = new Random();

			for (int i = 0; i < ids.length; i++) {
				rnd.nextBytes(publisher);
				// Note that when this was first written, trust matching
				// could not handle anything more complicated than a KEY
				ids[i] = new PublisherID(publisher, PublisherType.KEY);
				keyids[i] = new PublisherKeyID(publisher);
			}
			
		} catch (Exception ex) {
			ex.printStackTrace();
			System.out.println("Unable To Initialize Test!!!");
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
		Interest intA = new Interest("/a/b/c");
		Interest intB = new Interest("/a/b/c");
		Interest intC = new Interest("/a/b/c/d");
		
		ContentName namA = new ContentName("/a/b/c");
		ContentName namB = new ContentName("/a/b/c");
		ContentName namC = new ContentName("/a/b/c/d");
		
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
	}
		
	private CompleteName getCompleteName(ContentName name) throws ConfigurationException, InvalidKeyException, SignatureException, MalformedContentNameStringException {
		return getCompleteName(name, activeKeyID);
	}
	
	private CompleteName getCompleteName(ContentName name, PublisherKeyID pub) throws ConfigurationException, InvalidKeyException, SignatureException, MalformedContentNameStringException {
		// contents = current date value
		ByteBuffer bb = ByteBuffer.allocate(Long.SIZE/Byte.SIZE);
		bb.putLong(new Date().getTime());
		byte[] contents = bb.array();
		// security bits
		KeyLocator locator = new KeyLocator(new ContentName("/key/" + pub.id().toString()));
		// unique name		
		return CompleteName.generateAuthenticatedName(
				name, pub, ContentAuthenticator.now(),
						ContentAuthenticator.ContentType.LEAF, locator, contents, false, null);
	}
	
	private void match(InterestTable<Integer> table, ContentName name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		// Test both methods
		assertEquals(v, (null == activeKeyID) ? table.getMatch(name).value() :
												 table.getMatch(getCompleteName(name)).value());
		assertEquals(v, (null == activeKeyID) ? table.getValue(name) :
			 									table.getValue(getCompleteName(name)));
	}

	private void match(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		match(table, new ContentName(name), v);
	}
	
	private void removeMatch(InterestTable<Integer> table, ContentName name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		if (removeByMatch) {
			assertEquals(v, table.removeMatch(getCompleteName(name)).value());
		} else {
			assertEquals(v, table.removeValue(getCompleteName(name)));
		}
	}
	
	private void removeMatch(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		removeMatch(table, new ContentName(name), v);
	}
	
	private void remove(InterestTable<Integer> table, ContentName name, int v) {
		if (null == activeKeyID) {
			assertEquals(v, table.remove(name, new Integer(v)).value());
		} else {
			assertEquals(v, table.remove(new Interest(name, activeID), v).value());
		}
	}

	private void remove(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException {
		remove(table, new ContentName(name), v);
	}
	
	private void noRemove(InterestTable<Integer> table, ContentName name, int v) {
		if (null == activeKeyID) {
			assertNull(table.remove(name, new Integer(v)));
		} else {
			assertNull(table.remove(new Interest(name, activeID), v));
		}
	}
	
	private void noRemove(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException {
		noRemove(table, new ContentName(name), v);
	}

	private void noRemoveMatch(InterestTable<Integer> table, ContentName name) throws InvalidKeyException, SignatureException, MalformedContentNameStringException, ConfigurationException {
		assertNull(table.removeMatch(getCompleteName(name)));
		assertNull(table.removeValue(getCompleteName(name)));
	}
	
	private void noRemoveMatch(InterestTable<Integer> table, String name) throws InvalidKeyException, SignatureException, MalformedContentNameStringException, ConfigurationException {
		noRemoveMatch(table, new ContentName(name));
	}
	
	private void noMatch(InterestTable<Integer> table, ContentName name) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		assertNull((null == activeKeyID) ? table.getMatch(name) :
									       table.getMatch(getCompleteName(name)));
		assertNull((null == activeKeyID) ? table.getValue(name) :
		       							   table.getValue(getCompleteName(name)));
	}

	private void noMatch(InterestTable<Integer> table, String name) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		noMatch(table, new ContentName(name));
	}

	private void matches(InterestTable<Integer> table, ContentName name, ContentName[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		List<InterestTable.Entry<Integer>> result = (null == activeKeyID) ? table.getMatches(name) :
																			 table.getMatches(getCompleteName(name));
		assertEquals(v.length, result.size());
		for (int i = 0; i < v.length; i++) {
			assertEquals(v[i], result.get(i).value());
			assertEquals(n[i], result.get(i).name());
		}
		
		List<Integer> values = (null == activeKeyID) ? table.getValues(name) :
			 								table.getValues(getCompleteName(name));
		assertEquals(v.length, values.size());
		for (int i=0; i < v.length; i++) {
			assertEquals(v[i], values.get(i));
		}
	}
	
	private void matches(InterestTable<Integer> table, String name, String[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		ContentName[] cn = new ContentName[n.length];
		for (int i = 0; i < n.length; i++) {
			cn[i] = new ContentName(n[i]);
		}
		matches(table, new ContentName(name), cn, v);
	}
	
	private void removeMatches(InterestTable<Integer> table, ContentName name, ContentName[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		if (removeByMatch) {
			List<InterestTable.Entry<Integer>> result = table.removeMatches(getCompleteName(name));
			assertEquals(v.length, result.size());
			for (int i = 0; i < v.length; i++) {
				assertEquals(v[i], result.get(i).value());
				assertEquals(n[i], result.get(i).name());
			}
		} else {
			List<Integer> result = table.removeValues(getCompleteName(name));
			assertEquals(v.length, result.size());
			for (int i = 0; i < v.length; i++) {
				assertEquals(v[i], result.get(i));
			}
		}
	}

	private void removeMatches(InterestTable<Integer> table, String name, String[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		ContentName[] cn = new ContentName[n.length];
		for (int i = 0; i < n.length; i++) {
			cn[i] = new ContentName(n[i]);
		}
		removeMatches(table, new ContentName(name), cn, v);
	}
	
	private void addEntry(InterestTable<Integer> table, ContentName name, Integer value) throws MalformedContentNameStringException {
		if (null == activeKeyID) {
			table.add(name, value);
		} else {
			table.add(new Interest(name, activeID), value);
		}
	}
	
	private void addEntry(InterestTable<Integer> table, String name, Integer value) throws MalformedContentNameStringException {
		addEntry(table, new ContentName(name), value);
	}

	private void sizes(InterestTable<Integer> table, int s, int n) {
		assertEquals(s, table.size());
		assertEquals(n, table.sizeNames());
	}

	final String a = "/a";
	final String ab = "/a/b";
	final String a_bb = "/a/bb";
	final String abc = "/a/b/c";
	final String abb = "/a/b/b";
	final String b = "/b";
	final String c = "/c";
	final String _aa = "/aa";
	final ContentName zero = new ContentName(new byte[][]{{0x00, 0x02, 0x03, 0x04}});
	final ContentName one = new ContentName(new byte[][]{{0x01, 0x02, 0x03, 0x04}});
	final ContentName onethree = new ContentName(new byte[][]{{0x01, 0x02, 0x03, 0x04}, {0x03}});

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
		matches(names, "/a/b/c/d", new String[] {abc, ab, ab, a}, new int[] {7, 45, 2, 1});
		matches(names, abc, new String[] {abc, ab, ab, a}, new int[] {7, 45, 2, 1});
		matches(names, ab, new String[] {ab, ab, a}, new int[] {45, 2, 1});
		matches(names, a, new String[] {a}, new int[] {1});
		matches(names, a_bb, new String[] {a_bb, a}, new int[] {5, 1});
		matches(names, "/a/b/d", new String[] {ab, ab, a}, new int[] {45, 2, 1});
		matches(names, zero, new ContentName[] {zero}, new int[] {8});
		matches(names, onethree, new ContentName[] {onethree, one}, new int[] {9, 10});
		matches(names, one, new ContentName[] {one}, new int[] {10});

		addEntry(names, abb, new Integer(11));
		sizes(names, 12, 11);
		
		match(names, abb, 11);
		match(names, abc, 7);
		match(names, "/a/b/c/d", 7);
		
		matches(names, "/a/b/c/d", new String[] {abc, ab, ab, a}, new int[] {7, 45, 2, 1});
		matches(names, "/a/b/b/a", new String[] {abb, ab, ab, a}, new int[] {11, 45, 2, 1});

		noMatch(names, "/q");
		
		remove(names, ab, 2);
		sizes(names, 11, 11);
		matches(names, "/a/b/b/a", new String[] {abb, ab, a}, new int[] {11, 45, 1});

	}
	
	@Test
	public void testMatchName() throws InvalidKeyException, MalformedContentNameStringException, SignatureException, ConfigurationException {
		// First test names matching against names, no CompleteName
		setID(-1);
		runMatchName();
		
		// Next run CompleteName against names in table
		setID(0);
		runMatchName();
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
		matches(names, "/a/b/b/a", new String[] {abb, abb, ab, a}, new int[] {8, 4, 5, 1});
	}


	@Test
	public void testSimpleRemoves() throws InvalidKeyException, MalformedContentNameStringException, SignatureException, ConfigurationException {
		removeByMatch = true;
		runSimpleRemoves();
		
		removeByMatch = false;
		runSimpleRemoves();
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

		removeMatches(names, abc, new String[] {abc, ab, ab}, new int[] {7, 45, 2});
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
		removeByMatch = true;
		runRemovesPub();
		
		removeByMatch = false;
		runRemovesPub();
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
}
