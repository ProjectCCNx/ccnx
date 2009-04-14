package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.SegmentationProfile.SegmentNumberType;
import com.parc.ccn.security.crypto.CCNAggregatedSigner;
import com.parc.ccn.security.crypto.CCNMerkleTreeSigner;

/**
 * This class combines basic segmentation, signing and encryption; 
 * the intent is to provide a user-friendly, efficient, minimum-copy interface,
 * with some support for extensibility.
 * a) simple segmentation -- contiguous blocks (fixed or variable width),
 * 	  or sparse blocks, e.g. at various time offsets. Configurations set
 *    the numbering scheme. The two interfaces are either contiguous writes,
 *    or (for the sparse case), writes of individual segments specified by
 *    offset (can be multi-buffered, as may be multiple KB). 
 *    
 *    Simplest way to handle might be to expect contiguous blocks (either
 *    increments or byte count) and remember what we last wrote, so next
 *    call gets next value. Clients writing sparse blocks (only byte count
 *    or scaled byte count makes sense for this) can override by setting
 *    counter on a call.
 *    
 * b) signing control -- per-block signing with a choice of signature algorithm,
 *    or amortized signing, first Merkle Hash Tree based amortization, later
 *    options for other things.
 * c) stock low-level encryption. Given a key K, an IV, and a chosen encryption
 *    algorithm (standard: AES-CTR, also support AES-CBC), segment content so as
 *    to meet a desired net data length with potential block expansion, and encrypt.
 *    Other specs used to generate K and IV from higher-level data.
 *    
 * Overall, attempt to minimize copying of data. Data must be copied into final
 * ContentObjects returned by the signing operations. On the way, it may need
 * to pass through a block encrypter, which may perform local copies. Higher-level
 * constructs, such as streams, may buffer it above. If possible, limit copies
 * within this class. Even better, provide functionality that let client stream
 * classes limit copies (e.g. by partially creating data-filled content objects,
 * and not signing them till flush()). But start with the former.
 * 
 *  
 * DKS TODO -- sort out name increments for all segmenter clients
 * @author smetters
 *
 */
public class CCNSegmenter {

	public static final String PROP_BLOCK_SIZE = "ccn.lib.blocksize";
	protected int _blockSize = SegmentationProfile.DEFAULT_BLOCKSIZE;
	protected int _blockIncrement = SegmentationProfile.DEFAULT_INCREMENT;
	protected int _blockScale = SegmentationProfile.DEFAULT_SCALE;
	protected SegmentNumberType _sequenceType = SegmentNumberType.SEGMENT_FIXED_INCREMENT;
	
	protected int _nextBlock  = SegmentationProfile.baseSegment();

	protected CCNLibrary _library;
	// Eventually may not contain this; callers may access it exogenously.
	protected CCNFlowControl _flowControl;
	
	// Handle multi-block amortized signing. If null, default to single-block signing.
	protected CCNAggregatedSigner _bulkSigner;

	/**
	 * Eventually add encryption, allow control of authentication algorithm.
	 * @param baseName
	 * @param locator
	 * @param signingKey
	 */
	public CCNSegmenter() throws ConfigurationException, IOException {
		this(CCNLibrary.open());
	}

	public CCNSegmenter(CCNLibrary library) {
		this(new CCNFlowControl(library));
	}

	/**
	 * Create an object with default Merkle hash tree aggregated signing.
	 * @param flowControl
	 */
	public CCNSegmenter(CCNFlowControl flowControl) {
		this(flowControl, null);
	}

	public CCNSegmenter(CCNFlowControl flowControl, CCNAggregatedSigner signer) {
		if ((null == flowControl) || (null == flowControl.getLibrary())) {
			// Tries to get a library or make a flow control, yell if we fail.
			throw new IllegalArgumentException("CCNSegmenter: must provide a valid library or flow controller.");
		} 
		_flowControl = flowControl;
		_library = _flowControl.getLibrary();
		if (null == signer) {
			_bulkSigner = new CCNMerkleTreeSigner();
		} else {
			_bulkSigner = signer; // if null, default to merkle tree
		}
		initializeBlockSize();
	}

