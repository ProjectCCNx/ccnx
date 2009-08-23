package org.ccnx.ccn.io;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

import com.parc.ccn.library.profiles.SegmentationProfile;

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
	public CCNDescriptor(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle library) 
	throws XMLStreamException, IOException {
		openForReading(name, publisher, library);
	}

	public CCNDescriptor(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws XMLStreamException, IOException {
		openForWriting(name, locator, publisher, library);
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
			KeyLocator locator, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws IOException {
		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(name)) {
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		}
		_output = new CCNVersionedOutputStream(nameToOpen, locator, publisher, library);
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
