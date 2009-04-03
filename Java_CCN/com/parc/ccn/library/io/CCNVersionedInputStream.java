package com.parc.ccn.library.io;

import java.io.IOException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;

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
 * @author smetters
 *
 */
public class CCNVersionedInputStream extends CCNInputStream {

	public CCNVersionedInputStream(ContentName name,
			Long startingBlockIndex, PublisherKeyID publisher,
			CCNLibrary library) throws XMLStreamException, IOException {
		super(name, startingBlockIndex, publisher, library);
	}

	public CCNVersionedInputStream(ContentName name, PublisherKeyID publisher,
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

	public CCNVersionedInputStream(ContentName name, int blockNumber)
			throws XMLStreamException, IOException {
		super(name, blockNumber);
	}

	public CCNVersionedInputStream(ContentObject starterBlock,
			CCNLibrary library) throws XMLStreamException, IOException {
		super(starterBlock, library);
	}
	
	protected ContentObject getFirstBlock() throws IOException {
		if (VersioningProfile.isVersioned(_baseName)) {
			super.getFirstBlock();
		}
		Library.logger().info("getFirstBlock: getting latest version of " + _baseName);
		// This might get us the header instead...
		ContentObject result =  _library.getLatestVersion(_baseName, null, _timeout);
		if (null != result){
			Library.logger().info("getFirstBlock: retrieved " + result.name());
			if (result.signedInfo().getType() == ContentType.HEADER) {
				if (!addHeader(result)) { // verifies
					Library.logger().warning("Retrieved header in getFirstBlock, but failed to process it.");
				}
				_baseName = SegmentationProfile.headerRoot(result.name());
				// now we know the version
				return super.getFirstBlock();
			}
			// Now need to verify the block we got
			if (!verifyBlock(result)) {
				return null;
			}
			// Now we know the version
			_baseName = SegmentationProfile.segmentRoot(result.name());
			retrieveHeader(_baseName, new PublisherID(result.signedInfo().getPublisherKeyID()));
			// This is unlikely -- we ask for a specific segment of the latest
			// version... in that case, we pull the first segment, then seek.
			if (null != _startingBlockIndex) {
				return getBlock(_startingBlockIndex);
			}
		}
		return result;
	}
	
	public Timestamp getVersionAsTimestamp() throws VersionMissingException {
		if (null == _baseName)
			throw new VersionMissingException("Have not yet retrieved content name!");
		return VersioningProfile.getVersionAsTimestamp(_baseName);
	}
}
