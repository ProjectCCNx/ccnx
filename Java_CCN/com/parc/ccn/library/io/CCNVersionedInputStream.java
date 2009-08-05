package com.parc.ccn.library.io;

import java.io.IOException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentVerifier;
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
	
	@Override
	protected ContentObject getFirstBlock() throws IOException {
		if (VersioningProfile.hasTerminalVersion(_baseName)) {
			// Get exactly this version
			return super.getFirstBlock();
		}
		Library.logger().info("getFirstBlock: getting latest version of " + _baseName);
		ContentObject result = getFirstBlockOfLatestVersion(_baseName, null, _timeout, this, _library);
		if (null != result){
			Library.logger().info("getFirstBlock: retrieved latest version object " + result.name() + " type: " + result.signedInfo().getTypeName());
			_baseName = result.name().cut(_baseName.count() + 1);
		} else {
			Library.logger().info("getFirstBlock: no block available for latest version of " + _baseName);
		}
		return result;
	}
	
	/**
	 * This method returns 
	 * @param desiredName The name of the object we are looking for the first segment of.
	 * 					  If (VersioningProfile.hasTerminalVersion(desiredName) == false), will get latest version it can
	 * 							find of desiredName.
	 * 					  If desiredName has a terminal version, will try to find the first block of content whose
	 * 						    version is *after* desiredName (i.e. getLatestVersion starting from desiredName).
	 * @param startingBlockIndex The desired block number, or SegmentationProfile.baseSegment if null.
	 * @param timeout
	 * @param verifier Cannot be null.
	 * @return 			  The first block of a stream with a version later than desiredName, or null if timeout is reached.
	 * @throws IOException
	 */
	public static ContentObject getFirstBlockOfLatestVersion(ContentName startingVersion, Long startingBlockIndex, long timeout, ContentVerifier verifier, CCNLibrary library) throws IOException {
		
		Library.logger().info("getFirstBlockOfLatestVersion: getting version later than " + startingVersion);
		ContentObject result =  library.getLatestVersion(startingVersion, null, timeout);
		if (null != result){
			Library.logger().info("getFirstBlockOfLatestVersion: retrieved latest version object " + result.name() + " type: " + result.signedInfo().getTypeName());
			// Now need to verify the block we got
			if (!verifier.verifyBlock(result)) {
				return null;
			}
			
			// Now we know the version. Did we luck out and get first block?
			if (isFirstBlock(startingVersion, result, startingBlockIndex)) {
				Library.logger().info("getFirstBlockOfLatestVersion: got first block on first try: " + result.name());
				return result;
			}
			// This isn't the first block. Might be simply a later (cached) segment, or might be something
			// crazy like a repo_start_write. So what we want is to get the version of this new block -- if getLatestVersion
			// is doing its job, we now know the version we want (if we already knew that, we called super.getFirstBlock
			// above. If we get here, _baseName isn't versioned yet. So instead of taking segmentRoot of what we got,
			// which works fine only if we have the wrong segment rather than some other beast entirely (like metadata).
			// So chop off the new name just after the (first) version, and use that. If getLatestVersion is working
			// right, that should be the right thing.
			startingVersion = result.name().cut(startingVersion.count() + 1);
			Library.logger().info("getFirstBlockOfLatestVersion: Have version information, now querying first segment.");
			return CCNAbstractInputStream.getBlock(startingVersion, startingBlockIndex, timeout, verifier, library); // now that we have the latest version, go back for the first block.
		} else {
			Library.logger().info("getFirstBlockOfLatestVersion: no block available for later version of " + startingVersion);
		}
		return result;
	}

	/**
	 * Version of isFirstBlock that expects names to be versioned, and allows that desiredName
	 * won't know what version it wants but will want some version.
	 */
	public static boolean isFirstBlock(ContentName desiredName, ContentObject block, Long startingBlockIndex) {
		if ((null != block) && (SegmentationProfile.isSegment(block.name()))) {
			Library.logger().info("is " + block.name() + " a first block of " + desiredName);
			// In theory, the block should be at most a versioning component different from desiredName.
			// In the case of complex segmented objects (e.g. a KeyDirectory), where there is a version,
			// then some name components, then a segment, desiredName should contain all of those other
			// name components -- you can't use the usual versioning mechanisms to pull first block anyway.
			if (!desiredName.isPrefixOf(block.name())) {
				Library.logger().info("Desired name :" + desiredName + " is not a prefix of block: " + block.name());
				return false;
			}
			int difflen = block.name().count() - desiredName.count();
			if (difflen > 2) {
				Library.logger().info("Have " + difflen + " extra components between " + block.name() + " and desired " + desiredName);
				return false;
			}
			// Now need to make sure that if the difference is more than 1, that difference is
			// a version component.
			if ((difflen == 2) && (!VersioningProfile.isVersionComponent(block.name().component(block.name().count()-2)))) {
				Library.logger().info("The " + difflen + " extra component between " + block.name() + " and desired " + desiredName + " is not a version.");
				
			}
			if (null != startingBlockIndex) {
				return (startingBlockIndex.equals(SegmentationProfile.getSegmentNumber(block.name())));
			} else {
				return SegmentationProfile.isFirstSegment(block.name());
			}
		}
		return false;
	}
	
	protected boolean isFirstBlock(ContentName desiredName, ContentObject block) {
		return isFirstBlock(desiredName, block, _startingBlockIndex);
	}
	
	public Timestamp getVersionAsTimestamp() throws VersionMissingException {
		if (null == _baseName)
			throw new VersionMissingException("Have not yet retrieved content name!");
		return VersioningProfile.getLastVersionAsTimestamp(_baseName);
	}
}
