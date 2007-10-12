package test.ccn.data;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.XMLEncodable;

public class XMLEncodableTester {

	public static void encodeDecodeTest(String label, XMLEncodable toEncode, XMLEncodable decodeTarget) {
		System.out.println("Encoding " + label);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			toEncode.encode(baos);
		} catch (XMLStreamException e) {
			handleException(e);
		}
		System.out.println("Encoded " + label + ": " );
		System.out.println(baos.toString());
		
		System.out.println("Decoding " + label + ": ");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		try {
			decodeTarget.decode(bais);
		} catch (XMLStreamException e) {
			handleException(e);
		}
		System.out.println("Decoded " + label + ": " + decodeTarget);
		assertEquals(toEncode, decodeTarget);
		
	}
	
	public static void handleException(Exception ex) {
		System.out.println("Got exception of type: " + ex.getClass().getName() + " message: " +
										ex.getMessage());
		ex.printStackTrace();
	}

}
