package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;

/**
 * Prototypical wrapper around an XMLEncodable object. Expand to variants
 * for CCNObjects. 
 * TODO - synchronization
 * @author smetters
 *
 * @param <E>
 */
public class EncodableObject<E extends XMLEncodable>{
	
	public static final String DEFAULT_DIGEST = "SHA-1"; // OK for now.
	
	Class<E> _type;
	E _data;
	byte [] _lastSaved = null;
	boolean _potentiallyDirty = true;
	
	public EncodableObject(Class<E> type) {
		_type = type;
		// _data = new E(); // subclass constructors must do
	}
	
	public EncodableObject(Class<E> type, E data) {
		this(type);
		_data = data;
	}

	protected E factory() throws IOException {
		E newE;
		try {
			newE = _type.newInstance();
		} catch (InstantiationException e) {
			Library.logger().warning("Cannot wrap class " + _type.getName() + " -- impossible to construct instances!");
			throw new IOException("Cannot wrap class " + _type.getName() + " -- impossible to construct instances!");
		} catch (IllegalAccessException e) {
			Library.logger().warning("Cannot wrap class " + _type.getName() + " -- cannot access default constructor!");
			throw new IOException("Cannot wrap class " + _type.getName() + " -- cannot access default constructor!");
		}
		return newE;
	}
	
	public void update(InputStream input) throws IOException, XMLStreamException {
		E newData = factory();
		newData.decode(input);
		if (null == _data) {
			Library.logger().info("Update -- first initialization.");
			_data = _type.cast(newData);
			_potentiallyDirty = false;
		}
		if (_data.equals(newData)) {
			Library.logger().info("Update -- value hasn't changed.");
		} else {
			Library.logger().info("Update -- got new " + newData.getClass().getName());
			_data = merge(input, _type.cast(newData));
		}
	}
	
	/**
	 * Why pass in input? Because some subclasses have input streams that
	 * know more about their data than we do at this point... If the
	 * result of the merge is that there is no difference from what
	 * we just saw on the wire, set _potentiallyDirty to false. If 
	 * merge does a true merge, then set _potentiallyDirty to true.
	 * @param input
	 * @param newData
	 * @return
	 */
	protected E merge(InputStream input, E newData) {
		_potentiallyDirty = false;
		return newData;
	}
	
	/**
	 * Subclasses should expose methods to update _data,
	 * but possibly not _data itself. Ideally any dangerous operation
	 * (like giving access to some variable that could be changed) will
	 * mark the object as _potentiallyDirty.
	 */
	protected E data() { return _data; }
	
	public void save(OutputStream output) throws IOException, XMLStreamException {
		if (null == _data) {
			throw new InvalidObjectException("No data to save!");
		} if (null == _lastSaved) {
			// Definitely save the object
			internalWriteObject(output);
		} else if (_potentiallyDirty) {
			// Prep for CCN -- don't want to save unless it's really worth signing a new
			// version. Of course, for standard, non-CCN streams, it would be cheaper to
			// just rewrite the thing. But for us, be paranoid.
			if (isDirty()) {
				internalWriteObject(output);
			}
		}
	}
	
	protected boolean isDirty() throws XMLStreamException, IOException {
		try {
			// Problem -- can't wrap the OOS in a DOS, need to do it the other way round.
			DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), 
											MessageDigest.getInstance(DEFAULT_DIGEST));
			
			_data.encode(dos);
			dos.flush();
			dos.close();
			byte [] currentValue = dos.getMessageDigest().digest();
			
			if (Arrays.equals(currentValue, _lastSaved)) {
				Library.logger().info("Last saved value for object still current.");
				return true;
			} else {
				Library.logger().info("Last saved value for object not current -- object changed.");
				return false;
			}
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
		}	
	}
	
	protected void internalWriteObject(OutputStream output) throws IOException, XMLStreamException {
		try {
			// Problem -- can't wrap the OOS in a DOS, need to do it the other way round.
			DigestOutputStream dos = new DigestOutputStream(output, 
					MessageDigest.getInstance(DEFAULT_DIGEST));
			
			_data.encode(dos);
			dos.flush();
			dos.close();
			_lastSaved = dos.getMessageDigest().digest();
			_potentiallyDirty = false;
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
		}
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((_data == null) ? 0 : _data.hashCode());
		result = prime * result + ((_type == null) ? 0 : _type.hashCode());
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
		EncodableObject<?> other = (EncodableObject<?>) obj;
		if (_type == null) {
			if (other._type != null)
				return false;
		} else if (!_type.equals(other._type))
			return false;
		if (_data == null) {
			if (other._data != null)
				return false;
		} else if (!_data.equals(other._data))
			return false;
		return true;
	}

}
