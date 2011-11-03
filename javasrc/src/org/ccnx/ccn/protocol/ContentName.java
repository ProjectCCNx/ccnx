/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2011 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.protocol;

import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentNameProvider;

/**
 * ContentNames consist of a sequence of byte[] components which may not 
 * be assumed to follow any string encoding, or any other particular encoding.
 * 
 * Warning: This class is designed to be immutable. Some methods expose the internal
 * byte[] components. You must be careful not to change the contents of these byte[]
 * when you receive the values back from this class.
 */
public class ContentName extends GenericXMLEncodable implements XMLEncodable, Comparable<ContentName>, Serializable, ContentNameProvider, Iterable<byte []> {

	private static final long serialVersionUID = 2754391169423477552L;

	/**
	 * Official CCN URI scheme.
	 */
	public static final String SCHEME = "ccnx:";

	/**
	 * This scheme has been deprecated, but we still want to accept it.
	 */
	public static final String ORIGINAL_SCHEME = "ccn:";

	public static final String SEPARATOR = "/";
	public static final ContentName ROOT = new ContentName(0, (ArrayList<byte []>)null);

	protected ArrayList<byte []>  _components;
	public static class DotDotComponent extends Exception { // Need to strip off a component
		private static final long serialVersionUID = 4667513234636853164L;
	}; 
	static final char HEX_DIGITS[] = {
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
	};

	// Constructors
	public ContentName() {
		this(0, (ArrayList<byte[]>)null);
	}

	// support for name builder methods
	static abstract class StringParser {
		public abstract ArrayList<byte[]> parse(String s) throws MalformedContentNameStringException;
	}
	/**
	 * Allows a class to be included as an argument to a ContentName builder.
	 * @see builder
	 */
	public interface ComponentProvider {
		/**
		 * @return Fetches a component. The byte array that is returned must be immutable - i.e. never get changed.
		 * If there is any risk the byte array may be changed you must return a new copy of it.
		 */
		public byte[] getComponent();
	}
	
	/**
	 * When parsing string elements in a ContentName constructor
	 * always interpret the String as a single raw UTF8 component.
	 */
	private static final StringParser constructorStringParser = new StringParser(){
		@Override
		public ArrayList<byte[]> parse(String s) {
			ArrayList<byte[]> components = new ArrayList<byte[]>(1);
			components.add(s.getBytes());
			return components;
		}
	};
	private static final StringParser nativeStringParser = new StringParser(){
		@Override
		public ArrayList<byte[]> parse(String s) throws MalformedContentNameStringException {
			return fromNative(s)._components;
		}
	};
	private static final StringParser uriStringParser = new StringParser(){
		@Override
		public ArrayList<byte[]> parse(String s) throws MalformedContentNameStringException {
			return fromURI(s)._components;
		}
	};

	protected ContentName(ArrayList<byte[]> components) {
		_components = components;
	}

	/**
	 * This Constructor is required to avoid the varargs constructor interpreting
	 * a single byte[] as a list of separate byte arguments.
	 * @param component a single component. The data is cloned, so there is no restriction on
	 * its use after this call.
	 */
	public ContentName(byte[] component) {
		_components = new ArrayList<byte[]>(1);
		_components.add(component.clone());
	}

	/**
	 * Varargs name builder, Strings interpreted as Native.
	 * @see #builder(StringParser, Object[])
	 */
	public static ContentName fromNative(Object... args) throws MalformedContentNameStringException {
		return new ContentName(builder(nativeStringParser, args));
	}

	/**
	 * Varargs name builder, Strings interpreted as URI.
	 * @see #builder(StringParser, Object[])
	 */
	public static ContentName fromURI(Object... args) throws MalformedContentNameStringException {
		return new ContentName(builder(uriStringParser, args));
	}

