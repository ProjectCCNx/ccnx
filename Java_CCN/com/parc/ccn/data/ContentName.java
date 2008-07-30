package com.parc.ccn.data;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

public class ContentName extends GenericXMLEncodable implements XMLEncodable, Comparable<ContentName> {

	public static final String SEPARATOR = "/";
	public static final ContentName ROOT = new ContentName(0, (ArrayList<byte []>)null);
	private static final String CONTENT_NAME_ELEMENT = "Name";
	private static final String COMPONENT_ELEMENT = "Component";
	
	protected ArrayList<byte []>  _components;
		
	public ContentName(byte components[][]) {
		if (null == components) {
			_components = null;
		} else {
			_components = new ArrayList<byte []>(components.length);
			for (int i=0; i < components.length; ++i) {
				_components.add(components[i].clone());
			}
		}
	}
		
	public ContentName(String name) throws MalformedContentNameStringException {
		if((name == null) || (name.length() == 0)) {
			_components = null;
		} else {
			String[] parts;
			if (!name.startsWith(SEPARATOR)){
				throw new MalformedContentNameStringException("ContentName strings must begin with " + SEPARATOR);
			}
			parts = name.split(SEPARATOR);
			if (parts.length == 0) {
				// We've been asked to parse the root name.
				_components = new ArrayList<byte []>(0);
			} else {
				_components = new ArrayList<byte []>(parts.length - 1);
			}
			// Leave off initial empty component
			for (int i=1; i < parts.length; ++i) {
				_components.add(componentParse(parts[i]));
			}
		}
	}
	
	public ContentName(String parts[]) {
		if ((parts == null) || (parts.length == 0)) {
			_components = null;
		} else {
			_components = new ArrayList<byte []>(parts.length);
			for (int i=0; i < parts.length; ++i) {
				_components.add(componentParse(parts[i]));
			}
		}
	}

	public ContentName(ContentName parent, String name) {
		this(parent.count() + 
				((null != name) ? 1 : 0), parent.components());
		if (null != name) {
			byte[] decodedName = componentParse(name);
			_components.add(decodedName);
		}
	}
	
	public ContentName(ContentName parent, byte [] name) {
		this(parent.count() + 
				((null != name) ? 1 : 0), parent.components());
		if (null != name) {
			byte [] c = new byte[name.length];
			System.arraycopy(name,0,c,0,name.length);
			_components.add(c);
		}
	}
	
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
	 * Basic constructor for extending or contracting names.
	 * @param count
	 * @param components
	 */
	public ContentName(int count, byte components[][]) {
		if (0 >= count) {
			_components = new ArrayList<byte []>(0);
		} else {
			int max = (null == components) ? 0 : 
				  		((count > components.length) ? 
				  				components.length : count);
			_components = new ArrayList<byte []>(max);
			for (int i=0; i < max; ++i) {
				byte [] c = new byte[components[i].length];
				System.arraycopy(components[i],0,c,0,components[i].length);
				_components.add(c);
			}
		}
	}
	
	
	/**
	 * Basic constructor for extending or contracting names.
	 * Shallow copy, as we don't tend to alter name components
	 * once created.
	 * @param count
	 * @param components
	 */
	public ContentName(int count, ArrayList<byte []>components) {
		if (0 >= count) {
			_components = new ArrayList<byte[]>(0);
		} else {
			int max = (null == components) ? 0 : 
				  		((count > components.size()) ? 
				  				components.size() : count);
			_components = new ArrayList<byte []>(max);
			for (int i=0; i < max; ++i) {
				_components.add(components.get(i));
			}
		}
	}
	
	public ContentName() {
		this(0, (ArrayList<byte[]>)null);
	}
		
