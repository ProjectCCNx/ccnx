/**
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

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Right now, this has turned into a wrapper to input and output
 * stream that knows about versioning. It should probably mutate into
 * something else.
 * 
 * This object uses the library functions to do verification
 * and build trust.
 * @author smetters
 *
 */
public class CCNDescriptor {

	public enum OpenMode { O_RDONLY, O_WRONLY }
	public enum SeekWhence {SEEK_SET, SEEK_CUR, SEEK_END};

	protected CCNInputStream _input = null;
	protected CCNOutputStream _output = null;

	/**
	 * Open for reading. This does getLatestVersion, etc on name, and assumes fragmentation.
	 */
	public CCNDescriptor(ContentName name, PublisherPublicKeyDigest publisher, 
						 CCNHandle library, boolean openForWriting) 
										throws XMLStreamException, IOException {
		if (openForWriting) {
			openForWriting(name, publisher, library);
		} else {	
			openForReading(name, publisher, library);
		}
	}

	protected void openForReading(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle library) 
	throws IOException, XMLStreamException {
		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(nameToOpen)) {
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		} 

		_input = new CCNVersionedInputStream(nameToOpen, publisher, library);
	}

	protected void openForWriting(ContentName name, 
								  PublisherPublicKeyDigest publisher,
								  CCNHandle library) throws IOException {
		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(name)) {
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		}
		_output = new CCNVersionedOutputStream(nameToOpen, publisher, library);
	}

	public int available() throws IOException {
		if (!openForReading())
			return 0;
		return _input.available();
	}

	public boolean openForReading() {
		return (null != _input);
	}

	public boolean openForWriting() {
		return (null != _output);
	}

	public void close() throws IOException {
		if (null != _input)
			_input.close();
		else
			_output.close();
	}

	public void flush() throws IOException {
		if (null != _output)
			_output.flush();
	}

	public boolean eof() { 
		return openForReading() ? _input.eof() : false;
	}

	public int read(byte[] buf, int offset, int len) throws IOException {
		if (null != _input)
			return _input.read(buf, offset, len);
		throw new IOException("Descriptor not open for reading!");
	}

	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	public void write(byte[] buf, int offset, int len) throws IOException {
		if (null != _output) {
			_output.write(buf, offset, len);
			return;
		}
		throw new IOException("Descriptor not open for writing!");
	}

	public void setTimeout(int timeout) {
		if (null != _input)
			_input.setTimeout(timeout);
		else
			_output.setTimeout(timeout);
	}

}
