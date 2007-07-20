package com.parc.ccn.data;

import java.util.Arrays;

public class ContentName {

	public static final String SEPARATOR = "\\/";
	public static final ContentName ROOT = new ContentName((String)null);
	
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
	
	public ContentName clone() {
		return new ContentName(components());
	}
		
	public String toString() {
		if ((null == _components) || (0 == _components.length)) {
			return SEPARATOR;
		}
		StringBuffer nameBuf = new StringBuffer();
		for (int i=0; i < _components.length; ++i) {
			nameBuf.append(SEPARATOR);
			nameBuf.append(_components[i]);
		}
		return nameBuf.toString();
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
}
