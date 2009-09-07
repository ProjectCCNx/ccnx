package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.NullOutputStream;



/**
 * A NetworkObject provides support for storing an object in a network based backing store.
 * It provides support for loading the object from the network, tracking if the object's data
 * has been changed, to determine whether it needs to be saved or not and saving the object.
 * 
 * It can have 3 states:
 * - available: refers to whether it has data (either set by caller or updated from network)
 * 		(potentiallyDirty refers to whether it has been saved since last set; it might not 
 * 		 actually be dirty if saved to same value as previous)
 * - stored: saved to network or updated from network and not since saved
 * 
 * It can be:
 * - not available (no data assigned, not saved or read, basically not ready)
 * - available but not stored (assigned locally, but not yet stored anywhere; this
 * 				means that storage-related metadata is unavailable even though data can be read
 * 				back out), or assigned locally since last stored
 * 		- if assigned locally but unchanged, it will not be rewritten and last stored
 * 			metadata 
 * - available and stored (stored by caller, or read from network)
 * 
 * Subclasses can vary as to whether they think null is valid data for an object -- i.e. 
 * whether assigning the object's value to null makes it available or not. The default behavior
 * is to not treat a null assignment as a value -- i.e. not available.
 */
public abstract class NetworkObject<E> {

	public static final String DEFAULT_DIGEST = "SHA-1"; // OK for now.

	protected Class<E> _type;
	protected E _data;
	protected byte [] _lastSaved = null;
	protected boolean _isDirty = true;
	protected boolean _available = false; // false until first time data is set or updated

	public NetworkObject(Class<E> type) {
		_type = type;
	}
	
	public NetworkObject(Class<E> type, E data) {
		this(type);
		setData(data); // marks data as available if non-null
	}

	protected E factory() throws IOException {
		E newE;
		try {
			newE = _type.newInstance();
		} catch (InstantiationException e) {
			Log.warning("Cannot wrap class " + _type.getName() + " -- impossible to construct instances!");
			throw new IOException("Cannot wrap class " + _type.getName() + " -- impossible to construct instances!");
		} catch (IllegalAccessException e) {
			Log.warning("Cannot wrap class " + _type.getName() + " -- cannot access default constructor!");
			throw new IOException("Cannot wrap class " + _type.getName() + " -- cannot access default constructor!");
		}
		return newE;
	}

	public void update(InputStream input) throws IOException, XMLStreamException {
		
		E newData = readObjectImpl(input);
		
		if (!_available) {
			Log.info("Update -- first initialization.");
			_data = newData;
			_available = true;
			_isDirty = false;
		}
		if (null == _data) {
			if (null != newData) {
				Log.info("Update -- got new non-null " + newData.getClass().getName());
				_data = merge(input, newData);
			} else {
				Log.info("Update -- value still null.");
			}
		} else if (_data.equals(newData)) {
			Log.info("Update -- value hasn't changed.");
		} else {
			Log.info("Update -- got new " + newData.getClass().getName());
			_data = merge(input, newData);
		}
	}
	
	/**
	 * Have we read any data yet? Only valid at beginning; doesn't tell
	 * you if update has gone through.
	 * @return false if data has not been set or updated from the network yet
	 */
	public boolean available() {
		return _available;
	}
	
	protected void setAvailable(boolean available) {
		_available = available;
	}

	/**
	 * Why pass in input? Because some subclasses have input streams that
	 * know more about their data than we do at this point... If the
	 * result of the merge is that there is no difference from what
	 * we just saw on the wire, set _isDirty to false. If 
	 * merge does a true merge, then set _isDirty to true.
	 * @param input
	 * @param newData
	 * @return
	 */
	protected E merge(InputStream input, E newData) {
		_isDirty = false;
		return newData;
	}

	/**
	 * Subclasses should expose methods to update _data,
	 * but possibly not _data itself. Ideally any dangerous operation
	 * (like giving access to some variable that could be changed) will
	 * mark the object as _isDirty.
	 * @return Returns the data. Whether null data is allowed or not is
	 *   determined by the subclass, which can override available() (by
	 *   default, data cannot be null).
	 * @throws ContentNotReadyException if the object has not finished retrieving data/having data set
	 */
	protected E data() throws ContentNotReadyException, ContentGoneException { 
		if (!available()) {
			throw new ContentNotReadyException("No data yet saved or retrieved!");
		}
		return _data; 
	}
	
	public void setData(E data) { 
		if (null != _data) {
			if (!_data.equals(data)) {
				_data = data;
				setDirty(true);
				setAvailable(data == null);
			}
			// else -- setting to same value, not dirty, do nothing
		} else {
			if (data != null) {
				_data = data;
				setDirty(true);
				setAvailable(data == null);				
			}
		}
	}

	/**
	 * Base behavior -- always write when asked.
	 * @param output
	 * @throws IOException
	 */
	public void save(OutputStream output) throws IOException {
		if (null == _data) {
			throw new InvalidObjectException("No data to save!");
		}
		internalWriteObject(output);
	}

	/**
	 * Write only if necessary.
	 * @param output
	 * @throws IOException
	 */
	public void saveIfDirty(OutputStream output) throws IOException,
	XMLStreamException {
		if (null == _data) {
			throw new InvalidObjectException("No data to save!");
		} if (null == _lastSaved) {
			// Definitely save the object
			internalWriteObject(output);
		} else if (_isDirty) {
			// For CCN we don't want to write the thing unless we need to. But 
			// in general, probably want to write every time we're asked.
			if (isDirty()) {
				internalWriteObject(output);
			}
		}
	}

	protected boolean isDirty() throws IOException {
		try {
			if (_data == null)
				return _lastSaved != null;

			// Problem -- can't wrap the OOS in a DOS, need to do it the other way round.
			DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), 
					MessageDigest.getInstance(DEFAULT_DIGEST));

			writeObjectImpl(dos);
			dos.flush();
			dos.close();
			byte [] currentValue = dos.getMessageDigest().digest();

			if (Arrays.equals(currentValue, _lastSaved)) {
				Log.info("Last saved value for object still current.");
				return false;
			} else {
				Log.info("Last saved value for object not current -- object changed.");
				return true;
			}
		} catch (NoSuchAlgorithmException e) {
			Log.warning("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
		} catch (XMLStreamException e) {
			// XMLStreamException should never happen, since our code should always write good XML
			throw new RuntimeException(e);
		}
	}

	protected boolean isPotentiallyDirty() { return _isDirty; }

	protected void setDirty(boolean dirty) { _isDirty = dirty; }

	protected void internalWriteObject(OutputStream output) throws IOException {
		try {
			// Problem -- can't wrap the OOS in a DOS, need to do it the other way round.
			DigestOutputStream dos = new DigestOutputStream(output, 
					MessageDigest.getInstance(DEFAULT_DIGEST));
			writeObjectImpl(dos);
			dos.flush(); // do not close the dos, as it will close output. allow caller to do that.
			_lastSaved = dos.getMessageDigest().digest();
			setDirty(false);
		} catch (NoSuchAlgorithmException e) {
			Log.warning("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
		} catch (XMLStreamException e) {
			// XMLStreamException should never happen, since our code should always write good XML
			throw new RuntimeException(e);
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
	 * the new object.
	 * @throws ClassNotFoundException 
	 */
	protected abstract E readObjectImpl(InputStream input) throws IOException, XMLStreamException;
	
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
	
	@Override
	public String toString() { return (null == _data) ? null : _data.toString(); }

}