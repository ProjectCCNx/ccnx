package com.parc.ccn.library.io;

import java.io.IOException;
import java.io.OutputStream;
import java.security.PrivateKey;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;

public abstract class CCNAbstractOutputStream extends OutputStream {

	protected CCNLibrary _library = null;
	/** 
	 * The name for the content fragments, up to just before the sequence number.
	 */
	protected ContentName _baseName = null;
	protected int _blockIndex = 0;
	protected PublisherKeyID _publisher;
	protected KeyLocator _locator;
	protected PrivateKey _signingKey;
	protected SignedInfo.ContentType _type;

	public CCNAbstractOutputStream(PublisherKeyID publisher,
								   KeyLocator locator, PrivateKey signingKey,
								   CCNLibrary library) {
		super();
		_library = library; 
		if (null == _library) {
			_library = CCNLibrary.getLibrary();
		}
		
		// If these are null, the library defaults will be used.
		_publisher = publisher;
		_locator = locator;
		_signingKey = signingKey;
		
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

}