	public ContentName clone() {
		return new ContentName(count(), components());
	}
		
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
			nameBuf.append(componentPrint(_components.get(i)));
		}
		return nameBuf.toString();
	} 
	
	public static String componentPrint(byte[] bs) {
		// NHB: Van is expecting the URI encoding rules
		if (null == bs) {
			return new String();
		}
		try {
			return URLEncoder.encode(new String(bs), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("UTF-8 not supported", e);
		}
	}
	
	public static String hexPrint(byte [] bs) {
		if (null == bs)
			return new String();
		
		BigInteger bi = new BigInteger(1,bs);
		return bi.toString(16);
	}

	public static byte[] componentParse(String name) {
		byte[] decodedName = null;
		try {
			decodedName = URLDecoder.decode(name, "UTF-8").getBytes();
		} catch (UnsupportedEncodingException e) {
			Library.logger().severe("UTF-8 not supported.");
			throw new RuntimeException("UTF-8 not supported", e);
		}
		return decodedName;
	}

	public ArrayList<byte[]> components() { return _components; }
	
	public int count() { 
		if (null == _components) return 0;
		return _components.size(); 
	}

	public byte[] component(int i) { 
		if ((null == _components) || (i >= _components.size())) return null;
		return _components.get(i);
	}
	
	public String stringComponent(int i) {
		if ((null == _components) || (i >= _components.size())) return null;
		return componentPrint(_components.get(i));
	}
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(CONTENT_NAME_ELEMENT);
		
		_components = new ArrayList<byte []>();
		
		while (decoder.peekStartElement(COMPONENT_ELEMENT)) {
			_components.add(decoder.readBinaryElement(COMPONENT_ELEMENT));
		}
		
		decoder.readEndElement();
	}

	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final ContentName other = (ContentName)obj;
		if (other.count() != this.count())
			return false;
		for (int i=0; i < count(); ++i) {
			if (!Arrays.equals(other.component(i), this.component(i)))
					return false;
		}
		return true;
	}

	/**
	 * Check prefix match up to the first componentCount 
	 * components.
	 * @param obj
	 * @param componentCount if larger than the number of
	 * 	  components, take this as the whole thing.
	 * @return
	 */
	public boolean equals(ContentName obj, int componentCount) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if ((componentCount > this.count()) && 
				(obj.count() != this.count()))
			return false;
		for (int i=0; i < componentCount; ++i) {
			if (!Arrays.equals(obj.component(i), this.component(i)))
					return false;
		}
		return true;
	}

	public static ContentName parse(String str) throws MalformedContentNameStringException {
		if(str == null) return null;
		if(str.length() == 0) return ROOT;
		return new ContentName(str);
	}
	
	public boolean contains(String str) {
		return contains(componentParse(str));
	}
	
	public boolean contains(byte [] component) {
		return (containsWhere(component) > 0);
	}
	
	public int containsWhere(String str) {
		return containsWhere(componentParse(str));
	}
	
	int containsWhere(byte [] component) {
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
	 * Slice the name off right before the given component
	 * @param name
	 * @param component
	 * @return
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
	
	public ContentName cut(String component) {
		return cut(componentParse(component)); 
	}
	
	public boolean isPrefixOf(ContentName other) {
		if (null == other)
			return false;
		if (this.count() > other.count())
			return false;
		return this.equals(other, this.count());
	}
	
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}

		encoder.writeStartElement(CONTENT_NAME_ELEMENT);
		
		for (int i=0; i < count(); ++i) {
			encoder.writeElement(COMPONENT_ELEMENT, _components.get(i));
		}
		encoder.writeEndElement();
	}
	
	public boolean validate() { 
		return (null != _components);
	}
	
	public ContentName copy(int nameComponentCount) {
		return new ContentName(nameComponentCount, this.components());
	}

	public int compareTo(ContentName o) {
		int len = (this.count() > o.count()) ? this.count() : o.count();
		int componentResult = 0;
		for (int i=0; i < len; ++i) {
			componentResult = DataUtils.compareTo(this.component(i), o.component(i));
			if (0 != componentResult)
				return componentResult;
		}
		if (this.count() < o.count())
			return -1;
		else if (this.count() > o.count())
			return 1;
		return 0;
	}
}
