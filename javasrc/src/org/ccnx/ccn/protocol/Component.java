package org.ccnx.ccn.protocol;

import static org.ccnx.ccn.profiles.CommandMarker.COMMAND_MARKER_NONCE;

import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Random;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.protocol.ContentName.ComponentProvider;

/**
 * Wrapper class to store immutable name components.
 */
public class Component implements ComponentProvider {

	byte[] component;
	
	protected Component(byte[] comp) {
		component = comp;
	}

	/**
	 * Create a component from a native string.
	 * @param text native text string.
	 */
	public Component(String text) {
		component = parseNative(text);
	}

	public byte[] getComponent() {
		return component;
	}

	/**
	 * Parse native string component: just UTF-8 encode
	 * For full names in native strings only "/" is special
	 * but for an individual component we will even allow that.
	 * This method intentionally throws no declared exceptions
	 * so you can be confident in encoding any native Java String
	 * TODO make this use Java string escaping rules?
	 * @param name Component as native Java string
	 */
	public static byte[] parseNative(String name) {
		// Handle exception s around missing UTF-8
		return DataUtils.getBytesFromUTF8String(name);
	}

	/**
	 * Indicates an attempt to parse a .. component.
	 */
	public static class DotDot extends Exception { // Need to strip off a component
		private static final long serialVersionUID = 4667513234636853164L;
	}

	/**
	 * Parse the URI Generic Syntax of RFC 3986.
	 * Including handling percent encoding of sequences that are not legal character
	 * encodings in any character set.  This method is the inverse of 
	 * printComponent() and for any input sequence of bytes it must be the case
	 * that parseComponent(printComponent(input)) == input.  Note that the inverse
	 * is NOT true printComponent(parseComponent(input)) != input in general.
	 *  
	 * @see fromURI(String)
	 * 
	 * Note in particular that this method interprets sequences of more than
	 * two dots ('.') as representing an empty component or dot component value
	 * as encoded by componentPrint.  That is, the component value will be 
	 * the value obtained by removing three dots.
	 * @param name a single component of a name, URI encoded
	 * @return a name component
	 */
	public static byte[] parseURI(String name) throws DotDot, URISyntaxException {
		byte[] decodedName = null;
		boolean alldots = true; // does this component contain only dots after unescaping?
		boolean quitEarly = false;
	
		ByteBuffer result = ByteBuffer.allocate(name.length());
		for (int i = 0; i < name.length() && !quitEarly; i++) {
			char ch = name.charAt(i);
			switch (ch) {
			case '%': 
				// This is a byte string %xy where xy are hex digits
				// Since the input string must be compatible with the output
				// of componentPrint(), we may convert the character values directly.
				if (name.length()-1 < i+2) {
					throw new URISyntaxException(name, "malformed %xy byte representation: too short", i);
				}
				int b1 = Character.digit(name.charAt(++i), 16); // consume x
				int b2 = Character.digit(name.charAt(++i), 16); // consume y
				if (b1 < 0 || b2 < 0)
					throw new URISyntaxException(name, "malformed %xy byte representation: not legal hex number: " + name.substring(i-2, i+1), i-2);
				result.put((byte)((b1 * 16) + b2));
				break;
				// Note in C lib case 0 is handled like the two general delimiters below that terminate processing 
				// but that case should never arise in Java which uses real unicode characters.
			case '/':
			case '?':
			case '#':
				quitEarly = true; // early exit from containing loop
				break;
			case ':': case '[': case ']': case '@':
			case '!': case '$': case '&': case '\'': case '(': case ')':
			case '*': case '+': case ',': case ';': case '=':
				// Permit unescaped reserved characters
				result.put((byte)ch);
				break;
			default: 
				if (('a' <= ch && ch <= 'z') ||
						('A' <= ch && ch <= 'Z') ||
						('0' <= ch && ch <= '9') ||
						ch == '-' || ch == '.' || ch == '_' || ch == '~') {
					// This character remains the same
					result.put((byte)ch);
				} else {
					throw new URISyntaxException(name, "Illegal characters in URI", i);
				}
				break;
			}
			if (!quitEarly && result.get(result.position()-1) != '.') {
				alldots = false;
			}
		}
		result.flip();
		if (alldots) {
			if (result.limit() <= 1) {
				return null;
			} else if (result.limit() == 2) {
				throw new DotDot();
			} else {
				// Remove the three '.' extra
				result.limit(result.limit()-3);
			}
		}
		decodedName = new byte[result.limit()];
		System.arraycopy(result.array(), 0, decodedName, 0, result.limit());
		return decodedName;
	}

