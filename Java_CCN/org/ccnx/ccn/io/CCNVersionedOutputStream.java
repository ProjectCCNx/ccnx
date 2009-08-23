package org.ccnx.ccn.io;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;

import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.security.crypto.ContentKeys;

public class CCNVersionedOutputStream extends CCNOutputStream {

	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, CCNHandle library)
			throws IOException {
		this(name, locator, publisher, null, new CCNSegmenter(new CCNFlowControl(name, library)));
	}

	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentKeys keys, CCNHandle library)
			throws IOException {
		/*
		 * The Flow Controller must register a Filter above the version no. for someone else's
		 * getLatestVersion interests to see this stream.
		 */
		this(name, locator, publisher, null, new CCNSegmenter(
				new CCNFlowControl(VersioningProfile.cutTerminalVersion(name).first(), library), null, keys));
	}

	public CCNVersionedOutputStream(ContentName name, CCNHandle library)
			throws IOException {
		this(name, null, null, library);
	}

	/**
	 * Assume if name is already versioned, the caller knows what name it
	 * wants to write. Otherwise generate a new version number for it.
	 * @param name
	 * @param locator
	 * @param publisher
	 * @param segmenter
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentType type, CCNSegmenter segmenter)
			throws IOException {
		super((VersioningProfile.hasTerminalVersion(name) ? name : VersioningProfile.addVersion(name)), 
				locator, publisher, type, segmenter);
	}

	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentType type, CCNFlowControl flowControl)
			throws IOException {
		this(name, locator, publisher, type, new CCNSegmenter(flowControl));
	}
}
