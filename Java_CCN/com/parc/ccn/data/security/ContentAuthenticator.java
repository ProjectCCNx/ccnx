package com.parc.ccn.data.security;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;

public class ContentAuthenticator {

    public static final int PUBLISHER_ID_LEN = 256/8;
    public enum ContentType {FRAGMENT, LINK, CONTAINER, LEAF, SESSION};
    protected static final HashMap<ContentType, String> TypeNames = new HashMap<ContentType, String>();
    protected static final HashMap<String, ContentType> NameTypes = new HashMap<String, ContentType>();
    
    
    static {
        TypeNames.put(ContentType.FRAGMENT, "FRAGMENT");
        TypeNames.put(ContentType.LINK, "LINK");
        TypeNames.put(ContentType.CONTAINER, "CONTAINER");
        TypeNames.put(ContentType.LEAF, "LEAF");
        TypeNames.put(ContentType.SESSION, "SESSION");
        NameTypes.put("FRAGMENT", ContentType.FRAGMENT);
        NameTypes.put("LINK", ContentType.LINK);
        NameTypes.put("CONTAINER", ContentType.CONTAINER);
        NameTypes.put("LEAF", ContentType.LEAF);
        NameTypes.put("SESSION", ContentType.SESSION);
    }
    
    protected byte[] 	  	_publisher = new byte[PUBLISHER_ID_LEN];
    // int   		_version; // Java types are signed, must cope
    protected Timestamp		_timestamp;
    protected ContentType 	_type;
    // long	  	_size; // signed, must cope
    protected byte []		_contentHash; // encoded DigestInfo
    protected KeyLocator  	_keyLocator;
    protected byte[]		_signature; // might want to use Signature type

    public ContentAuthenticator(byte[] publisher, 
    							Timestamp timestamp, 
    							ContentType type, 
    							byte[] hash, 
    							KeyLocator locator, 
    							byte[] signature) {
		super();
		this._publisher = publisher;
		this._timestamp = timestamp;
		this._type = type;
		_contentHash = hash;
		_keyLocator = locator;
		this._signature = signature;
	}
    
    public ContentAuthenticator() {}
    
    public boolean empty() {
    	return (emptyPublisher() && emptySignature() && emptyContentHash());
    }
    
    public boolean emptyPublisher() {
    	if ((null != publisher()) && (0 != publisher().length))
    		return false;
    	return true;
    }
    
    public boolean emptySignature() {
       	if ((null != signature()) && (0 != signature().length))
    		return false;
       	return true;
    }
    
    public boolean emptyContentHash() {
    	if ((null != contentHash()) && (0 != contentHash().length))
    		return false;
    	return true;   	
    }
    
	public byte[] contentHash() {
		return _contentHash;
	}
	public void contentHash(byte[] hash) {
		_contentHash = hash;
	}
	public KeyLocator keyLocator() {
		return _keyLocator;
	}
	public void keyLocator(KeyLocator locator) {
		_keyLocator = locator;
	}
	public byte[] publisher() {
		return _publisher;
	}
	public void publisher(byte[] publisher) {
		this._publisher = publisher;
	}
	public byte[] signature() {
		return _signature;
	}
	public void signature(byte[] signature) {
		this._signature = signature;
	}
	public Timestamp timestamp() {
		return _timestamp;
	}
	public void timestamp(Timestamp timestamp) {
		this._timestamp = timestamp;
	}
	public ContentType type() {
		return _type;
	}
	public void type(ContentType type) {
		this._type = type;
	}
	
	public String typeName() {
		return typeToName(type());
	}
	
	public static String typeToName(ContentType type) {
		return TypeNames.get(type);
	}

	public static ContentType nameToType(String name) {
		return NameTypes.get(name);
	}
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_contentHash);
		result = PRIME * result + ((_keyLocator == null) ? 0 : _keyLocator.hashCode());
		result = PRIME * result + Arrays.hashCode(_publisher);
		result = PRIME * result + Arrays.hashCode(_signature);
		result = PRIME * result + ((_timestamp == null) ? 0 : _timestamp.hashCode());
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
		final ContentAuthenticator other = (ContentAuthenticator) obj;
		if (!Arrays.equals(_contentHash, other._contentHash))
			return false;
		if (_keyLocator == null) {
			if (other._keyLocator != null)
				return false;
		} else if (!_keyLocator.equals(other._keyLocator))
			return false;
		if (!Arrays.equals(_publisher, other._publisher))
			return false;
		if (!Arrays.equals(_signature, other._signature))
			return false;
		if (_timestamp == null) {
			if (other._timestamp != null)
				return false;
		} else if (!_timestamp.equals(other._timestamp))
			return false;
		if (_type == null) {
			if (other._type != null)
				return false;
		} else if (!_type.equals(other._type))
			return false;
		return true;
	}

}