	public static String hexPrint(byte [] bs) {
		if (null == bs)
			return new String();
	
		BigInteger bi = new BigInteger(1,bs);
		return bi.toString(16);
	}

	public static String printNative(byte[] bs) {
		// Native string print is the one place where we can just use
		// Java native platform decoding.  Note that this is not 
		// necessarily invertible, since there may be byte sequences 
		// that do not correspond to any legal native character encoding
		// that may be converted to e.g. Unicode "Replacement Character" U+FFFD.
		return new String(bs);
	}

	public static String printURI(byte [] bs) {
		return printURI(bs, 0, bs.length);
	}

	static final char HEX_DIGITS[] = {
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
	};

	/**
	 * Print bytes in the URI Generic Syntax of RFC 3986 
	 * including byte sequences that are not legal character
	 * encodings in any character set and byte sequences that have special 
	 * meaning for URI resolution per RFC 3986.  This is designed to match
	 * the C library URI encoding.
	 * 
	 * This method must be invertible by parseComponent() so 
	 * for any input sequence of bytes it must be the case
	 * that parseComponent(printComponent(input)) == input.
	 * 
	 * All bytes that are unreserved characters per RFC 3986 are left unescaped.
	 * Other bytes are percent encoded.
	 * 
	 * Empty path components and path components "." and ".." have special 
	 * meaning for relative URI resolution per RFC 3986.  To guarantee 
	 * these component variations are preserved and recovered exactly when
	 * the URI is parsed by parseComponent() we use a convention that 
	 * components that are empty or consist entirely of '.' characters will 
	 * have "..." appended.  This is intended to be consistent with the CCN C 
	 * library handling of URI representation of names.
	 * @param bs input byte array.
	 * @return
	 */
	public static String printURI(byte[] bs, int offset, int length) {
		int i;
		if (null == bs || bs.length == 0) {
			// Empty component represented by three '.'
			return "...";
		}
		// To get enough control over the encoding, we use 
		// our own loop and NOT simply new String(bs) (or java.net.URLEncoder) because
		// the String constructor will decode illegal UTF-8 sub-sequences
		// with Unicode "Replacement Character" U+FFFD.  We could use a CharsetDecoder
		// to detect the illegal UTF-8 sub-sequences and handle them separately,
		// except that this is almost certainly less efficient and some versions of Java 
		// have bugs that prevent flagging illegal overlong UTF-8 encodings (CVE-2008-2938).
		// Also, it is much easier to verify what this is doing and compare to the C library implementation.
		//
		// Initial allocation is based on the documented behavior of StringBuilder's buffer
		// expansion algorithm being 2+2*length if expansion is required.
		StringBuilder result = new StringBuilder((1 + 3 * bs.length) / 2);
		for (i = 0; i < bs.length && bs[i] == '.'; i++) {
			continue;
		}
		if (i == bs.length) {
			// all dots
			result.append("...");
		}
		for (i = 0; i < bs.length; i++) {
			char ch = (char) bs[i];
			if (('a' <= ch && ch <= 'z') ||
					('A' <= ch && ch <= 'Z') ||
					('0' <= ch && ch <= '9') ||
					ch == '-' || ch == '.' || ch == '_' || ch == '~')
				result.append(ch);
			else {
				result.append('%');
				result.append(HEX_DIGITS[(ch >> 4) & 0xF]);
				result.append(HEX_DIGITS[ch & 0xF]);
			}
		}
		return result.toString();
	}

	private static Random random = new Random();
	/**
	 * Generates a random nonce component (with a nonce CommandMarker header).
	 * Can be used in ContentName constructors where a nonce is required.
	 * Note: the nonce component generated will be different every time this
	 * is used.
	 */
	public static final ComponentProvider NONCE = new ComponentProvider() {
		public byte[] getComponent() {
			byte [] nonce = new byte[8];
			random.nextBytes(nonce);
			return COMMAND_MARKER_NONCE.addBinaryData(nonce);
		}
	};

	private static byte[] emptyComponent = new byte[]{ };
	/**
	 * This object generates an empty component (length = 0).
	 */
	public static final ComponentProvider EMPTY = new ComponentProvider() {
		public byte[] getComponent() {
			return emptyComponent;
		}
	};

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof byte[])
			return Arrays.areEqual( (byte[])obj, getComponent() );
		if (obj instanceof ComponentProvider)
			return Arrays.areEqual( ((ComponentProvider)obj).getComponent(), getComponent() );
		if (obj instanceof String)
			return Arrays.areEqual( ((String)obj).getBytes(), getComponent() );
		return super.equals(obj);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(getComponent());
	}

	@Override
	public String toString() {
		return printURI(component);
	}
}
