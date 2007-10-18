package test.ccn.data;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.xml.stream.XMLStreamException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.*;

public class ContentNameTest {

	public String baseName = "test";
	public String subName1 = "briggs";
	public String subName2 = "smetters";
	public String document1 = "test.txt";
	public String document2 = "test2.txt";	
	public byte [] document3 = new byte[]{0x01, 0x02, 0x03, 0x04,
				0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
				0x0d, 0x0e, 0x0f, 0x1f, 0x1b, 0x1c, 0x1d, 0x1e,
				0x1f, 0x2e, 0x3c, 0x4a, 0x5c, 0x6d, 0x7e, 0xf};
	public String escapedSubName1 = "%62%72%69%67%67%73";
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testContentNameString() {
		ContentName name;
		String testString = ContentName.SEPARATOR + baseName + ContentName.SEPARATOR +
				subName1 + ContentName.SEPARATOR + 
				document1;
				
		System.out.println("ContentName: parsing name string \"" + testString+"\"");
		try {
			name = new ContentName(testString);
		} catch (MalformedContentNameStringException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
			name = null;
		}
		assertNotNull(name);
		System.out.println("Name: " + name);
		assertEquals(name.toString(), testString);
	}
	
	@Test(expected=MalformedContentNameStringException.class)
	public void testContentNameStringException() throws MalformedContentNameStringException {
		String testString = "expectingAnException";
		System.out.println("ContentName: parsing name string \"" + testString+"\"");
		new ContentName(testString);
	}
	
	@Test
	public void testContentNameStringArray() throws MalformedContentNameStringException {
		ContentName name;
		ContentName name2;
		String testString = ContentName.SEPARATOR + baseName + ContentName.SEPARATOR +
		subName1 + ContentName.SEPARATOR + 
		document1;
		String [] testStringParts = new String[3];
		testStringParts[0] = baseName;
		testStringParts[1] = subName1;
		testStringParts[2] = document1;
		name = new ContentName(testStringParts);
		name2 = new ContentName(testString);
		assertEquals(name, name2);
	}
	
	@Test
	public void testEncoding() {
		String name1 = ContentName.SEPARATOR + subName1;
		String name2 = ContentName.SEPARATOR + escapedSubName1;
		System.out.println("ContentName: comparing parsed \"" + name1 + "\" and \"" + name2 + "\"");
		try {
			assertEquals(new ContentName(name1), new ContentName(name2));
		} catch (MalformedContentNameStringException e) {
			fail("Unexpected exception MalformedContentNameStringException during ContentName parsing");
		}
	}
	
	@Test
	public void testContentNameByteArrayArray() {
		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		System.out.println("Creating name from byte arrays.");
		ContentName name = new ContentName(3, arr);
		assertNotNull(name);
		System.out.println("Name: " + name);
		arr[3] = document3;
		ContentName name2 = new ContentName(arr);
		assertNotNull(name2);
		System.out.println("Name 2: " + name2);
	}

	@Test
	public void testParent() {
		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		arr[3] = document3;
		ContentName name = new ContentName(arr);
		System.out.println("Name: " + name);
		assertNotNull(name);
		ContentName parent = name.parent();
		assertNotNull(parent);
		System.out.println("Parent: " + parent);
		ContentName grandparent = parent.parent();
		assertNotNull(grandparent);
		System.out.println("Grandparent: " + grandparent);
	}

	@Test
	public void testEncodeOutputStream() {
		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		arr[3] = document3;
		ContentName name = new ContentName(arr);
		System.out.println("Encoding name: " + name);
		assertNotNull(name);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			name.encode(baos);
		} catch (XMLStreamException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Encoded name: " );
		System.out.println(baos.toString());
	}

	@Test
	public void testDecodeInputStream() {
		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		arr[3] = document3;
		ContentName name = new ContentName(arr);
		System.out.println("Encoding name: " + name);
		assertNotNull(name);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			name.encode(baos);
		} catch (XMLStreamException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Encoded name: " );
		System.out.println(baos.toString());
		
		System.out.println("Decoding name: ");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		ContentName name2 = new ContentName();
		try {
			name2.decode(bais);
		} catch (XMLStreamException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Decoded name: " + name2);
		assertEquals(name, name2);
	}

}
