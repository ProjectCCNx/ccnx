package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.support.Log;



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
	protected boolean _isDirty = false;
	protected boolean _available = false; // false until first time data is set or updated
	protected ReentrantReadWriteLock _lock = new ReentrantReadWriteLock();

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
		
		try {
			_lock.writeLock().lock();
			if (!_available) {
				Log.info("Update -- first initialization.");
			}

			_data = newData;
			_available = true;
			_isDirty = false;
		} finally {
			_lock.writeLock().unlock();
		}
	}
	
	/**
	 * Have we read any data yet? Only valid at beginning; doesn't tell
	 * you if update has gone through.
	 * @return false if data has not been set or updated from the network yet
	 */
	public boolean available() {
		try {
			_lock.readLock().lock();
			return _available; // do we need to return a copy of a primitive type?
		} finally {
			_lock.readLock().unlock();
		}
	}
	
	public void setData(E data) { 
		try {
			_lock.writeLock().lock(); // blocks readers. a reader can never get the write lock
									  // without first releasing read lock and re-checking; go
									  // for simplicity here and just take the write lock even though
									  // we might not need it.
			if (null != _data) {
				if (!_data.equals(data)) {
					_data = data;
					setDirty(true);
					setAvailable(data != null);
				}
				// else -- setting to same value, not dirty, do nothing
			} else {
				if (data != null) {
					_data = data;
					setDirty(true);
					setAvailable(true);				
				}
				// else -- setting from null to null, do nothing
			}
		} finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * Expects to be called while holding write lock.
	 * @param available
	 */
	protected void setAvailable(boolean available) {
		_available = available;
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
		try {
			_lock.readLock().lock();
			if (!available()) {
				throw new ContentNotReadyException("No data yet saved or retrieved!");
			}
			// return a pointer to the current data. No guarantee that this will continue
			// to be what we think our data unless caller holds read lock.
			return _data; 
		} finally {
			_lock.readLock().unlock();
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
		try {
			_lock.writeLock().lock();
			internalWriteObject(output);
		} finally {
			_lock.writeLock().lock();
		}
	}

	/**
	 * Write only if necessary.
	 * @param output
	 * @throws IOException
	 */
	public void saveIfDirty(OutputStream output) throws IOException,
	XMLStreamException {
		_lock.readLock().lock();
		if (available() && isDirty()) {
			_lock.readLock().unlock();
			save(output);
		} else {
			_lock.readLock().unlock();
		}
	}

	protected boolean isDirty() {
		try {
			_lock.readLock().lock();
			return _isDirty; 
		} finally {
			_lock.readLock().unlock();
		}
	}
	
	/**
	 * True if the content was either read from the network or was saved locally.
	 * @return
	 */
	public boolean isSaved() {
		try {
			_lock.readLock().lock();
			return available() && !isDirty();
		} finally {
			_lock.readLock().unlock();
		}
	}

	/**
	 * Expects to be called under write lock.
	 * @param dirty
	 */
	protected void setDirty(boolean dirty) { _isDirty = dirty; }

	protected void internalWriteObject(OutputStream output) throws IOException {
		try {
			_lock.writeLock().lock();
			writeObjectImpl(output);
			setDirty(false);
		} catch (XMLStreamException e) {
			// TODO when move to 1.6, use nested exceptions
			throw new IOException("XMLStreamException " + e);
		} finally {
			_lock.writeLock().unlock();
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