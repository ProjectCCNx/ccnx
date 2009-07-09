package com.parc.ccn.library.io;

import java.io.IOException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.ContentKeys;

/**
 * A CCNInputStream that embodies the Versioning profile. If you
 * ask to open a name that is already versioned, it opens that
 * version for you. If you ask to open a name without a version,
 * it attempts to open the latest version of that name. If you
 * attempt to open a name with a segment marker on it as well,
 * it opens that version of that content at that segment.
 * 
 * The only behavior we have to change from superclass is that
 * involved in getting the first block -- header or regular block.
 * We need to make an interest that gets the latest version, and
 * then fills in the version information on the name we
 * are working with, to make sure we continue to get blocks
 * from the same version (even if, say someone writes another
 * version on top of us).
 * 
 * TODO -- outstanding concern -- depending on when the header arrives,
 * the response of this class may differ (not entirely clear). Given
 * that we're moving away from headers, perhaps, this may not be an
 * issue, but it brings up the point that we have to write unit tests
 * that seed ccnd or the repo with potentially complicating data and
 * make sure we can still retrieve it.
 * @author smetters
 *
 */
public class CCNVersionedInputStream extends CCNInputStream {

	public CCNVersionedInputStream(ContentName name,
			Long startingBlockIndex, PublisherPublicKeyDigest publisher,
			ContentKeys keys, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, startingBlockIndex, publisher, keys, library);
	}

	public CCNVersionedInputStream(ContentName name,
			Long startingBlockIndex, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws XMLStreamException, IOException {
		super(name, startingBlockIndex, publisher, library);
	}

	public CCNVersionedInputStream(ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws XMLStreamException, IOException {
		super(name, publisher, library);
	}

	public CCNVersionedInputStream(ContentName name) throws XMLStreamException,
			IOException {
		super(name);
	}

	public CCNVersionedInputStream(ContentName name, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, library);
	}

	public CCNVersionedInputStream(ContentName name, Long startingBlockIndex)
			throws XMLStreamException, IOException {
		super(name, startingBlockIndex);
	}

	public CCNVersionedInputStream(ContentObject starterBlock,
			CCNLibrary library) throws XMLStreamException, IOException {
		super(starterBlock, library);
	}
	
	protected ContentObject getFirstBlock() throws IOException {
		if (VersioningProfile.isVersioned(_baseName)) {
			return super.getFirstBlock();
		}
		Library.logger().info("getFirstBlock: getting latest version of " + _baseName);
		ContentObject result =  _library.getLatestVersion(_baseName, null, _timeout);
		if (null != result){
			Library.logger().info("getFirstBlock: retrieved latest version object " + result.name() + " type: " + result.signedInfo().getTypeName());
			// Now need to verify the block we got
			if (!verifyBlock(result)) {
				return null;
			}
			_baseName = SegmentationProfile.segmentRoot(result.name());
			// Now we know the version. Did we luck out and get first block?
			if (isFirstBlock(result)) {
				Library.logger().info("getFirstBlock: got first block on first try: " + result.name());
				return result;
			}
			Library.logger().info("getFirstBlock: Have version information, now querying first segment.");
			return super.getFirstBlock(); // now that we have the latest version, go back for the first block.
		} else {
			Library.logger().info("getFirstBlock: no block available for latest version of " + _baseName);
		}
		return result;
	}
	
	public Timestamp getVersionAsTimestamp() throws VersionMissingException {
		if (null == _baseName)
			throw new VersionMissingException("Have not yet retrieved content name!");
		return VersioningProfile.getVersionAsTimestamp(_baseName);
	}
}
