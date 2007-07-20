package com.parc.ccn.data.security;

import java.io.ByteArrayInputStream;
import java.security.PublicKey;
import com.parc.ccn.data.ContentName;

public class KeyLocator {
    public enum KeyLocatorType { NAME, KEY };

    protected KeyLocatorType _type;
    // Fake out a union.
    protected ContentName _name;       // null if wrong type
    protected PublicKey _key;
    
    public KeyLocator(ContentName name) {
    	_name = name.clone();
    	_type = KeyLocatorType.NAME;
    }
    
    public KeyLocator(PublicKey key) {
    	_key = key;
    	_type = KeyLocatorType.KEY;
    }
    
    public KeyLocator(byte[] encodedKeyLocator) {
		// TODO Auto-generated constructor stub
	}

	public PublicKey key() { return _key; }
    public ContentName name() { return _name; }
    public KeyLocatorType type() { return _type; }

	public ByteArrayInputStream getEncoded() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_key == null) ? 0 : _key.hashCode());
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
		result = PRIME * result + ((_type == null) ? 0 : _type.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final KeyLocator other = (KeyLocator) obj;
		if (_key == null) {
			if (other._key != null)
				return false;
		} else if (!_key.equals(other._key))
			return false;
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
			return false;
		if (_type == null) {
			if (other._type != null)
				return false;
		} else if (!_type.equals(other._type))
			return false;
		return true;
	}

}
