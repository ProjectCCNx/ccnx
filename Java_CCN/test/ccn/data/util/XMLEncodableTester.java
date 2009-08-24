package test.ccn.data.util;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.TextXMLCodec;
import org.ccnx.ccn.impl.encoding.XMLEncodable;


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

	public static void encodeDecodeByteArrayTest(String label,
			XMLEncodable toEncode,
			XMLEncodable decodeTargetText,
			XMLEncodable decodeTargetBinary) {

		encodeDecodeByteArrayTest(TextXMLCodec.codecName(), label, toEncode, decodeTargetText);
		encodeDecodeByteArrayTest(BinaryXMLCodec.codecName(), label, toEncode, decodeTargetBinary);
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

	public static void encodeDecodeByteArrayTest(String codec,
			String label, 
			XMLEncodable toEncode, 
			XMLEncodable decodeTarget) {
		System.out.println("Encoding " + label + "(" + codec + "):");
		byte [] bao = null;
		try {
			bao = toEncode.encode(codec);
		} catch (XMLStreamException e) {
			handleException(e);
		}
		System.out.println("Encoded " + label + ": " );

		System.out.println("Decoding " + label + ": ");
		try {
			decodeTarget.decode(bao, codec);
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
