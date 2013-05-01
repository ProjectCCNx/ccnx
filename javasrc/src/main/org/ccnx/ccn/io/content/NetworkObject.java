/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.ErrorStateException;
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

	/**
	 * Care about speed, not collision-resistance.
	 */
	public static final String DEFAULT_CHECKSUM_ALGORITHM = "MD5"; 

	protected Class<E> _type;
	protected E _data;
	protected boolean _isDirty = false;
	protected boolean _isPotentiallyDirty = false;
	/**
	 * Is it possible to modify the type of data we contain directly
	 * from a pointer to the object, or do we have to replace the whole
	 * thing to change its value? For example, a Java String or BigInteger
	 * is immutable (modulo reflection-based abstraction violations). A
	 * complex structure whose fields can be set would be mutable. We want
	 * to track whether the content of the object has been changed either
	 * using setData or outside of the object interface; this is an 
	 * optimization to allow us to avoid the outside-the-object checks
	 * for immutable objects.
	 */
	protected boolean _contentIsMutable = false; 
	protected byte [] _lastSaved; // save digest of serialized item, so can tell if updated outside
								  // of setData
	protected boolean _available = false; // false until first time data is set or updated
	
	/**
	 * Track error state in a subclass-compatible way by storing the last exception we threw.
	 */
	protected IOException _errorState = null;
	
	public NetworkObject() {} // Needed to support serialization of subclasses

	/**
	 * Subclasses need to specify the type as an argument as well as a template
	 * parameter in order to make factory methods work properly.
	 * @param type Should be same as class template parameter.
	 * @param contentIsMutable Is the class we are encapsulating mutable (its content can
	 * 	be modified without replacing the object reference) or immutable (the only
	 * 	way to change it is to replace it, or here set it with setData). Unfortunately
	 *  there is no way to determine this via reflection. You could also set this to
	 *  false if you do not expose the data directly, but merely expose methods to modify
	 *  its values, and manage _isPotentiallyDirty directly.
	 */
	public NetworkObject(Class<E> type, boolean contentIsMutable) {
		_type = type;
		_contentIsMutable = contentIsMutable;
	}
	
	/**
	 * Specify type as well as initial data.
	 * @param type Should be same as class template parameter.
	 * @param contentIsMutable Is the class we are encapsulating mutable (its content can
	 * 	be modified without replacing the object reference) or immutable (the only
	 * 	way to change it is to replace it, or here set it with setData). Unfortunately
	 *  there is no way to determine this via reflection. You could also set this to
	 *  false if you do not expose the data directly, but merely expose methods to modify
	 *  its values, and manage _isPotentiallyDirty directly.
	 * @param data Initial data value.
	 */
	public NetworkObject(Class<E> type, boolean contentIsMutable, E data) {
		this(type, contentIsMutable);
		setData(data); // marks data as available if non-null
	}
	
	protected NetworkObject(Class<E> type, NetworkObject<? extends E> other) {
		this(type, other._contentIsMutable);
		_data = other._data;
		_isDirty = other._isDirty;
		_isPotentiallyDirty = other._isPotentiallyDirty;
		_lastSaved = other._lastSaved;
		_available = other._available;
	}

	/**
	 * Create an instance of the parameterized type, used for decoding.
	 * @return the new instance
	 * @throws IOException wrapping other types of exception generated by constructor.
	 */
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

	/**
	 * Read this object's data from the network.
	 * @param input InputStream holding the object's data.
	 * @throws ContentDecodingException if there is an error decoding the object
	 * @throws IOException if there is an error reading the object from the network
	 */
	public void update(InputStream input) throws ContentDecodingException, IOException {

		E newData = readObjectImpl(input);

		synchronized(this) {
			if (!_available) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINEST)) {
					Log.finest(Log.FAC_IO, "Update -- first initialization.");
				}
			}

			_data = newData;
			_available = true;
			setDirty(false);
			_lastSaved = digestContent();
		}
	}
	
	/**
	 * @return true if the object has been updated from the network, or has had
	 * its data value set to a non-null value (whether it has been saved to the
	 * network or not).
	 */
	public synchronized boolean available() {
		return _available; 
	}
	
	public synchronized boolean hasError() {
		return (null != _errorState);
	}
	
	public synchronized IOException getError() {
		return _errorState;
	}
	
	public synchronized void clearError() {
		_errorState = null;
	}
	
	protected synchronized void setError(IOException t) {
		_errorState = t;
	}
	
	/**
	 * Set a new data value for this object.  Mark it as dirty (needing
	 * to be saved).
	 * @param data new value
	 */
	public synchronized void setData(E data) { 
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
	}

	/**
	 * @param available the new value
	 */
	protected synchronized void setAvailable(boolean available) {
		_available = available;
	}

	/**
	 * Retrieve this object's data.
	 * Subclasses should expose methods to access/modify _data,
	 * but may choose not to expose _data itself. Ideally any dangerous operation
	 * (like giving access to some variable that could be changed) will
	 * mark the object as _isPotentiallyDirty. Changes to the data will
	 * then be detected automatically. (The use of _isPotentiallyDirty
	 * to control detection of content change is an optimization, otherwise
	 * isDirty() is invoked every time the object might need to be saved.)
	 * @return Returns the data. Whether null data is allowed or not is
	 *   determined by the subclass, which can override available() (by
	 *   default, data cannot be null).
	 * @throws ContentNotReadyException if the object has not finished retrieving data/having data set
	 * @throws ErrorStateException 
	 */
	protected synchronized E data() throws ContentNotReadyException, ContentGoneException, ErrorStateException { 
		if (hasError()) {
			throw new ErrorStateException("Cannot retrieve data -- object in error state!", _errorState);
		}
		
		if (!available()) {
			throw new ContentNotReadyException("No data yet saved or retrieved!");
		}
		// Mark that we've given out access to the internal data, so we know someone might
		// have changed it. If it can't be changed outside this interface, don't
		// mark it potentially dirty as an optimization.
		if (_contentIsMutable)
			_isPotentiallyDirty = true;
		// return a pointer to the current data. No guarantee that this will continue
		// to be what we think our data unless caller holds read lock.
		return _data; 
	}
	
	/**
	 * Save the object regardless of whether it has been modified (isDirty()) or not.
	 * @param output stream to save to
	 * @throws ContentEncodingException if there is an error encoding the object
	 * @throws IOException if there is an error writing the object to the network
	 */
	public synchronized void forceSave(OutputStream output) throws ContentEncodingException, IOException {
		if (null == _data) {
			throw new InvalidObjectException("No data to save!");
		}
		internalWriteObject(output);
	}

	/**
	 * Save the object if it is dirty (has been changed).
	 * @param output stream to write to.
	 * @throws ContentEncodingException if there is an error encoding the object
	 * @throws IOException if there is an error writing the object to the network.
	 */
	public synchronized void save(OutputStream output) throws ContentEncodingException, IOException {

		if (available() && isDirty()) {
			forceSave(output);
		}
	}
	
	/**
	 * Encode and digest the object's content in order to detect changes made
	 * outside of the object's own interface (for example, if the data is accessed
	 * using data() and then modified).
	 * @return
	 * @throws ContentEncodingException if there is a problem encoding the content
	 * @throws IOException if there is a problem writing the object to the stream.
	 */
	protected byte [] digestContent() throws ContentEncodingException, IOException {
		try {
			// Otherwise, might have been written when we weren't looking (someone accessed
			// data and then changed it).
			DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), MessageDigest.getInstance(DEFAULT_CHECKSUM_ALGORITHM));
			writeObjectImpl(dos);
			dos.flush();
			dos.close();
			byte [] currentValue = dos.getMessageDigest().digest();
			return currentValue;
		} catch (NoSuchAlgorithmException e) {
			Log.warning("No pre-configured algorithm {0} available -- configuration error!", DEFAULT_CHECKSUM_ALGORITHM);
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_CHECKSUM_ALGORITHM + " available -- configuration error!");
		}
	}

	/**
	 * Encode the object and see whether its digest has changed since last time
	 * it was saved.  Conservative, only runs the full check if _isPotentiallyDirty
	 * is true.
	 * @return true if the object has been modified.
	 * @throws IOException if there is a problem encoding the object.
	 */
	protected synchronized boolean isDirty() throws ContentEncodingException, IOException {

		if (_isDirty) {
			return _isDirty;
		} else if (_lastSaved == null) {
			if (_data == null)
				return false;
			return true;
		}
		if (_isPotentiallyDirty) {
			byte [] currentValue = digestContent();

			if (Arrays.equals(currentValue, _lastSaved)) {
				Log.finest("Last saved value for object still current.");
				_isDirty = false;
			} else {
				Log.finer("Last saved value for object not current -- object changed.");
				_isDirty = true;
			}
		} else {
			// We've never set the data, nor given out access to it. It can't be dirty.
			Log.finest("NetworkObject: data cannot be dirty.");
			_isDirty = false;
		}

		return _isDirty; 
	}
	
	/**
	 * @return True if the content was either read from the network or was saved locally.
	 */
	public synchronized boolean isSaved() throws IOException {
		return available() && !isDirty();
	}

	/**
	 * @param dirty new value for the dirty setting.
	 */
	protected synchronized void setDirty(boolean dirty) { 
		_isDirty = dirty; 
		if (!_isDirty) {
			_isPotentiallyDirty = false; // just read or written
		}
	}
	
	/**
	 * Extract the content digest (made with the default digest algorithm).
	 * @throws IOException 
	 */
	public byte [] getContentDigest() throws IOException {
		if (!isSaved()) {
			throw new ErrorStateException("Content has not been saved!");
		}
		return _lastSaved;
	}

	/**
	 * Save the object and update the internal tracking digest of its last-saved content.
	 * @param output stream to write to.
	 * @throws ContentEncodingException if there is an error encoding the object
	 * @throws IOException if there is an error writing the object to the network
	 */
	protected synchronized void internalWriteObject(OutputStream output) throws ContentEncodingException, IOException {
		try {
			DigestOutputStream dos = new DigestOutputStream(output, MessageDigest.getInstance(DEFAULT_CHECKSUM_ALGORITHM));
			writeObjectImpl(dos);
			dos.flush(); // do not close dos, as it will close the output, allow caller to close
			_lastSaved = dos.getMessageDigest().digest();
			setDirty(false);
			
		} catch (NoSuchAlgorithmException e) {
			Log.warning("No pre-configured algorithm {0} available -- configuration error!", DEFAULT_CHECKSUM_ALGORITHM);
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_CHECKSUM_ALGORITHM + " available -- configuration error!");
		}
	}
	
	/**
	 * Subclasses override. This implements the actual object write. No flush or close necessary.
	 * @param output the stream to write to
	 * @throws ContentEncodingException if there is an error encoding the object
	 * @throws IOException if there is an error writing it to the network
	 */
	protected abstract void writeObjectImpl(OutputStream output) throws ContentEncodingException, IOException;

	/**
	 * Subclasses override. This implements the actual object read from stream, returning
	 * the new object.
	 * @throws ContentDecodingException if there is an error decoding the object
	 * @throws IOException if there is an error actually reading the data 
	 */
	protected abstract E readObjectImpl(InputStream input) throws ContentDecodingException, IOException;
	
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

	/**
	 * Equality comparison on just the internal data.
	 * @param obj
	 * @return true if other is a NetworkObject and the two have matching data().
	 */
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
	public String toString() { return (null == _data) ? "(null)" : _data.toString(); }

}
