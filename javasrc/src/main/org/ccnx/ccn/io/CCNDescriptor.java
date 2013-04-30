/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.io;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A file descriptor-style wrapper around CCNVersionedInputStream and CCNVersionedOutputStream.
 *
 */
public class CCNDescriptor {

	public enum OpenMode { O_RDONLY, O_WRONLY }
	public enum SeekWhence {SEEK_SET, SEEK_CUR, SEEK_END};

	protected CCNInputStream _input = null;
	protected CCNOutputStream _output = null;

	/**
	 * Open a new descriptor for reading or writing (but not both).
	 *
	 * @param name see CCNVersionedInputStream for specification
	 * @param publisher see CCNVersionedInputStream for specification
	 * @param handle see CCNVersionedInputStream for specification
	 * @param openForWriting if true, open an output stream. Otherwise open an input stream.
	 * @throws IOException
	 */
	public CCNDescriptor(ContentName name, PublisherPublicKeyDigest publisher, 
						 CCNHandle handle, boolean openForWriting) 
										throws IOException {
		if (openForWriting) {
			openForWriting(name, publisher, handle);
		} else {	
			openForReading(name, publisher, handle);
		}
	}

	protected void openForReading(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) 
	throws IOException {
		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(nameToOpen)) {
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		} 

		_input = new CCNVersionedInputStream(nameToOpen, publisher, handle);
	}

	protected void openForWriting(ContentName name, 
								  PublisherPublicKeyDigest publisher,
								  CCNHandle handle) throws IOException {
		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(name)) {
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		}
		_output = new CCNVersionedOutputStream(nameToOpen, publisher, handle);
	}

	/**
	 * @return If open for reading, returns result of CCNInputStream#available(), otherwise returns 0.
	 * @throws IOException
	 */
	public int available() throws IOException {
		if (!openForReading())
			return 0;
		return _input.available();
	}

	/**
	 * @return true if opened for reading
	 */
	public boolean openForReading() {
		return (null != _input);
	}

	/**
	 * @return true if opened for writing
	 */
	public boolean openForWriting() {
		return (null != _output);
	}

	/**
	 * Close underlying stream.
	 * @throws IOException
	 */
	public void close() throws IOException {
		if (null != _input)
			_input.close();
		else
			_output.close();
	}

	/**
	 * Flush output stream if open for writing.
	 * @throws IOException
	 */
	public void flush() throws IOException {
		if (null != _output)
			_output.flush();
	}

	/**
	 * @return true if open for reading and CCNInputStream#eof().
	 */
	public boolean eof() { 
		return openForReading() ? _input.eof() : false;
	}

	/**
	 * See CCNInputStream#read(byte[], int, int).
	 */
	public int read(byte[] buf, int offset, int len) throws IOException {
		if (null != _input)
			return _input.read(buf, offset, len);
		throw new IOException("Descriptor not open for reading!");
	}

	/**
	 * See CCNInputStream#read(byte[]).
	 */
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	/**
	 * See CCNOutputStream#writeToNetwork(byte[], long, long).
	 */
	public void write(byte[] buf, int offset, int len) throws IOException {
		if (null != _output) {
			_output.write(buf, offset, len);
			return;
		}
		throw new IOException("Descriptor not open for writing!");
	}

	/**
	 * Sets the timeout for the underlying stream.
	 * @param timeout in msec
	 */
	public void setTimeout(int timeout) {
		if (null != _input)
			_input.setTimeout(timeout);
		else
			_output.setTimeout(timeout);
	}

}