	protected void initializeBlockSize() {
		String blockString = System.getProperty(PROP_BLOCK_SIZE);
		if (null != blockString) {
			try {
				_blockSize = new Integer(blockString).intValue();
				Library.logger().info("Using specified fragmentation block size " + _blockSize);
			} catch (NumberFormatException e) {
				// Do nothing
				Library.logger().warning("Error: malformed property value " + PROP_BLOCK_SIZE + ": " + blockString + " should be an integer.");
			}
		}
	}
	
	public static CCNSegmenter getBlockSegmenter(int blockSize, CCNFlowControl flowControl) {
		CCNSegmenter segmenter = new CCNSegmenter(flowControl);
		segmenter.useFixedIncrementSequenceNumbers(1);
		segmenter.setBlockSize(blockSize);
		return segmenter;
	}

	public static CCNSegmenter getScaledByteCountSegmenter(int scale, CCNFlowControl flowControl) {
		CCNSegmenter segmenter = new CCNSegmenter(flowControl);
		segmenter.useScaledByteCountSequenceNumbers(scale);
		return segmenter;
	}
	
	public static CCNSegmenter getByteCountSegmenter(CCNFlowControl flowControl) {
		return getScaledByteCountSegmenter(1, flowControl);
	}
	
	public CCNLibrary getLibrary() { return _library; }
	
	public CCNFlowControl getFlowControl() { return _flowControl; }

	/**
	 * Set the fragmentation block size to use
	 * @param blockSize
	 */
	public void setBlockSize(int blockSize) {
		_blockSize = blockSize;
	}

	public int getBlockSize() {
		return _blockSize;
	}

	public void useByteCountSequenceNumbers() {
		setSequenceType(SegmentNumberType.SEGMENT_BYTE_COUNT);
		setBlockScale(1);
	}

	public void useFixedIncrementSequenceNumbers(int increment) {
		setSequenceType(SegmentNumberType.SEGMENT_FIXED_INCREMENT);
		setBlockIncrement(increment);
	}

	public void useScaledByteCountSequenceNumbers(int scale) {
		setSequenceType(SegmentNumberType.SEGMENT_BYTE_COUNT);
		setBlockScale(scale);
	}

	public void setSequenceType(SegmentNumberType seqType) { _sequenceType = seqType; }

	/**
	 * Set increment between block numbers.
	 * @param blockWidth
	 */
	public void setBlockIncrement(int blockIncrement) { _blockIncrement = blockIncrement; }

	public void setBlockScale(int blockScale) { _blockScale = blockScale; }


