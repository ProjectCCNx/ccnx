package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.HeaderData;
import com.parc.ccn.data.content.HeaderData.HeaderObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.security.crypto.ContentKeys;

public class CCNFileOutputStream extends CCNVersionedOutputStream {

	public CCNFileOutputStream(ContentName name,
			PublisherPublicKeyDigest publisher, ContentKeys keys,
			CCNLibrary library)
			throws IOException {
		super(name, null, publisher, keys, library);
	}

	public CCNFileOutputStream(ContentName name,
			PublisherPublicKeyDigest publisher, CCNLibrary library)
			throws IOException {
		super(name, null, publisher, library);
	}

	public CCNFileOutputStream(ContentName name, CCNLibrary library)
			throws IOException {
		super(name, library);
	}

	public CCNFileOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentType type, CCNSegmenter segmenter)
			throws IOException {
		super(name, locator, publisher, type, segmenter);
	}

	public CCNFileOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentType type, CCNFlowControl flowControl)
			throws IOException {
		super(name, locator, publisher, type, flowControl);
	}

	protected void writeHeader() throws InvalidKeyException, SignatureException, IOException, InterruptedException {
		// What do we put in the header if we have multiple merkle trees?
		putHeader(_baseName, lengthWritten(), getBlockSize(), _dh.digest(), null,
				_timestamp, _locator, _publisher);
		Library.logger().info("Wrote header: " + SegmentationProfile.headerName(_baseName));
	}
	
	/**
	 * Override this, not close(), because CCNOutputStream.close() currently
	 * calls waitForPutDrain, and we don't want to call that till after we've put the header.
	 * 
	 * When we can, we might want to write the header earlier. Here we wait
	 * till we know how many bytes are in the file.
	 */
	@Override
	protected void closeNetworkData() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, 
								IOException, InterruptedException {
		super.closeNetworkData();
		writeHeader();
	}
	
	protected void putHeader(
			ContentName name, long contentLength, int blockSize, byte [] contentDigest, 
			byte [] contentTreeAuthenticator,
			Timestamp timestamp, 
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws IOException, InvalidKeyException, SignatureException {

		if (null == publisher) {
			publisher = _library.keyManager().getDefaultKeyID();
		}

		// Add another differentiator to avoid making header
		// name prefix of other valid names?
		try {
			ContentName headerName = SegmentationProfile.headerName(name);
			HeaderData headerData = new HeaderData(contentLength, contentDigest, contentTreeAuthenticator, blockSize);
			HeaderObject header = new HeaderObject(headerName, headerData, publisher, locator, _library);
			header.save();
		} catch (XMLStreamException e) {
			Library.logger().warning("This should not happen: we cannot encode our own header!");
			Library.warningStackTrace(e);
			throw new IOException("This should not happen: we cannot encode our own header!" + e.getMessage());
		}
	}
}
