package org.ccnx.ccn.io;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Timestamp;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

import com.parc.ccn.library.CCNSegmenter;

public abstract class CCNAbstractOutputStream extends OutputStream {

	protected CCNHandle _library = null;
	protected CCNSegmenter _segmenter = null;
	/** 
	 * The name for the content fragments, up to just before the sequence number.
	 */
	protected ContentName _baseName = null;
	protected KeyLocator _locator;
	protected PublisherPublicKeyDigest _publisher;

	/**
	 * The behavior of an output stream derived class is determined
	 * largely by its buffering, segmentation configuration (embodied
	 * in a CCNSegmenter) and flow control (embodied in a CCNFlowControl,
	 * contained within the segmenter).
	 * @param locator
	 * @param publisher
	 * @param segmenter
	 */
	public CCNAbstractOutputStream(KeyLocator locator, 
								   PublisherPublicKeyDigest publisher,
								   CCNSegmenter segmenter) {
		super();
		_segmenter = segmenter;
		_library = _segmenter.getLibrary();
		if (null == _library) {
			_library = CCNHandle.getLibrary();
		}

		// If these are null, the library defaults will be used.
		_locator = locator;
		_publisher = publisher;		
	}
	
	public CCNAbstractOutputStream() {}	// special purpose constructor
	
	protected void startWrite() throws IOException {}

	@Override
	public void write(byte[] b) throws IOException {
		write(b, 0, b.length);
	}

	@Override
	public void write(int b) throws IOException {
		byte buf[] = {(byte)b};
		write(buf, 0, 1);
	}

	public ContentName getBaseName() {
		return _baseName;
	}

	public Timestamp getVersion() {
		if (null == _baseName) 
			return null;
		return VersioningProfile.getTerminalVersionAsTimestampIfVersioned(_baseName);
	}

	public CCNSegmenter getSegmenter() {
		return _segmenter;
	}
	
	public void setTimeout(int timeout) {
		getSegmenter().setTimeout(timeout);
	}
}