	/**
	 * If small enough, doesn't fragment. Otherwise, does.
	 * Return ContentObject of the thing they put (in the case
	 * of a fragmented thing, the header). That way the
	 * caller can then also easily link to that thing if
	 * it needs to, or put again with a different name.
	 * 
	 * We want to generate a unique name (just considering
	 * the name part) for transport and routing layer efficiency. 
	 * We want to do this in a way that
	 * gives us the following properties:
	 * <ol>
	 * <li>General CCN nodes do not need to understand any
	 *   name components.
	 * <li>General CCN nodes can verify content signatures if
	 * 	 they feel so inclined. That means that any components
	 *   added to the name to make it unique must be signed
	 *   along with the rest of the name.
	 * <li>General CCN nodes need to know as few algorithms
	 *   for verifying content signatures as possible; at
	 *   minimum one for leaf content and one for fragmented
	 *   content (probably also one for streamed content).
	 * <li>If a particular CCN node wishes to interpret the
	 * 	 content of the additional component (or query over it),
	 * 	 they can, but we don't require them to be able to.
	 * <li>Making content names unique shouldn't interfere with
	 * 	 making names or content private. Content can be encrypted
	 *   before hashing; name components could be encrypted even
	 *   after uniquification (so no one can tell that two blocks
	 *   have the same content, or even anything about the block
	 *   that maps to a name).
	 * </ol>
	 * Requiring the result to be unique means that the additional
	 * component added can't simply be the content digest, or
	 * the publisher ID. Either of these could be useful, but
	 * neither is guaranteed to be unique. The signature is guaranteed
	 * to be unique, but including the signature in the name itself
	 * (or the digest of the signature, etc) means that the name
	 * cannot be completely signed -- as the signature can't be
	 * included in the name for signing. At least the user-intended
	 * part of the name must signed, and including the signature
	 * in a distinguished component of the name means that CCN
	 * nodes must understand what parts of the name are signed
	 * and what aren't. While this is necessarily true, e.g. for
	 * fragmented data (see below), you either need a way to
	 * verify the remainder of the name (which is possible for
	 * fragmented data), or only require users to sign name prefixes.
	 * It is much better to require verification of the entire
	 * name, either by signing it completely (for unfragmented data),
	 * or by including the fragment names in the block information
	 * incorporated in the hash tree for signing (see below).
	 * So, what we use for unfragmented data is the digest of 
	 * the content signedInfo without the signature in it; 
	 * which in turn contains the digest
	 * of the content itself, as well as the publisher ID and
	 * the timestamp (which will make it unique). When we generate
	 * the signature, we still sign the name, the content signedInfo,
	 * and the content, as we cannot guarantee that the content
	 * signedInfo digest has been incorporated in the name.
	 * 
	 * For fragmented data, we only generate one signature,
	 * on the root of the Merkle hash tree. For that we use
	 * this same process to generate a unique name from the
	 * content name and content information. However, we then
	 * decorate that name to create the individual block names;
	 * rather than have CCN nodes understand how to separate
	 * that decoration and verify it, we incorporate the block
	 * names into the Merkle hash tree.
	 * 
	 **/
	public ContentObject put(
			ContentName name, byte [] content, int offset, int length,
			boolean lastSegments,
			SignedInfo.ContentType type,
			Integer freshnessSeconds, 
			KeyLocator locator, 
			PublisherKeyID publisher) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {

		if (null == publisher) {
			publisher = _library.keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = _library.keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);

		if (null == type) {
			// DKS TODO -- default to DATA
			type = ContentType.FRAGMENT;
		}

		// Remove existing segmentation markers on end of name, at point right
		// before put. If do it sooner, have to re-do it just to be sure.
		if (SegmentationProfile.isSegment(name)) {
			Library.logger().info("Asked to store fragments under fragment name: " + name + ". Stripping fragment information");
		}

		// DKS TODO -- take encryption overhead into account
		// DKS TODO -- hook up last segment
		if (length >= getBlockSize()) {
			return fragmentedPut(name, content, offset, length, null,
								 type, freshnessSeconds, locator, publisher);
		} else {
			try {
				// We should only get here on a single-fragment object, where the lastBlocks
				// argument is false (omitted).
				return putFragment(name, SegmentationProfile.baseSegment(), 
								   content, offset, length, type,
								  null, freshnessSeconds, null, 
						// SegmentationProfile.baseSegment(), // DKS TODO -- when can deserialize, put this here
															// right now it's not going out on the wire, so coming
															// back off wire null.
						locator, publisher);
			} catch (IOException e) {
				Library.logger().warning("This should not happen: put failed with an IOException.");
				Library.warningStackTrace(e);
				throw e;
			}
		}
	}

