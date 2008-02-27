package com.parc.ccn.data;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;
import com.parc.ccn.network.rpc.Name;
import com.parc.ccn.network.rpc.NameComponent;

public class ContentName extends GenericXMLEncodable implements XMLEncodable {

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
				byte [] c = new byte[components[i].length];
				System.arraycopy(components[i],0,c,0,components[i].length);
				_components.add(c);
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
		
	public ContentName(byte [] encoded) throws XMLStreamException {
		super(encoded);
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
	
	public ContentName(Name oncRpcName) {
		if ((null == oncRpcName.component) ||
			(0 >= oncRpcName.component.length)) {
			_components = new ArrayList<byte[]>(0);
		} else {
			_components = new ArrayList<byte []>(oncRpcName.component.length);
			for (int i=0; i < oncRpcName.component.length; ++i) {
				byte [] c = new byte[oncRpcName.component[i].length];
				System.arraycopy(oncRpcName.component[i].vals,0,c,0,oncRpcName.component[i].length);
				_components.add(c);
			}
		}
	}
	
	public ContentName clone() {
		return new ContentName(count(), components());
	}
		
	public ContentName parent() {
		return new ContentName(count()-1, components());
	}
	
	public String toString() {
		if (null == _components) return null;
		if (0 == _components.size()) return new String();
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
	
	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, CONTENT_NAME_ELEMENT);
		
		_components = new ArrayList<byte []>();
		
		while (XMLHelper.peekStartElement(reader, COMPONENT_ELEMENT)) {
			String strComponent = XMLHelper.readElementText(reader, COMPONENT_ELEMENT); 
			try {
				_components.add(XMLHelper.decodeElement(strComponent));
			} catch (IOException e) {
				throw new XMLStreamException("Cannot decode component: " + strComponent, e);
			}
		}
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, CONTENT_NAME_ELEMENT, isFirstElement);
		
		for (int i=0; i < count(); ++i) {
			XMLHelper.writeElement(writer, COMPONENT_ELEMENT, 
					XMLHelper.encodeElement(_components.get(i)));
		}
		writer.writeEndElement();
	}
	
	public boolean validate() { 
		return (null != _components);
	}
	
	public Name toONCName() {
		Name oncName = new Name();
		// RPCgen created objects have lots of public data, no useful constructors.
		oncName.component = new NameComponent[count()];
		for (int i=0; i < count(); ++i) {
			oncName.component[i] = new NameComponent();
			oncName.component[i].length = component(i).length;
			// JDK 1.5 doesn't have Arrays.copyOf...
			oncName.component[i].vals = new byte [oncName.component[i].length];
			System.arraycopy(component(i), 0, oncName.component[i].vals, 0, oncName.component[i].length);	
		}
		return oncName;
	}

	public ContentName copy(int nameComponentCount) {
		return new ContentName(nameComponentCount, this.components());
	}
}
