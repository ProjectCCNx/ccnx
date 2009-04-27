package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.security.crypto.ContentKeys;

public class CCNFileOutputStream extends CCNVersionedOutputStream {

	public CCNFileOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentKeys keys,
			CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, locator, publisher, library);
	}

	public CCNFileOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, locator, publisher, library);
	}

	public CCNFileOutputStream(ContentName name, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, library);
	}

	public CCNFileOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentType type, CCNSegmenter segmenter)
			throws XMLStreamException, IOException {
		super(name, locator, publisher, type, segmenter);
	}

	public CCNFileOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, CCNFlowControl flowControl)
			throws XMLStreamException, IOException {
		super(name, locator, publisher, flowControl);
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
	
	protected ContentObject putHeader(
			ContentName name, long contentLength, int blockSize, byte [] contentDigest, 
			byte [] contentTreeAuthenticator,
			Timestamp timestamp, 
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws IOException, InvalidKeyException, SignatureException {

		if (null == publisher) {
			publisher = _library.keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = _library.keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);

		// Add another differentiator to avoid making header
		// name prefix of other valid names?
		ContentName headerName = SegmentationProfile.headerName(name);
		Header header;
		try {
			header = new Header(headerName, contentLength, contentDigest, contentTreeAuthenticator, blockSize,
								publisher, locator, signingKey);
		} catch (XMLStreamException e) {
			Library.logger().warning("This should not happen: we cannot encode our own header!");
			Library.warningStackTrace(e);
			throw new IOException("This should not happen: we cannot encode our own header!" + e.getMessage());
		}
		ContentObject headerResult = null;
		try {
			headerResult = getSegmenter().getFlowControl().put(header);
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own header!");
			Library.warningStackTrace(e);
			throw e;
		}
		return headerResult;		
	}
}
