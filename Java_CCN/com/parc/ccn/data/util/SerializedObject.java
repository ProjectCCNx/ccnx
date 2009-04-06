package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import com.parc.ccn.Library;

/**
 * Prototypical wrapper around a Serializable object. Expand to variants
 * for CCNObjects. 
 * TODO - synchronization
 * @author smetters
 *
 * @param <E>
 */
public class SerializedObject<E extends Serializable>{
	
	public static final String DEFAULT_DIGEST = "SHA-1"; // OK for now.
	
	Class<E> _type;
	E _data;
	byte [] _lastSaved = null;
	boolean _potentiallyDirty = true;
	
	public SerializedObject(Class<E> type) {
		_type = type;
		// _data = new E(); // subclass constructors must do
	}
	
	public SerializedObject(Class<E> type, E data) {
		this(type);
		_data = data;
	}
	
	public void update(InputStream input) throws IOException, ClassNotFoundException {
		ObjectInputStream ois = new ObjectInputStream(input);
		Object newData = ois.readObject();
		if (_data.equals(newData)) {
			Library.logger().info("Update -- value hasn't changed.");
		} else {
			Library.logger().info("Update -- got new " + newData.getClass().getName());
			_data = merge(input, _type.cast(newData));
		}
	}
	
	/**
	 * Why pass in input? Because some subclasses have input streams that
	 * know more about their data than we do at this point...
	 * @param input
	 * @param newData
	 * @return
	 */
	protected E merge(InputStream input, E newData) {
		return newData;
	}
	
	/**
	 * Subclasses should expose methods to update _data,
	 * but possibly not _data itself. Ideally any dangerous operation
	 * (like giving access to some variable that could be changed) will
	 * mark the object as _potentiallyDirty.
	 */
	protected E data() { return _data; }
	
	public void save(OutputStream output) throws IOException {
		if (null == _lastSaved) {
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
	
	protected boolean isDirty() throws IOException {
		try {
			// Problem -- can't wrap the OOS in a DOS, need to do it the other way round.
			DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), 
											MessageDigest.getInstance(DEFAULT_DIGEST));
			ObjectOutputStream oos = new ObjectOutputStream(dos);
			
			oos.writeObject(_data);
			oos.flush();
			dos.flush();
			oos.close();
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
	
	protected void internalWriteObject(OutputStream output) throws IOException {
		try {
			// Problem -- can't wrap the OOS in a DOS, need to do it the other way round.
			DigestOutputStream dos = new DigestOutputStream(output, 
					MessageDigest.getInstance(DEFAULT_DIGEST));
			ObjectOutputStream oos = new ObjectOutputStream(dos);
		
			oos.writeObject(_data);
			oos.flush();
			dos.flush();
			oos.close();
			_lastSaved = dos.getMessageDigest().digest();
			_potentiallyDirty = false;
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
			throw new RuntimeException("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
		}
	}

}
