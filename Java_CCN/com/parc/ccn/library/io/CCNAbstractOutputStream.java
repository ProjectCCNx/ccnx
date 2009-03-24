package com.parc.ccn.library.io;

import java.io.IOException;
import java.io.OutputStream;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;

public abstract class CCNAbstractOutputStream extends OutputStream {

	protected CCNLibrary _library = null;
	protected CCNSegmenter _segmenter = null;
	/** 
	 * The name for the content fragments, up to just before the sequence number.
	 */
	protected ContentName _baseName = null;
	protected int _blockIndex = 0;
	protected KeyLocator _locator;
	protected PublisherKeyID _publisher;

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
								   PublisherKeyID publisher,
								   CCNSegmenter segmenter) {
		super();
		_segmenter = segmenter;
		_library = _segmenter.getLibrary();
		if (null == _library) {
			_library = CCNLibrary.getLibrary();
		}

		// If these are null, the library defaults will be used.
		_locator = locator;
		_publisher = publisher;		
	}

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

	public CCNSegmenter getSegmenter() {
		return _segmenter;
	}
}