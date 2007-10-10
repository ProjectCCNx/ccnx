package com.parc.ccn.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Arrays;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

public class ContentName implements XMLEncodable {

	public static final String SEPARATOR = "/";
	public static final ContentName ROOT = new ContentName((String)null);
	private static final String COUNT_ELEMENT = "Count";
	private static final String CONTENT_NAME_ELEMENT = "Name";
	private static final String COMPONENT_ELEMENT = "Component";
	
	protected byte _components[][];
		
	public ContentName(byte components[][]) {
		if (null == components) {
			_components = null;
		} else {
			_components = new byte[components.length][];
			for (int i=0; i < components.length; ++i) {
				_components[i] = new byte[components[i].length];
				System.arraycopy(components[i],0,_components[i],0,components[i].length);
			}
		}
	}
		
	public ContentName(String name) {
		if((name == null) || (name.length() == 0)) {
			_components = null;
		} else {
			String[] parts = name.split(SEPARATOR);
			_components = new byte[parts.length][];
			for (int i=0; i < _components.length; ++i) {
				_components[i] = parts[i].getBytes();
			}
		}
	}
	
	public ContentName(String parts[]) {
		if ((parts == null) || (parts.length == 0)) {
			_components = null;
		} else {
			_components = new byte[parts.length][];
			for (int i=0; i < _components.length; ++i) {
				_components[i] = parts[i].getBytes();
			}
		}
	}
	
	public ContentName(ContentName parent, byte[] name) {
		this(parent.count() + 
				((null != name) ? 1 : 0), parent.components());
		if (null != name) {
			_components[parent.count()] = new byte[name.length];
			System.arraycopy(_components[parent.count()],0,name,0,name.length);
		}
	}
	
	public ContentName parent() {
		return new ContentName(count()-1, components());
	}
	
	/**
	 * Basic constructor for extending or contracting names.
	 * @param count
	 * @param components
	 */
	public ContentName(int count, byte components[][]) {
		if (0 >= count) {
			_components = null;
		} else {
			_components = new byte[count][];
			int max = (null == components) ? 0 : 
				  		((count > components.length) ? 
				  				components.length : count);
			for (int i=0; i < max; ++i) {
				_components[i] = new byte[components[i].length];
				System.arraycopy(components[i],0,_components[i],0,components[i].length);
			}
		}
	}
	
	public ContentName() {
		this(0, null);
	}
	
	public ContentName clone() {
		return new ContentName(components());
	}
		
	public String toString() {
		// DKS - print out component contents, not component object...
		if ((null == _components) || (0 == _components.length)) {
			return SEPARATOR;
		}
		StringBuffer nameBuf = new StringBuffer();
		for (int i=0; i < _components.length; ++i) {
			nameBuf.append(SEPARATOR);
			nameBuf.append(componentPrint(_components[i]));
		}
		return nameBuf.toString();
	} 
	
	protected String componentPrint(byte[] bs) {
		// DKS: would like to display strings as strings,
		// but would have to detect printability 
		if (isPrintable(bs)) {
			String bstring = new String(bs);
			return bstring;
		} else {
			BigInteger bsi = new BigInteger(1, bs); // force positive
			return "0x" + bsi.toString(16);
		}
	}

	protected boolean isPrintable(byte[] bs) {
		// TODO Auto-generated method stub
		return false;
	}

	public byte[][] components() { return _components; }
	public int count() { 
		if (null == _components) return 0;
		return _components.length; 
	}

	public byte[] component(int i) { 
		if ((null == _components) || (i >= _components.length)) return null;
		return _components[i];
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

	public static ContentName parse(String str) {
		if(str == null) return ROOT;
		if(str.length() == 0) return ROOT;
		String[] parts = str.split(SEPARATOR);
		return new ContentName(parts);
	}

	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
		XMLHelper.endDecoding(reader);
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, CONTENT_NAME_ELEMENT);

		String strCount = XMLHelper.readElementText(reader, COUNT_ELEMENT); 
		int count = Integer.valueOf(strCount);
		
		_components = new byte[count][];
		
		for (int i=0; i < count; ++i) {
			String strComponent = XMLHelper.readElementText(reader, COMPONENT_ELEMENT); 
			try {
				_components[i] = XMLHelper.decodeElement(strComponent);
			} catch (IOException e) {
				throw new XMLStreamException("Cannot decode component " + i + ": " + strComponent, e);
			}
			if (null == _components[i]) {
				throw new XMLStreamException("Component " + i + " decodes to null: " + strComponent);
			}
		}
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer);
		XMLHelper.endEncoding(writer);	
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		XMLHelper.startFirstElement(writer, CONTENT_NAME_ELEMENT);
		XMLHelper.writeElement(writer, COUNT_ELEMENT, Integer.toString(count()));
		
		for (int i=0; i < count(); ++i) {
			XMLHelper.writeElement(writer, COMPONENT_ELEMENT, 
					XMLHelper.encodeElement(_components[i]));
		}
		writer.writeEndElement();
	}
}