	/**
	 * Varargs name builder, Strings always represent a single component, interpreted as UTF8.
	 * @see #builder(StringParser, Object[])
	 */
	public ContentName(Object... args) {
		try {
			_components = builder(constructorStringParser, args);
		} catch (MalformedContentNameStringException e) {
			// constructorStringParser won't throw an exception, this should never happen.
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Varargs name builder. Convenience method to allow ContentNames to be constructed from multiple parts.
	 * @param stringParser method to call to parse String arguments
	 * @param args Any number of byte[], String, ContentNameProvider or ComponentProvider arguments
	 * @return an ArrayList of components built by taking components from all the arguments passed in.
	 * @throws MalformedContentNameStringException if a String argument does not parse correctly when passed to stringParser
	 * @see #ContentName(Object... args)
	 * @see #fromNative(Object... args)
	 * @see #fromURL(Object... args)
	 */
	@SuppressWarnings("unchecked")
	protected static ArrayList<byte[]> builder(StringParser stringParser, Object[] args) throws MalformedContentNameStringException {
		int componentCount = 0;

		// first make 1 pass through the arguments validating them and determining the final component count
		for(int i = 0; i < args.length; i++) {
			Object arg = args[i];
			if (arg instanceof byte[]) {
				// incoming byte[] needs to be cloned to ensure ContentName's immutability
				byte[] component = (byte[]) args[i];
				componentCount++;
				args[i] = component.clone();
			} else if (arg instanceof ContentNameProvider) {
				ContentName name = ((ContentNameProvider) arg).getContentName();
				componentCount += name.count();
				args[i] = name._components;
			} else if (arg instanceof String) {
				ArrayList<byte[]> components = stringParser.parse((String) arg);
				args[i] = components;
				componentCount += components.size();
			} else if (arg instanceof ContentName.ComponentProvider) {
				ContentName.ComponentProvider p = (ContentName.ComponentProvider) arg;
				componentCount++;
				args[i] = p.getComponent();
			} else
				throw new IllegalArgumentException("Argument " + i+1 + " is a " + arg.getClass().getSimpleName());
		}

		// allocate an array for the components
		ArrayList<byte[]> components = new ArrayList<byte[]>(args.length);

		// and collect the components into the array
		for(int i = 0; i < args.length; i++) {
			Object arg = args[i];
			if (arg instanceof byte[]) {
				components.add((byte[]) arg);
			} else {
				components.addAll((ArrayList<byte[]>) arg);
			}
		}
		return components;
	}

	public final ContentName getContentName() {
		return this;
	}

	/**
	 * This method is here to avoid a warning when passing a byte[][]
	 * parameter directly into the varargs constructor.
	 */
	public ContentName(byte components[][]) {
		this((Object[]) components);
	}

	/**
	 * Constructor given another ContentName, appends an extra component.
	 * @param parent used for the base of the name, if null, no prefix
	 * 	added.
	 * @param name component to be appended; if null, just copy parent
	 * @deprecated superseded by the {@link ContentName#ContentName(Object...)} varargs constructor.
	 */
	public ContentName(ContentName parent, byte [] name) {
		this(new Object[]{parent, name});
	}

	/**
	 * Constructor given another ContentName, appends extra components.
	 * @param parent used for the base of the name.
	 * @param childComponents components to be appended.
	 * @deprecated superseded by the {@link ContentName#ContentName(Object...)} varargs constructor.
	 */
	@Deprecated
	public ContentName(ContentName parent, byte [][] childComponents) {
		this(parent.count() + 
				((null != childComponents) ? childComponents.length : 0), parent.components());
		if (null != childComponents) {
			for (byte [] b : childComponents) {
				if (null == b)
					continue;
				byte [] c = new byte[b.length];
				System.arraycopy(b,0,c,0,b.length);
				_components.add(c);
			}
		}
	}

	/**
	 * Now that components() returns an ArrayList<byte []>, make a constructor that takes that
	 * as input.
	 * @param parent used for the base of the name.
	 * @param childComponents the additional name components to add at the end of parent
	 * @deprecated superseded by the {@link ContentName#ContentName(Object...)} varargs constructor.
	 */
	@Deprecated
	public ContentName(ContentName parent, ArrayList<byte []> childComponents) {
		this(parent.count() + ((null != childComponents) ? childComponents.size() : 0), parent.components());
		if (null != childComponents) {
			for (byte [] b : childComponents) {
				if (null == b)
					continue;
				byte [] c = new byte[b.length];
				System.arraycopy(b,0,c,0,b.length);
				_components.add(c);
			}
		}
	}

	/**
	 * parent is base name, then add components from childComponets starting
	 * at index "start".
	 * @param parent used for the base of the name.
	 * @param start index in childComponents to begin adding from
	 * @param childComponents the additional name components to add at the end of parent
	 * @deprecated use {@link ContentName#right(int)} and the {@link ContentName#ContentName(Object...)}
	 * varargs constructor instead.
	 */
	@Deprecated
	public ContentName(ContentName parent, int start, ArrayList<byte []> childComponents) {
		// shallow copy
		this(parent.count() + ((null != childComponents) ? childComponents.size() : 0) - start, parent.components());
		if (null != childComponents) {
			for( int i = start; i < childComponents.size(); i++ ) {
				byte [] b = childComponents.get(i);
				if (null == b)
					continue;
				// shallow copy
				_components.add(childComponents.get(i));
			}
		}
	}

	/**
	 * @deprecated superseded by the {@link ContentName#ContentName(Object...)} varargs constructor.
	 */
	public ContentName(ContentName parent, byte[] name1, byte[] name2) {
		this (parent.count() +
				((null != name1) ? 1 : 0) +
				((null != name2) ? 1 : 0), parent.components());
		if (null != name1) {
			byte [] c = new byte[name1.length];
			System.arraycopy(name1,0,c,0,name1.length);
			_components.add(c);
		}
		if (null != name2) {
			byte [] c = new byte[name2.length];
			System.arraycopy(name2,0,c,0,name2.length);
			_components.add(c);
		}
	}

	/**
	 * Constructor for extending or contracting names.
	 * @param count only this number of name components are taken from components.
	 * @param components
	 * @deprecated Use {@link ContentName#cut(int)} instead.
	 */
	@Deprecated
	public ContentName(int count, byte components[][]) {
		if (0 >= count) {
			_components = new ArrayList<byte []>(0);
		} else {
			int max = (null == components) ? 0 : 
				((count > components.length) ? 
						components.length : count);
			_components = new ArrayList<byte []>(count);
			for (int i=0; i < max; ++i) {
				byte [] c = new byte[components[i].length];
				System.arraycopy(components[i],0,c,0,components[i].length);
				_components.add(c);
			}
		}
	}

	/**
	 * Constructor for extending or contracting names.
	 * Performs a faster shallow copy of the components, as we don't tend to alter name components
	 * once created.
	 * @param count Only this number of name components are copied into the new name.
	 * @param components These are the name components to be copied. Can be null, empty, or longer or shorter than count.
	 * @deprecated Use {@link ContentName#cut(int)} instead.
	 */
	@Deprecated
	public ContentName(int count, ArrayList<byte []>components) {
		if (0 >= count) {
			_components = new ArrayList<byte[]>(0);
		} else {
			int max = (null == components) ? 0 : 
				((count > components.size()) ? 
						components.size() : count);
			_components = new ArrayList<byte []>(count);
			for (int i=0; i < max; ++i) {
				_components.add(components.get(i));
			}
		}
	}

	/**
	 * Subname constructor for extending or contracting names, extracts particular
	 * subcomponents from an existing set.
	 * Performs a faster shallow copy of the components, as we don't tend to alter name components
	 * once created.
	 * @param start This is index (0-based) of the first component to copy.
	 * @param count Only this number of name components are copied into the new name. If count-start is
	 * 	greater than the last component in the components array, only copies count-start.
	 * @param components These are the name components to be copied. Can be null, empty, or longer or shorter than count.
	 * @deprecated Use {@link ContentName#subname(int, int)} instead.
	 */
	@Deprecated
	public ContentName(int start, int count, ArrayList<byte []>components) {
		if (0 >= count) {
			_components = new ArrayList<byte[]>(0);
		} else {
			int max = (null == components) ? 0 : 
				((count > (components.size()-start)) ? 
						(components.size()-start) : count);
			_components = new ArrayList<byte []>(max);
			for (int i=start; i < max+start; ++i) {
				_components.add(components.get(i));
			}
		}
	}

	/**
	 * Copy constructor, also used by subclasses merely wanting a different
	 * name in encoding/decoding.
	 * @param otherName
	 */
	public ContentName(ContentName otherName) {
		this(((null == otherName) ? 0 : otherName.count()), ((null == otherName) ? null : otherName.components()));
	}

	/**
	 * Return the <code>ContentName</code> represented by the given URI.
	 * A CCN <code>ContentName</code> consists of a sequence of binary components
	 * of any length (including 0), which allows such things as encrypted
	 * name components.  It is often convenient to work with string 
	 * representations of names in various forms.
	 * <p> 
	 * The canonical String representation of a CCN <code>ContentName</code> is a 
	 * URI encoding of the name according to RFC 3986 with the addition 
	 * of special treatment for name components of 0 length or containing 
	 * only one or more of the byte value 0x2E, which is the US-ASCII
	 * encoding of '.'.  The major features of the URI encoding are the 
	 * use of a limited set of characters and the use of percent-encoding 
	 * to encode all other byte values.  The combination of percent-encoding
	 * and special treatment for certain name components allows the 
	 * canonical CCN string representation to encode all possible CCN names.
	 * <p>
	 * The legal characters in the URI are limited to the <i>unreserved</i> characters 
	 * "a" through "z", "A" through "Z", "0" through "9", and "-", "_", ".", and "~"
	 * plus the <i>reserved</i> delimiters  "!", "$" "&", "'", "(", ")",
	 * "*", "+", ",", ";", "=".
	 * The reserved delimiter "/" is a special case interpreted as component separator and so
	 * may not be used within a component unescaped.
	 * Any query (starting '?') or fragment (starting '#') is ignored which means that these
	 * reserved delimiters must be percent-encoded if they are to be part of the name. 
	 * <p>
	 * The URI must begin with either the "/" delimiter or the scheme specification "ccnx:"
	 * plus delimiter to make URI absolute.
	 * <p>
	 * The decoding from a URI String to a ContentName translates each legal 
	 * character to its US-ASCII byte encoding, except for the "." which is subject 
	 * to special handling described below.  Any other byte value in a component 
	 * (including those corresponding to "/" and ":") must be percent-encoded in 
	 * the URI.  Any character sequence starting with "?" or "#" is discarded (to the
	 * end of the component).
	 * <p>
	 * The resolution rules for relative references are applied in this 
	 * decoding:
	 * <ul>
	 * <li> "//" in the URI is interpreted as "/"
	 * <li> "/./" and "/." in the URI are interpreted as "/" and ""
	 * <li> "/../" and "/.." in the URI are interpreted as removing the preceding component
	 * </ul>
	 * <p>
	 * Any component of 0 length, or containing only one or more of the byte 
	 * value 0x2E ("."), is represented in the URI by one "." per byte plus the 
	 * suffix "..." which provides unambiguous representation of all possible name
	 * components in conjunction with the use of the resolution rules given above. 
	 * Thus the decoding from URI String to ContentName makes conversions such as:
	 * <ul>
	 * <li> "/.../" in the URI is converted to a 0-length name component
	 * <li> "/..../" in the URI is converted to the name component {0x2E}
	 * <li> "/...../" in the URI is converted to the name component {0x2E, 0x2E}
	 * <li> "/....../" in the URI is converted to the name component {0x2E, 0x2E, 0x2E}
	 * </ul>
	 * <p>
	 * Note that this URI encoding is very similar to but not the same as the 
	 * application/x-www-form-urlencoded MIME format that is used by the Java 
	 * java.net.URLDecoder.
	 * 
	 * TODO: Inconsistent with C lib in that it does not strip authority part
	 * TODO: Inconsistent with C lib in that it does not fully strip query and fragment parts (within component only)
	 * @param name
	 * @return
	 * @throws MalformedContentNameStringException
	 */
	public static ContentName fromURI(String name) throws MalformedContentNameStringException {
		try {
			ContentName result;
			if ((name == null) || (name.length() == 0)) {
				result = new ContentName(0, (ArrayList<byte[]>)null);
			} else {
				String[] parts;
				String justname = name;
				if (!name.startsWith(SEPARATOR)){
					if ((!name.startsWith(SCHEME + SEPARATOR)) && (!name.startsWith(ORIGINAL_SCHEME + SEPARATOR))) {
						throw new MalformedContentNameStringException("ContentName strings must begin with " + SEPARATOR + " or " + SCHEME + SEPARATOR);
					}
					if (name.startsWith(SCHEME)) {
						justname = name.substring(SCHEME.length());
					} else if (name.startsWith(ORIGINAL_SCHEME)) {
						justname = name.substring(ORIGINAL_SCHEME.length());
					}
				}
				parts = justname.split(SEPARATOR);
				if (parts.length == 0) {
					// We've been asked to parse the root name.
					result = new ContentName(0, (ArrayList<byte[]>)null);
				} else {
					result = new ContentName(parts.length - 1, (ArrayList<byte[]>)null);
				}
				// Leave off initial empty component
				for (int i=1; i < parts.length; ++i) {
					try {
						byte[] component = Component.parseURI(parts[i]);
						if (null != component) {
							result._components.add(component);
						}
					} catch (DotDotComponent c) {
						// Need to strip "parent"
						if (result._components.size() < 1) {
							throw new MalformedContentNameStringException("ContentName string contains too many .. components: " + name);
						} else {
							result._components.remove(result._components.size()-1);
						}
					}
				}
			}
			return result;
		} catch (URISyntaxException e) {
			throw new MalformedContentNameStringException(e.getMessage());
		}
	}

	/**
	 * Given an array of strings, apply URI decoding and create a ContentName
	 * @see fromURI(String)
	 * @throws MalformedContentNameStringException
	 */
	public static ContentName fromURI(String parts[]) throws MalformedContentNameStringException {
		try {
			ContentName result;
			if ((parts == null) || (parts.length == 0)) {
				result = new ContentName(0, (ArrayList<byte[]>)null);
			} else {
				result = new ContentName(parts.length, (ArrayList<byte[]>)null);
				for (int i=0; i < parts.length; ++i) {
					try {
						byte[] component = Component.parseURI(parts[i]);
						if (null != component) {
							result._components.add(component);
						}
					} catch (DotDotComponent c) {
						// Need to strip "parent"
						if (result._components.size() < 1) {
							throw new MalformedContentNameStringException("ContentName parts contains too many .. components");
						} else {
							result._components.remove(result._components.size()-1);
						}					
					}
				}
			}
			return result;
		} catch (URISyntaxException e) {
			throw new MalformedContentNameStringException(e.getMessage());
		}
	}

	/**
	 * Return the <code>ContentName</code> created by appending one component
	 * to the supplied parent.  The new component is converted from URI 
	 * string encoding.
	 * @see fromURI(String)
	 * @param parent used for the base of the name.
	 * @param name sequence of URI encoded name components, appended to the base.
	 * @throws MalformedContentNameStringException
	 */
	public static ContentName fromURI(ContentName parent, String name) throws MalformedContentNameStringException {
		try {
			ContentName result = new ContentName(parent.count() + ((null != name) ? 1 : 0), parent.components());
			if (null != name) {
				try {
					byte[] decodedName = Component.parseURI(name);
					if (null != decodedName) {
						result._components.add(decodedName);
					}
				} catch (DotDotComponent c) {
					// Need to strip "parent"
					if (result._components.size() < 1) {
						throw new MalformedContentNameStringException("ContentName parts contains too many .. components");
					} else {
						result._components.remove(result._components.size()-1);
					}									
				}
			}
			return result;
		} catch (URISyntaxException e) {
			throw new MalformedContentNameStringException(e.getMessage());
		}
	}

	/**
	 * Return the <code>ContentName</code> created from a native Java String.
	 * In native strings only "/" is special, interpreted as component delimiter,
	 * while all other characters will be encoded as UTF-8 in the output <code>ContentName</code>
	 * Native String representations do not incorporate a URI scheme, and so must
	 * begin with the component delimiter "/".
	 * TODO use Java string escaping rules?
	 * @param name
	 * @throws MalformedContentNameStringException if name does not start with "/"
	 */
	public static ContentName fromNative(String name) throws MalformedContentNameStringException {
		ContentName result;
		if (!name.startsWith(SEPARATOR)){
			throw new MalformedContentNameStringException("ContentName native strings must begin with " + SEPARATOR);
		}
		if ((name == null) || (name.length() == 0)) {
			result = new ContentName(0, (ArrayList<byte[]>)null);
		} else {
			String[] parts;
			parts = name.split(SEPARATOR);
			if (parts.length == 0) {
				// We've been asked to parse the root name.
				result = new ContentName(0, (ArrayList<byte[]>)null);
			} else {
				result = new ContentName(parts.length - 1, (ArrayList<byte[]>)null);
			}
			// Leave off initial empty component
			for (int i=1; i < parts.length; ++i) {
				byte[] component = Component.parseNative(parts[i]);
				if (null != component) {
					result._components.add(component);
				}
			}
		}
		return result;
	}

	/**
	 * Return the <code>ContentName</code> created by appending one component
	 * to the supplied parent.
	 * This method intentionally throws no declared exceptions
	 * so you can be confident in encoding any native Java String.
	 * @param parent used for the base of the name.
	 * @param name Native Java String which will be encoded as UTF-8 in the output
	 * <code>ContentName</code>
	 */
	public static ContentName fromNative(ContentName parent, String name) {
		ContentName result = new ContentName(parent.count() + ((null != name) ? 1 : 0), parent.components());
		if (null != name) {
			byte[] decodedName = Component.parseNative(name);
			if (null != decodedName) {
				result._components.add(decodedName);
			}
		} 
		return result;
	}

	public static ContentName fromNative(ContentName parent, byte [] name) {
		ContentName result = new ContentName(parent.count() + 1, parent.components());
		result._components.add(name);
		return result;
	}

	public static ContentName fromNative(ContentName parent, String name1, String name2) {
		return fromNative(parent, new String[]{name1, name2});
	}

	public static ContentName fromNative(String [] parts) {
		return fromNative(null, parts);
	}

	public static ContentName fromNative(ContentName parent, String [] parts) {
		int extra = (null != parts) ? parts.length : 0;
		int parentCount = (null != parent) ? parent.count() : 0;
		ContentName result = new ContentName(parentCount + extra, (null != parent) ? parent.components() : null);
		if ((null != parts) && (parts.length > 0)) {
			for (int i=0; i < parts.length; ++i) {
				byte[] component = Component.parseNative(parts[i]);
				if (null != component) {
					result._components.add(component);
				}
			}
		}
		return result;
	}

	public ContentName clone() {
		return new ContentName(count(), components());
	}

	/**
	 * Returns a new name with the last component removed.
	 */
	public ContentName parent() {
		return new ContentName(count()-1, components());
	}

	public String toString() {
		if (null == _components) return null;
		// toString of root name is "/"
		if (0 == _components.size()) return SEPARATOR;
		StringBuffer nameBuf = new StringBuffer();
		for (int i=0; i < _components.size(); ++i) {
			nameBuf.append(SEPARATOR);
			nameBuf.append(Component.printURI(_components.get(i)));
		}
		return nameBuf.toString();
	} 

	/**
	 * Print as string with scheme in front. toString already
	 * prints in URI format with leading /, just add scheme.
	 */
	public String toURIString() {
		return SCHEME + toString();
	}

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
	 * @deprecated Use {@link Component#printURI(byte[],int,int)} instead
	 */
	@Deprecated
	public static String componentPrintURI(byte[] bs, int offset, int length) {
		return Component.printURI(bs, offset, length);
	}

	/**
	 * @deprecated Use {@link Component#printURI(byte[])} instead
	 */
	@Deprecated
	public static String componentPrintURI(byte [] bs) {
		return Component.printURI(bs);
	}

	/**
	 * @deprecated Use {@link Component#printNative(byte[])} instead
	 */
	@Deprecated
	public static String componentPrintNative(byte[] bs) {
		return Component.printNative(bs);
	}

	/**
	 * @deprecated Use {@link Component#hexPrint(byte[])} instead
	 */
	@Deprecated
	public static String hexPrint(byte [] bs) {
		return Component.hexPrint(bs);
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
	 * @deprecated Use {@link Component#parseURI(String)} instead
	 */
	@Deprecated
	public static byte[] componentParseURI(String name) throws DotDotComponent, URISyntaxException {
		return Component.parseURI(name);
	}

	/**
	 * Parse native string component: just UTF-8 encode
	 * For full names in native strings only "/" is special
	 * but for an individual component we will even allow that.
	 * This method intentionally throws no declared exceptions
	 * so you can be confident in encoding any native Java String
	 * TODO make this use Java string escaping rules?
	 * @param name Component as native Java string
	 * @deprecated Use {@link Component#parseNative(String)} instead
	 */
	@Deprecated
	public static byte[] componentParseNative(String name) {
		return Component.parseNative(name);
	}

	/**
	 * @return
	 * Warning: this returns the internal byte arrays used in the ContentName component representation, without copying them.
	 * You must not modify the contents of these byte arrays, or you will break the immutability
	 * of ContentNames, which can have many problematic consequences.
	 */
	@Deprecated
	public ArrayList<byte[]> components() { return _components; }

	/**
	 * @return The number of components in the name.
	 */
	public int count() { 
		if (null == _components) return 0;
		return _components.size(); 
	}

	/**
	 * Append a segmented name to this name.
	 */
	public ContentName append(ContentName other) {
		return new ContentName(this, other.components());
	}

	/**
	 * Append a name to this one, where the child name might have more than one
	 * path component -- e.g. foo/bar/bash. Will add leading / to postfix for
	 * parsing, if one not present.
	 * @throws MalformedContentNameStringException 
	 */
	public ContentName append(String postfix) throws MalformedContentNameStringException {
		if (!postfix.startsWith("/")) {
			postfix = "/" + postfix;
		}
		ContentName postfixName = ContentName.fromNative(postfix);
		return this.append(postfixName);
	}

	/**
	 * Get the i'th component, indexed from 0.
	 * @param i
	 * @return null if i is out of range.
	 * Warning: this returns the internal byte array used in the ContentName component representation.
	 * You must not modify the contents of this byte array, or you will break the immutability
	 * of ContentNames, which can have many problematic consequences.
	 */
	public final byte[] component(int i) { 
		if ((null == _components) || (i >= _components.size())) return null;
		return _components.get(i);
	}

	/**
	 * @return null if there are no components.
	 * Warning: this returns the internal byte array used in the ContentName component representation.
	 * You must not modify the contents of this byte array, or you will break the immutability
	 * of ContentNames, which can have many problematic consequences.
	 */
	public final byte [] lastComponent() {
		if (null == _components || _components.size() == 0)
			return null;
		return _components.get(_components.size()-1);
	}

	/**
	 * @return The i'th component, converted using URI encoding.
	 */
	public String stringComponent(int i) {
		if ((null == _components) || (i >= _components.size())) return null;
		return Component.printURI(_components.get(i));
	}

	/**
	 * Used by NetworkObject to decode the object from a network stream.
	 * @see org.ccnx.ccn.impl.encoding.XMLEncodable
	 */
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());

		_components = new ArrayList<byte []>();

		while (decoder.peekStartElement(CCNProtocolDTags.Component)) {
			_components.add(decoder.readBinaryElement(CCNProtocolDTags.Component));
		}

		decoder.readEndElement();
	}

	/**
	 * Test if this name is a prefix of another name - i.e. do all components in this name exist in the
	 * name being compared with. Note there do not need to be any more components in the name
	 * being compared with. 
	 * @param name name being compared with.
	 */
	public boolean isPrefixOf(ContentName name) {
		return isPrefixOf(name, count());
	}

	/**
	 * Tests if the first n components are a prefix of name
	 * @param name
	 * @param count number of components to check
	 */
	public boolean isPrefixOf(ContentName name, int count) {
		if (null == name)
			return false;
		if (count > name.count())
			return false;
		for (int i=0; i < count; ++i) {
			if (!Arrays.equals(name.component(i), component(i)))
				return false;
		}
		return true;
	}

	/**
	 * Compare our name to the name of the ContentObject.
	 * If our name is 1 component longer than the ContentObject
	 * and no prefix count is set, our name might contain a digest.
	 * In that case, try matching the content to the last component as
	 * a digest.
	 * 
	 * @param other
	 * @return
	 */

	public boolean isPrefixOf(ContentObject other) {
		return isPrefixOf(other, count());
	}

	public boolean isPrefixOf(ContentObject other, int count) {
		boolean match = isPrefixOf(other.name(), count);
		if (match || count() != count)
			return match;
		if (count() == other.name().count() + 1) {
			if (DataUtils.compare(component(count() - 1), other.digest()) == 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * hashCode and equals not auto-generated, ArrayList does not do the right thing.
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (! (obj instanceof ContentName)) 
			return false;
		final ContentName other = (ContentName)obj;
		if (other.count() != this.count())
			return false;
		int count = count();
		for (int i=0; i < count; ++i) {
			if (!Arrays.equals(other.component(i), this.component(i)))
				return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		int count = count();
		for (int i=0; i < count; ++i) {
			result = prime * result + Arrays.hashCode(component(i));			
		}
		return result;
	}

	/**
	 * Parses the canonical URI representation.
	 * @param str
	 * @return
	 * @throws MalformedContentNameStringException
	 */
	public static ContentName parse(String str) throws MalformedContentNameStringException {
		if(str == null) return null;
		if(str.length() == 0) return ROOT;
		return fromURI(str);
	}

	/**
	 * Uses the canonical URI representation
	 * @param str
	 * @return
	 */
	public boolean contains(String str) throws URISyntaxException {
		try {
			byte[] parsed = Component.parseURI(str);
			if (null == parsed) {
				return false;
			} else {
				return contains(parsed);
			}
		} catch (DotDotComponent c) {
			return false;
		}
	}

	public boolean contains(byte [] component) {
		return (containsWhere(component) >= 0);
	}

	/**
	 * Looks for a component.
	 * @param str Component to search for, encoded using URI encoding.
	 * @return The index of the first component that matched. Starts at 0.
	 * @throws URISyntaxException
	 */
	public int containsWhere(String str) throws URISyntaxException {
		try {
			byte[] parsed = Component.parseURI(str);
			if (null == parsed) {
				return -1;
			} else {
				return containsWhere(parsed);
			}
		} catch (DotDotComponent c) {
			return -1;
		}
	}

	/**
	 * Looks for a component, starting from the end..
	 * @param str Component to search for, encoded using URI encoding.
	 * @return The index of the first component that matched. Starts at 0.
	 * @throws URISyntaxException
	 */
	public int whereLast(String str) throws URISyntaxException {
		try {
			byte[] parsed = Component.parseURI(str);
			if (null == parsed) {
				return -1;
			} else {
				return whereLast(parsed);
			}
		} catch (DotDotComponent c) {
			return -1;
		}
	}

	/**
	 * Return component index of the first matching component if it exists.
	 * @param component Component to search for.
	 * @return -1 on failure, component index otherwise (starts at 0).
	 */
	public int containsWhere(byte [] component) {
		int i=0;
		boolean result = false;
		for (i=0; i < _components.size(); ++i) {
			if (Arrays.equals(_components.get(i),component)) {
				result = true;
				break;
			}	
		}
		if (result)
			return i;
		return -1;		
	}

	/**
	 * Return component index of the last matching component if it exists.
	 * @param component Component to search for.
	 * @return -1 on failure, component index otherwise (starts at 0).
	 */
	public int whereLast(byte [] component) {
		int i=0;
		boolean result = false;
		for (i=_components.size()-1; i >= 0; --i) {
			if (Arrays.equals(_components.get(i),component)) {
				result = true;
				break;
			}	
		}
		if (result)
			return i;
		return -1;		
	}
	
	/**
	 * Does a component of the ContentName startWith value?
	 * 
	 * @param value
	 * @return
	 */
	public boolean startsWith(byte [] value) {
		return (startsWithWhere(value) >= 0);
	}
	
	/**
	 * Return component index of first component that starts with argument value
	 * 
	 * @param value
	 * @return
	 */
	public int startsWithWhere(byte [] value) {
		int i=0;
		int size = value.length;
		for (i=0; i < _components.size(); ++i) {
			byte [] component = _components.get(i);
			if (size <= component.length) {
				boolean result = true;
				for (int j = 0; j < size; j++) {
					if (component[j] != value[j]) {
						result = false;
						break;
					}
				}
				if (result)
					return i;
			}
		}
		return -1;		
	}
	
	/**
	 * Return the first componentNumber components of this name as a new name.
	 * @param componentNumber
	 * @return
	 */
	public ContentName cut(int componentCount) {
		if ((componentCount < 0) || (componentCount > count())) {
			throw new IllegalArgumentException("Illegal component count: " + componentCount);
		}
		if (componentCount == count())
			return this;
		return new ContentName(componentCount, this.components());
	}

	/**
	 * @param position component number to include as first component in the new name.
	 * Starting with component 0.
	 * @return A new name using the components starting from position.
	 */
	public ContentName right(int position) {
		if ((position < 0) || (position > count())) {
			throw new IllegalArgumentException("Illegal component count: " + position);
		}
		if (position == 0)
			return this;
		return new ContentName(position, _components.size()-position, _components);
	}

	/**
	 * Slice the name off right before the given component
	 * @param component
	 */
	public ContentName cut(byte [] component) {
		int offset = this.containsWhere(component);

		if (offset < 0) {
			// unfragmented
			return this;
		}

		// else need to cut it
		return new ContentName(offset, this.components());
	}

	/**
	 * Slice the name off right before the given component
	 * @param component In URI encoded form.
	 */
	public ContentName cut(String component) throws URISyntaxException {
		try {
			byte[] parsed = Component.parseURI(component);
			if (null == parsed) {
				return this;
			} else {
				return cut(parsed);
			}
		} catch (DotDotComponent c) {
			return this;
		}
	}

	/**
	 * Return a subname of this name as a new name.
	 * @param start the starting component index (0-based)
	 * @param componentCount the number of components to include beginning with start.
	 * @return the new name.
	 */
	public ContentName subname(int start, int componentCount) {
		return new ContentName(start, componentCount, components());
	}

	/**
	 * Return the remainder of this name after the prefix, if the prefix
	 * is a prefix of this name. Otherwise return null. If the prefix is
	 * identical to this name, return the root (empty) name.
	 */
	public ContentName postfix(ContentName prefix) {
		if (!prefix.isPrefixOf(this))
			return null;

		if (prefix.count() == count()) {
			return ROOT;
		}

		return subname(prefix.count(), count()-prefix.count());
	}

	/**
	 * Used by NetworkObject to encode the object to a network stream.
	 * @see org.ccnx.ccn.impl.encoding.XMLEncodable
	 */
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}

		encoder.writeStartElement(getElementLabel());
		int count = count();
		for (int i=0; i < count; ++i) {
			encoder.writeElement(CCNProtocolDTags.Component, _components.get(i));
		}
		encoder.writeEndElement();
	}

	@Override
	public boolean validate() { 
		return (null != _components);
	}

	@Override
	public long getElementLabel() { 
		return CCNProtocolDTags.Name;
	}

	public ContentName copy(int nameComponentCount) {
		return new ContentName(nameComponentCount, this.components());
	}

	public int compareTo(ContentName o) {
		if (this == o)
			return 0;
		int thisCount = this.count();
		int oCount = o.count();
		int len = (thisCount > oCount) ? thisCount : oCount;
		int componentResult;
		for (int i=0; i < len; ++i) {
			componentResult = DataUtils.compare(this.component(i), o.component(i));
			if (0 != componentResult)
				return componentResult;
		}
		return 0;
	}

	/**
	 * This returns the components of the name.
	 * Warning - the iterator directly returns the underlying byte arrays used in the name to
	 * hold the components. These must not be modified (they may be reused in multiple places,
	 * and are assumed not to change).
	 * @return iterator over the name components.
	 */
	@Override
	public Iterator<byte[]> iterator() {
		_components.iterator();
		return null;
	}
}
