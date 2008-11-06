package test.ccn.data;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.BinaryXMLCodec;
import com.parc.ccn.data.util.TextXMLCodec;
import com.parc.ccn.data.util.XMLEncodable;

public class XMLEncodableTester {

	/**
	 * Test both binary and text encodings.
	 * @param label
	 * @param toEncode
	 * @param decodeTarget
	 */
	public static void encodeDecodeTest(String label,
										XMLEncodable toEncode,
										XMLEncodable decodeTargetText,
										XMLEncodable decodeTargetBinary) {
		
		encodeDecodeTest(TextXMLCodec.codecName(), label, toEncode, decodeTargetText);
		encodeDecodeTest(BinaryXMLCodec.codecName(), label, toEncode, decodeTargetBinary);
		// Should match, both match toEncode
		assertEquals(decodeTargetText, decodeTargetBinary);
	}
	
	public static void encodeDecodeTest(String codec,
										String label, 
										XMLEncodable toEncode, 
										XMLEncodable decodeTarget) {
		System.out.println("Encoding " + label + "(" + codec + "):");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			toEncode.encode(baos, codec);
		} catch (XMLStreamException e) {
			handleException(e);
		}
		System.out.println("Encoded " + label + ": " );
		System.out.println(baos.toString());
		
		System.out.println("Decoding " + label + ": ");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		try {
			decodeTarget.decode(bais, codec);
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
