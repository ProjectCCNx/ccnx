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
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.PublisherID.PublisherType;
import com.parc.ccn.data.util.InterestTable;
import com.parc.ccn.security.keys.KeyManager;

public class InterestTableTest {


	static public PublisherID ids[] = new PublisherID[3];
	static public PublisherKeyID keyids[] = new PublisherKeyID[3];

	static public PublisherKeyID activeKeyID = null;
	static public PublisherID activeID = null;

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
	
	private CompleteName getCompleteName(String name, PublisherKeyID pub) throws InvalidKeyException, SignatureException, MalformedContentNameStringException, ConfigurationException {
		return getCompleteName(new ContentName(name), pub);
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
	
	private void matchName(InterestTable<Integer> table, ContentName name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		assertEquals(v, (null == activeKeyID) ? table.getMatch(name).value() :
												 table.getMatch(getCompleteName(name)).value());
	}

	private void matchName(InterestTable<Integer> table, String name, int v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		matchName(table, new ContentName(name), v);
	}
	
	private void noMatch(InterestTable<Integer> table, ContentName name) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		assertNull((null == activeKeyID) ? table.getMatch(name) :
									       table.getMatch(getCompleteName(name)));
	}

	private void noMatch(InterestTable<Integer> table, String name) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		noMatch(table, new ContentName(name));
	}

	private void matchNames(InterestTable<Integer> table, ContentName name, ContentName[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		List<InterestTable.Entry<Integer>> result = (null == activeKeyID) ? table.getMatches(name) :
																			 table.getMatches(getCompleteName(name));
		assertEquals(v.length, result.size());
		for (int i = 0; i < v.length; i++) {
			assertEquals(v[i], result.get(i).value());
			assertEquals(n[i], result.get(i).name());
		}
	}
	
	private void matchNames(InterestTable<Integer> table, String name, String[] n, int[] v) throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		ContentName[] cn = new ContentName[n.length];
		for (int i = 0; i < n.length; i++) {
			cn[i] = new ContentName(n[i]);
		}
		matchNames(table, new ContentName(name), cn, v);
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
	
	private void runMatchName() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {
		String a = "/a";
		String ab = "/a/b";
		String abb = "/a/bb";
		String abc = "/a/b/c";
		String ab_b = "/a/b/b";
		String b = "/b";
		String c = "/c";
		String aa = "/aa";
		ContentName zero = new ContentName(new byte[][]{{0x00, 0x02, 0x03, 0x04}});
		ContentName one = new ContentName(new byte[][]{{0x01, 0x02, 0x03, 0x04}});
		ContentName onethree = new ContentName(new byte[][]{{0x01, 0x02, 0x03, 0x04}, {0x03}});
		
		InterestTable<Integer> names = new InterestTable<Integer>();
		addEntry(names, a, new Integer(1));
		addEntry(names, ab, new Integer(2));
		addEntry(names, c, new Integer(3));
		addEntry(names, b, new Integer(4));
		addEntry(names, abb, new Integer(5));
		addEntry(names, aa, new Integer(6));
		addEntry(names, abc, new Integer(7));
		addEntry(names, zero, new Integer(8));
		addEntry(names, onethree, new Integer(9));
		addEntry(names, one, new Integer(10));
		addEntry(names, ab, new Integer(45));

		assertEquals(11, names.size());
		assertEquals(10, names.sizeNames());

		matchName(names, abb, 5);
		matchName(names, abc, 7);
		matchName(names, ab, 2);
		matchName(names, "/a/b/d", 2);
		matchName(names, "/a/b/c/d", 7);
		matchName(names, ab_b, 2);
		matchName(names, b, 4);
		matchName(names, c, 3);
		matchName(names, one, 10);
		matchName(names, zero, 8);
		matchName(names, onethree, 9);
		matchName(names, "/a/b/b/a", 2);
		
		matchNames(names, aa, new String[] {aa}, new int[] {6});
		matchNames(names, c, new String[] {c}, new int[] {3});
		matchNames(names, "/a/b/c/d", new String[] {abc, ab, ab, a}, new int[] {7, 45, 2, 1});
		matchNames(names, abc, new String[] {abc, ab, ab, a}, new int[] {7, 45, 2, 1});
		matchNames(names, ab, new String[] {ab, ab, a}, new int[] {45, 2, 1});
		matchNames(names, a, new String[] {a}, new int[] {1});
		matchNames(names, abb, new String[] {abb, a}, new int[] {5, 1});
		matchNames(names, "/a/b/d", new String[] {ab, ab, a}, new int[] {45, 2, 1});
		matchNames(names, zero, new ContentName[] {zero}, new int[] {8});
		matchNames(names, onethree, new ContentName[] {onethree, one}, new int[] {9, 10});
		matchNames(names, one, new ContentName[] {one}, new int[] {10});

		addEntry(names, ab_b, new Integer(11));
		assertEquals(12, names.size());
		assertEquals(11, names.sizeNames());

		
		matchName(names, ab_b, 11);
		matchName(names, abc, 7);
		matchName(names, "/a/b/c/d", 7);
		
		matchNames(names, "/a/b/c/d", new String[] {abc, ab, ab, a}, new int[] {7, 45, 2, 1});
		matchNames(names, "/a/b/b/a", new String[] {ab_b, ab, ab, a}, new int[] {11, 45, 2, 1});

		noMatch(names, "/q");
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
	
	@Test
	public void testMatchPub() throws MalformedContentNameStringException, InvalidKeyException, SignatureException, ConfigurationException {

		String a = "/a";
		String ab = "/a/b";
		String abb = "/a/bb";
		String abc = "/a/b/c";
		String ab_b = "/a/b/b";
		String b = "/b";
		String c = "/c";
		String aa = "/aa";

		InterestTable<Integer> names = new InterestTable<Integer>();

		setID(0);
		addEntry(names, a, new Integer(1));
		addEntry(names, b, new Integer(2));
		addEntry(names, abb, new Integer(3));
		addEntry(names, ab_b, new Integer(4));
		
		setID(1);
		addEntry(names, ab, new Integer(5));
		addEntry(names, abc, new Integer(6));
		addEntry(names, aa, new Integer(7));
		
		setID(2);
		addEntry(names, ab_b, new Integer(8));
		
		assertEquals(8, names.size());
		assertEquals(7, names.sizeNames());
		
		setID(2);
		matchName(names, ab_b, 8);
		matchName(names, "/a/b/b/a", 8);
		matchNames(names, "/a/b/b/a", new String[] {ab_b}, new int[] {8});
		noMatch(names, abb);
		noMatch(names, abc);
		matchName(names, ab_b, 8);
		
		setID(0);
		noMatch(names, aa);
		matchName(names, "/a/b/b/a", 4);
		matchName(names, abc, 1);
		matchNames(names, abc, new String[] {a}, new int[] {1});
		matchName(names, "/a/b/c/d", 1);
		matchNames(names, "/a/b/b/a", new String[] {ab_b, a}, new int[] {4, 1});
		matchName(names, ab_b, 4);
		matchNames(names, ab_b, new String[] {ab_b, a}, new int[] {4, 1});
		
		setID(1);
		noMatch(names, b);
		matchName(names, abc, 6);
		matchNames(names, abc, new String[] {abc, ab}, new int[] {6, 5});
		matchName(names, "/a/b/b/a", 5);
		noMatch(names, abb);
	}

}
