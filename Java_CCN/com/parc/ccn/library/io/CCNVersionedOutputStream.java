package com.parc.ccn.library.io;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.ContentKeys;

public class CCNVersionedOutputStream extends CCNOutputStream {

	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, CCNLibrary library)
			throws IOException {
		this(name, locator, publisher, null, new CCNSegmenter(new CCNFlowControl(name, library)));
	}

	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentKeys keys, CCNLibrary library)
			throws IOException {
		/*
		 * The Flow Controller must register a Filter above the version no. for someone else's
		 * getLatestVersion interests to see this stream.
		 */
		this(name, locator, publisher, null, new CCNSegmenter(
				new CCNFlowControl(VersioningProfile.cutTerminalVersion(name).first(), library), null, keys));
	}

	public CCNVersionedOutputStream(ContentName name, CCNLibrary library)
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
