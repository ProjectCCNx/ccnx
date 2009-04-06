package com.parc.ccn.data.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import com.parc.ccn.Library;
import com.parc.ccn.library.io.NullOutputStream;

public class SerializedObject<E extends Serializable>{
	
	public static final String DEFAULT_DIGEST = "SHA-1"; // OK for now.
	
	E _data;
	byte [] _lastSaved = null;
	boolean _potentiallyDirty = true;
	
	public SerializedObject() {
		// _data = new E(); // subclass constructors must do
	}
	
	public SerializedObject(E data) {
		_data = data;
	}
	
	public void update(InputStream input) {
		
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
				} else {
					internalWriteObject(output);
				}
			} catch (NoSuchAlgorithmException e) {
				Library.logger().warning("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
				throw new RuntimeException("No pre-configured algorithm " + DEFAULT_DIGEST + " available -- configuration error!");
			}
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
