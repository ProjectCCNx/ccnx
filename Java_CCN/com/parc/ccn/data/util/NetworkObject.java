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

public abstract class NetworkObject<E> {

	public static final String DEFAULT_DIGEST = "SHA-1"; // OK for now.

	protected Class<E> _type;
	protected E _data;
	protected byte [] _lastSaved = null;
	protected boolean _potentiallyDirty = true;

	public NetworkObject(Class<E> type) {
		_type = type;
	}
	
	public NetworkObject(Class<E> type, E data) {
		this(type);
		_data = data;
	}

	public NetworkObject() {
		super();
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
		
		Object newData = readObjectImpl(input);
		
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
	 * Have we read any data yet? Only valid at beginning; doesn't tell
	 * you if update has gone through.
	 * @return
	 */
	public boolean ready() {
		return (null != _data);
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
	
	public void setData(E data) { 
		_data = data; 
		setPotentiallyDirty(true);
	}

	/**
	 * Base behavior -- always write when asked.
	 * @param output
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	public void save(OutputStream output) throws IOException, XMLStreamException {
		if (null == _data) {
			throw new InvalidObjectException("No data to save!");
		}
		internalWriteObject(output);
	}

	/**
	 * Write only if necessary.
	 * @param output
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	public void saveIfDirty(OutputStream output) throws IOException,
	XMLStreamException {
		if (null == _data) {
			throw new InvalidObjectException("No data to save!");
		} if (null == _lastSaved) {
			// Definitely save the object
			internalWriteObject(output);
		} else if (_potentiallyDirty) {
			// For CCN we don't want to write the thing unless we need to. But 
			// in general, probably want to write every time we're asked.
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

			writeObjectImpl(dos);
			dos.flush();
			dos.close();
			byte [] currentValue = dos.getMessageDigest().digest();

			if (Arrays.equals(currentValue, _lastSaved)) {
				Library.logger().info("Last saved value for object still current.");
				return false;
			} else {
				Library.logger().info("Last saved value for object not current -- object changed.");
				return true;
			}
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
		}	
	}

	protected boolean isPotentiallyDirty() { return _potentiallyDirty; }

	protected void setPotentiallyDirty(boolean dirty) { _potentiallyDirty = dirty; }

	protected void internalWriteObject(OutputStream output) throws IOException, XMLStreamException {
		try {
			// Problem -- can't wrap the OOS in a DOS, need to do it the other way round.
			DigestOutputStream dos = new DigestOutputStream(output, 
					MessageDigest.getInstance(DEFAULT_DIGEST));
			writeObjectImpl(dos);
			dos.flush(); // do not close the dos, as it will close output. allow caller to do that.
			_lastSaved = dos.getMessageDigest().digest();
			setPotentiallyDirty(false);
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
		}
	}
	
	/**
	 * Subclasses override. This implements the actual object write. No flush or close necessary.
	 * @param output
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	protected abstract void writeObjectImpl(OutputStream output) throws IOException, XMLStreamException;

	/**
	 * Subclasses override. This implements the actual object read from stream, returning
	 * the new object. This should return something that can be cast to E.
	 * @throws ClassNotFoundException 
	 */
	protected abstract Object readObjectImpl(InputStream input) throws IOException, XMLStreamException;
	
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
		NetworkObject<?> other = (NetworkObject<?>) obj;
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

	public boolean contentEquals(Object obj) {
		if (getClass() != obj.getClass())
			return false;
		NetworkObject<?> other = (NetworkObject<?>) obj;
		if (_data == null) {
			if (other._data != null) {
				return false;
			} else {
				return true;
			}
		}
		return _data.equals(other._data);
	}

}