package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
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
 *    For this, we use the standard Java encryption mechanisms, augmented by
 *    alternative providers (e.g. BouncyCastle for AES-CTR). We just need
 *    a Cipher, a SecretKeySpec, and an IvParameterSpec holding the relevant
 *    key data.
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
	public static final Long LAST_SEGMENT = Long.valueOf(-1);
	
	protected int _blockSize = SegmentationProfile.DEFAULT_BLOCKSIZE;
	protected int _blockIncrement = SegmentationProfile.DEFAULT_INCREMENT;
	protected int _byteScale = SegmentationProfile.DEFAULT_SCALE;
	protected SegmentNumberType _sequenceType = SegmentNumberType.SEGMENT_FIXED_INCREMENT;
	
	protected int _nextBlock  = SegmentationProfile.baseSegment();

	protected CCNLibrary _library;
	// Eventually may not contain this; callers may access it exogenously.
	protected CCNFlowControl _flowControl;
	
	// Handle multi-block amortized signing. If null, default to single-block signing.
	protected CCNAggregatedSigner _bulkSigner;
	
	// Encryption/decryption handler
	protected String _encryptionAlgorithm; // in Java standard form, default CCNCipherFactory.DEFAULT_CIPHER_MODE.
	protected SecretKeySpec _encryptionKey;
	protected IvParameterSpec _masterIV;

	/**
	 * Eventually add encryption, allow control of authentication algorithm.
	 * @param baseName
	 * @param locator
	 * @param signingKey
	 */
	public CCNSegmenter() throws ConfigurationException, IOException {
		this(null, null, null);
	}
	
	public CCNSegmenter(String encryptionAlgorithm, 
						SecretKeySpec encryptionKey, IvParameterSpec masterIV) throws ConfigurationException, IOException {
		this(CCNLibrary.open(), encryptionAlgorithm, encryptionKey, masterIV);
	}

	public CCNSegmenter(CCNLibrary library) {
		this(new CCNFlowControl(library));
	}

	public CCNSegmenter(CCNLibrary library, String encryptionAlgorithm, 
							SecretKeySpec encryptionKey, IvParameterSpec masterIV) {
		this(new CCNFlowControl(library), null, encryptionAlgorithm, encryptionKey, masterIV);
	}
	/**
	 * Create an object with default Merkle hash tree aggregated signing.
	 * @param flowControl
	 */
	public CCNSegmenter(CCNFlowControl flowControl) {
		this(flowControl, null);
	}
	
	public CCNSegmenter(CCNFlowControl flowControl, CCNAggregatedSigner signer) {
		this(flowControl, signer, null, null, null);
	}

	public CCNSegmenter(CCNFlowControl flowControl, CCNAggregatedSigner signer,
						String encryptionAlgorithm, SecretKeySpec encryptionKey, IvParameterSpec masterIV) {
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
		
		_encryptionAlgorithm = encryptionAlgorithm;
		_encryptionKey = encryptionKey;
		_masterIV = masterIV;
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
		setByteScale(1);
	}

	public void useFixedIncrementSequenceNumbers(int increment) {
		setSequenceType(SegmentNumberType.SEGMENT_FIXED_INCREMENT);
		setBlockIncrement(increment);
	}

	public void useScaledByteCountSequenceNumbers(int scale) {
		setSequenceType(SegmentNumberType.SEGMENT_BYTE_COUNT);
		setByteScale(scale);
	}

	public void setSequenceType(SegmentNumberType seqType) { _sequenceType = seqType; }

	/**
	 * Set increment between block numbers.
	 * @param blockWidth
	 */
	public void setBlockIncrement(int blockIncrement) { _blockIncrement = blockIncrement; }
	public int getBlockIncrement() { return _blockIncrement; }

	public void setByteScale(int byteScale) { _byteScale = byteScale; }
	public int getByteScale() { return _byteScale; }


	/**
	 * Put a complete data item, potentially fragmented. The
	 * assumption of this API is that this single call puts
	 * all the blocks of the item; if multiple calls to the
	 * segmenter will be required to output an item, use other
	 * methods to manage segment identifiers.
	 * 
	 * If small enough, doesn't fragment. Otherwise, does.
	 * Return ContentObject of the thing they put (in the case
	 * of a fragmented thing, the first fragment). That way the
	 * caller can then also easily link to that thing if
	 * it needs to, or put again with a different name.
	 * If multi-fragment, uses the naming profile and specified
	 * bulk signer (default: MHT) to generate names and signatures.
	 **/
	public long put(
			ContentName name, byte [] content, int offset, int length,
			boolean lastSegments,
			SignedInfo.ContentType type,
			Integer freshnessSeconds, 
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {

		if (null == publisher) {
			publisher = _library.keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = _library.keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);

		if (null == type) {
			type = ContentType.DATA;
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
	 * Low-level segmentation interface. Assume arguments have been cleaned
	 * prior to arrival -- name is not already segmented, type is set, etc.
	 * 
	 * Starts segmentation at segment SegmentationProfile().baseSegment().
	 * @param name
	 * @param content content buffer containing content to put
	 * @param offset offset into buffer at which to start reading content to put
	 * @param length number of bytes of buffer to put
	 * @param finalSegmentIndex the expected segment number of the last segment of this stream,
	 * 				null to omit, Long(-1) to set as the last segment of this put, whatever
	 * 				its number turns out to be
	 * @param type
	 * @param freshnessSeconds the number of seconds this content should be considered fresh, or null
	 * 			to leave unset
	 * @param locator
	 * @param publisher
	 * @return returns the segment identifier for the next segment to be written, if any.
	 * 		If the caller doesn't want to override this, they can hand this number back
	 * 	    to a subsequent call to fragmentedPut.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException 
	 */
	protected long fragmentedPut(
			ContentName name, 
			byte [] content, int offset, int length,
			Long finalSegmentIndex,
			SignedInfo.ContentType type,
			Integer freshnessSeconds, 
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		
		return fragmentedPut(name, SegmentationProfile.baseSegment(),
				content, offset, length, getBlockSize(), type,
				null, freshnessSeconds, finalSegmentIndex, locator, publisher);
	}
	
	/** 
	 * @see CCNSegmenter#fragmentedPut(ContentName, byte[], int, int, Long, ContentType, Integer, KeyLocator, PublisherPublicKeyDigest)
	 * Starts segmentation at segment SegmentationProfile().baseSegment().
	 */
	public long fragmentedPut(
			ContentName name, long baseSegmentNumber,
			byte [] content, int offset, int length, int blockWidth,
			ContentType type, 
			Timestamp timestamp,
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, 
									SignatureException, NoSuchAlgorithmException, IOException {
		

		// This will call into CCNBase after picking appropriate credentials
		// take content, blocksize (static), divide content into array of 
		// content blocks, call hash fn for each block, call fn to build merkle
		// hash tree.   Build header, for each block, get authinfo for block,
		// (with hash tree, block identifier, timestamp -- SQLDateTime)
		// insert header using mid-level insert, low-level insert for actual blocks.
		long result = 
			_bulkSigner.putBlocks(this, name, baseSegmentNumber,
						content, offset, length, blockWidth, type,
						timestamp, freshnessSeconds, finalSegmentIndex, locator, publisher);
		// Used to return header. Now return first block.
		return result;
	}

	public long fragmentedPut(
			ContentName name, long baseSegmentNumber,
			byte [][] contentBlocks, int blockCount, 
			int firstBlockIndex, int lastBlockLength,
			ContentType type, 
			Timestamp timestamp,
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException {
		
		long result = 
			_bulkSigner.putBlocks(this, name, baseSegmentNumber,
						contentBlocks, blockCount, firstBlockIndex, lastBlockLength, type,
						timestamp, freshnessSeconds, finalSegmentIndex, locator, publisher);
		// Used to return header. Now return first block.
		return result;
	}

	/**
	 * Puts a single block of content using a fragment naming convention.
	 * @param name
	 * @param segmentNumber
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
	public long putFragment(
			ContentName name, long segmentNumber, 
			byte [] content, int offset, int length,
			ContentType type, 
			Timestamp timestamp, 
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {

		if (null == publisher) {
			publisher = _library.keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = _library.keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);

		if (null == type) {
			type = ContentType.DATA;
		}

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		_flowControl.addNameSpace(rootName);
		
		byte [] finalBlockID = ((null == finalSegmentIndex) ? null : 
								((finalSegmentIndex.equals(LAST_SEGMENT)) ? 
										SegmentationProfile.getSegmentID(segmentNumber) : 
											SegmentationProfile.getSegmentID(finalSegmentIndex)));

		ContentObject co = 
			new ContentObject(SegmentationProfile.segmentName(rootName, 
					segmentNumber),
					new SignedInfo(publisher, timestamp,
							type, locator,
							freshnessSeconds, 
							finalBlockID), 
							content, offset, length, signingKey);
		Library.logger().info("CCNSegmenter: putting " + co.name() + " (timestamp: " + co.signedInfo().getTimestamp() + ", length: " + length + ")");
		_flowControl.put(co);
		
		return nextSegmentIndex(segmentNumber, content.length);
	}
	
	/**
	 * Increment segment number according to the profile.
	 * @param lastSegmentNumber
	 * @param lastSegmentLength
	 * @return
	 */
	public long nextSegmentIndex(long lastSegmentNumber, long lastSegmentLength) {
		if (SegmentNumberType.SEGMENT_FIXED_INCREMENT == _sequenceType) {
			return lastSegmentNumber + getBlockIncrement();
		} else if (SegmentNumberType.SEGMENT_BYTE_COUNT == _sequenceType) {
			return lastSegmentNumber + (getByteScale() * lastSegmentLength);
		} else {
			Library.logger().warning("Unknown segmentation type: " + _sequenceType);
			return lastSegmentNumber + 1;
		}
	}

	/**
	 * Compute the index of the last block of a set of segments, according to the
	 * profile.
	 * @param currentSegmentNumber
	 * @param bytesIntervening
	 * @param blocksRemaining
	 * @return
	 */
	public Long lastSegmentIndex(long currentSegmentNumber, long bytesIntervening, int blocksRemaining) {
		if (SegmentNumberType.SEGMENT_FIXED_INCREMENT == _sequenceType) {
			return currentSegmentNumber + (getBlockIncrement() * (blocksRemaining - 1));
		} else if (SegmentNumberType.SEGMENT_BYTE_COUNT == _sequenceType) {
			// don't want this, want number of bytes prior to last block
			return currentSegmentNumber + (getByteScale() * bytesIntervening);
		} else {
			Library.logger().warning("Unknown segmentation type: " + _sequenceType);
			return currentSegmentNumber + (blocksRemaining - 1);
		}
	}
}