	/** 
	 * Low-level fragmentation interface. Assume arguments have been cleaned
	 * prior to arrival -- name is not already segmented, type is set, etc.
	 * @param name
	 * @param contents
	 * @param type
	 * @param publisher
	 * @param locator
	 * @param signingKey
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException 
	 */
	protected ContentObject fragmentedPut(
			ContentName name, 
			byte [] content, int offset, int length,
			Integer lastSegment,
			SignedInfo.ContentType type,
			Integer freshnessSeconds, 
			KeyLocator locator, 
			PublisherKeyID publisher) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		
		return fragmentedPut(name, SegmentationProfile.baseSegment(),
				content, offset, length, getBlockSize(), type,
				null, freshnessSeconds, lastSegment, locator, publisher);
	}
	
	public ContentObject fragmentedPut(
			ContentName name, int baseNameIndex,
			byte [] content, int offset, int length, int blockWidth,
			ContentType type, 
			Timestamp timestamp,
			Integer freshnessSeconds, Integer lastSegment,
			KeyLocator locator, 
			PublisherKeyID publisher) throws InvalidKeyException, 
									SignatureException, NoSuchAlgorithmException, IOException {
		

		// This will call into CCNBase after picking appropriate credentials
		// take content, blocksize (static), divide content into array of 
		// content blocks, call hash fn for each block, call fn to build merkle
		// hash tree.   Build header, for each block, get authinfo for block,
		// (with hash tree, block identifier, timestamp -- SQLDateTime)
		// insert header using mid-level insert, low-level insert for actual blocks.
		ContentObject result = 
			_bulkSigner.putBlocks(this, name, baseNameIndex,
						content, offset, length, blockWidth, type,
						timestamp, freshnessSeconds, lastSegment, locator, publisher);
		// Used to return header. Now return first block.
		return result;
	}

	public ContentObject fragmentedPut(
			ContentName name, int baseNameIndex,
			byte [][] contentBlocks, int blockCount, 
			int baseBlockIndex, int lastBlockLength,
			ContentType type, 
			Timestamp timestamp,
			Integer freshnessSeconds, Integer lastSegment,
			KeyLocator locator, 
			PublisherKeyID publisher) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException {
		
		ContentObject result = 
			_bulkSigner.putBlocks(this, name, baseNameIndex,
						contentBlocks, blockCount, baseBlockIndex, lastBlockLength, type,
						timestamp, freshnessSeconds, lastSegment, locator, publisher);
		// Used to return header. Now return first block.
		return result;
	}

	/**
	 * DKS TODO -- may need to be tweaked
	 * 
	 * Use this to put a set of unrelated content blocks. May need
	 * fancier version that allows sub-itemst to segment.
	 * @params names the individual names of the content items to put
	 */
	public ContentObject fragmentedPut(
			ContentName [] names, 
			byte [][] contentBlocks, int blockCount, 
			int baseBlockIndex, int lastBlockLength,
			ContentType type, 
			Timestamp timestamp,
			Integer freshnessSeconds, Integer lastSegment,
			KeyLocator locator, 
			PublisherKeyID publisher) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException {

		ContentObject result = 
			_bulkSigner.putBlocks(this, names, 
						contentBlocks, blockCount, baseBlockIndex, lastBlockLength, type,
						timestamp, freshnessSeconds, lastSegment, locator, publisher);
		// Used to return header. Now return first block.
		return result;
	}


	/**
	 * Puts a single block of content using a fragment naming convention.
	 * @param name
	 * @param fragmentNumber
	 * @param content
	 * @param timestamp
	 * @param publisher
	 * @param locator
	 * @param signingKey
	 * @return
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	public ContentObject putFragment(
			ContentName name, long fragmentNumber, 
			byte [] content, int offset, int length,
			ContentType type, 
			Timestamp timestamp, 
			Integer freshnessSeconds, Integer lastSegment,
			KeyLocator locator, 
			PublisherKeyID publisher) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {

		if (null == publisher) {
			publisher = _library.keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = _library.keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);

		if (null == type) {
			// DKS TODO -- default to DATA
			type = ContentType.FRAGMENT;
		}

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		_flowControl.addNameSpace(rootName);

		ContentObject co = 
			new ContentObject(SegmentationProfile.segmentName(rootName, 
					fragmentNumber),
					new SignedInfo(publisher, timestamp,
							type, locator,
							freshnessSeconds, lastSegment), 
							content, offset, length, signingKey);
		Library.logger().info("CCNSegmenter: putting " + co.name() + " (timestamp: " + co.signedInfo().getTimestamp() + ", length: " + length + ")");
		return _flowControl.put(co);
	}
}
