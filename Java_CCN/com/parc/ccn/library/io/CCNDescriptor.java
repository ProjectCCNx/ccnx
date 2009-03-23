package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.PrivateKey;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;

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
	public CCNDescriptor(ContentName name, PublisherKeyID publisher, CCNLibrary library) 
				throws XMLStreamException, IOException {
		openForReading(name, publisher, library);
	}
	
	public CCNDescriptor(ContentName name, PublisherKeyID publisher,
			   KeyLocator locator, PrivateKey signingKey,
			   CCNLibrary library) throws XMLStreamException, IOException {
		openForWriting(name, publisher, locator, signingKey, library);
	}
	
	/**
	 * DKS TODO: Needs to handle both single-put and fragmented content.
	 * @param name
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws XMLStreamException
	 */

	protected void openForReading(ContentName name, PublisherKeyID publisher, CCNLibrary library) 
				throws IOException, XMLStreamException {
		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(nameToOpen)) {
			// DKS TODO: should we do this?
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		} else {
			if (!VersioningProfile.isVersioned(nameToOpen)) {
				// if publisherID is null, will get any publisher
				nameToOpen = 
					library.getLatestVersionName(nameToOpen, publisher);
			}
			nameToOpen = new ContentName(nameToOpen, ContentName.componentParseNative(CCNLibrary.FRAGMENT_MARKER));
		}
		_input = new CCNInputStream(nameToOpen, publisher, library);
	}

	protected void openForWriting(ContentName name, PublisherKeyID publisher,
			   KeyLocator locator, PrivateKey signingKey,
			   CCNLibrary library) throws XMLStreamException, IOException {
		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(name)) {
			// DKS TODO: should we do this?
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		}
		// Assume if name is already versioned, caller knows what name
		// to write. If caller specifies authentication information,
		// ignore it for now.
		if (!VersioningProfile.isVersioned(nameToOpen)) {
			// if publisherID is null, will get any publisher
			ContentName currentVersionName = 
				library.getLatestVersionName(nameToOpen, null);
			if (null == currentVersionName) {
				nameToOpen = VersioningProfile.versionName(nameToOpen, VersioningProfile.baseVersion());
			} else {
				nameToOpen = VersioningProfile.versionName(currentVersionName, (VersioningProfile.getVersionNumber(currentVersionName) + 1));
			}
		}
		_output = new CCNOutputStream(name, publisher, locator, signingKey, library);
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
	}

}
