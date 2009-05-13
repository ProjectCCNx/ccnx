package com.parc.ccn.library;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.IllegalBlockSizeException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.SegmentationProfile.SegmentNumberType;
import com.parc.ccn.security.crypto.CCNAggregatedSigner;
import com.parc.ccn.security.crypto.CCNMerkleTree;
import com.parc.ccn.security.crypto.CCNMerkleTreeSigner;
import com.parc.ccn.security.crypto.ContentKeys;

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
 *    algorithm (standard: AES-CTR, also eventually AES-CBC and other padded
 *    block cipher options), segment content so as
 *    to meet a desired net data length with potential block expansion, and encrypt.
 *    Other specs used to generate K and IV from higher-level data.
 *    
 *    For block ciphers, we require a certain amount of extra space in the
 *    blocks to accommodate padding (a minimum of 1 bytes for PKCS5 padding,
 *    for example). 
 *    DKS TODO -- deal with the padding and length expansion
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
	
	protected CCNLibrary _library;
	// Eventually may not contain this; callers may access it exogenously.
	protected CCNFlowControl _flowControl;
	
	// Handle multi-block amortized signing. If null, default to single-block signing.
	protected CCNAggregatedSigner _bulkSigner;
	
	// Encryption/decryption handler
	protected ContentKeys _keys;

	/**
	 * Eventually add encryption, allow control of authentication algorithm.
	 * @param baseName
	 * @param locator
	 * @param signingKey
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	public CCNSegmenter() throws ConfigurationException, IOException {
		this(CCNLibrary.open());
	}
	
	public CCNSegmenter(ContentKeys keys) throws ConfigurationException, IOException {
		this(CCNLibrary.open(), keys);
	}

	public CCNSegmenter(CCNLibrary library) {
		this(new CCNFlowControl(library));
	}

	public CCNSegmenter(CCNLibrary library, ContentKeys keys) {
		this(new CCNFlowControl(library), null, null);
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

	public CCNSegmenter(CCNFlowControl flowControl, CCNAggregatedSigner signer,
						ContentKeys keys) {
		this(flowControl, signer);
		
		if (keys != null) {
			keys.OnlySupportDefaultAlg();
			_keys = keys;
		}
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
	 * @throws InvalidAlgorithmParameterException 
	 **/
	public long put(
			ContentName name, byte [] content, int offset, int length,
			boolean lastSegments,
			SignedInfo.ContentType type,
			Integer freshnessSeconds, 
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) 
					throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException {

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
		if (outputLength(length) >= getBlockSize()) {
			return fragmentedPut(name, content, offset, length, null,
								 type, freshnessSeconds, locator, publisher);
		} else {
			try {
				// We should only get here on a single-fragment object, where the lastBlocks
				// argument is false (omitted).
				return putFragment(name, SegmentationProfile.baseSegment(), 
						content, offset, length, type,
						null, freshnessSeconds, 
						Long.valueOf(SegmentationProfile.baseSegment()), 
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
	 * @throws InvalidAlgorithmParameterException 
	 */
	protected long fragmentedPut(
			ContentName name, 
			byte [] content, int offset, int length,
			Long finalSegmentIndex,
			SignedInfo.ContentType type,
			Integer freshnessSeconds, 
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) 
			throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, 
						IOException, InvalidAlgorithmParameterException {
		
		return fragmentedPut(name, SegmentationProfile.baseSegment(),
				content, offset, length, getBlockSize(), type,
				null, freshnessSeconds, finalSegmentIndex, locator, publisher);
	}
	
	/** 
	 * @throws InvalidAlgorithmParameterException 
	 * @throws NoSuchAlgorithmException 
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
									SignatureException, IOException, 
									InvalidAlgorithmParameterException, NoSuchAlgorithmException {
		

		// This will call into CCNBase after picking appropriate credentials
		// take content, blocksize (static), divide content into array of 
		// content blocks, call hash fn for each block, call fn to build merkle
		// hash tree.   Build header, for each block, get authinfo for block,
		// (with hash tree, block identifier, timestamp -- SQLDateTime)
		// insert header using mid-level insert, low-level insert for actual blocks.
		if (length == 0)
			return baseSegmentNumber;
		
		if (null == publisher) {
			publisher = getFlowControl().getLibrary().keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = getFlowControl().getLibrary().keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = getFlowControl().getLibrary().keyManager().getKeyLocator(signingKey);

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		getFlowControl().addNameSpace(rootName);

		if (null == type) {
			type = ContentType.DATA;
		}
		
		byte [] finalBlockID = null;
		if (null != finalSegmentIndex) {
			if (finalSegmentIndex.equals(CCNSegmenter.LAST_SEGMENT)) {
				// compute final segment number
				// compute final segment number; which might be this one if blockCount == 1
				int blockCount = CCNMerkleTree.blockCount(length, blockWidth);
				finalBlockID = SegmentationProfile.getSegmentID(
					lastSegmentIndex(baseSegmentNumber, (blockCount-1)*blockWidth, 
												blockCount));
			} else {
				finalBlockID = SegmentationProfile.getSegmentID(finalSegmentIndex);
			}
		}
		
		ContentObject [] contentObjects = 
			buildBlocks(rootName, baseSegmentNumber, 
					new SignedInfo(publisher, timestamp, type, locator, freshnessSeconds, finalBlockID),
					content, offset, length, blockWidth);

		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// and signedInfos in the MerkleTree blocks. 
		// For now, this generates the root signature too, so can
		// ask for the signature for each block.
		_bulkSigner.signBlocks(contentObjects, signingKey);
		getFlowControl().put(contentObjects);

		return nextSegmentIndex(
				SegmentationProfile.getSegmentNumber(contentObjects[contentObjects.length - 1].name()), 
				contentObjects[contentObjects.length - 1].contentLength());
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
						NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException {

		if (blockCount == 0)
			return baseSegmentNumber;

		if (null == publisher) {
			publisher = getFlowControl().getLibrary().keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = getFlowControl().getLibrary().keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = getFlowControl().getLibrary().keyManager().getKeyLocator(signingKey);

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		getFlowControl().addNameSpace(rootName);

		if (null == type) {
			type = ContentType.DATA;
		}

		byte [] finalBlockID = null;
		if (null != finalSegmentIndex) {
			if (finalSegmentIndex.equals(CCNSegmenter.LAST_SEGMENT)) {
				long length = 0;
				for (int j = firstBlockIndex; j < firstBlockIndex + blockCount - 1; j++) {
					length += contentBlocks[j].length;
				}
				// don't include last block length; want intervening byte count before the last block

				// compute final segment number
				finalBlockID = SegmentationProfile.getSegmentID(
						lastSegmentIndex(baseSegmentNumber, length, blockCount));
			} else {
				finalBlockID = SegmentationProfile.getSegmentID(finalSegmentIndex);
			}
		}

		ContentObject [] contentObjects = 
			buildBlocks(rootName, baseSegmentNumber, 
					new SignedInfo(publisher, timestamp, type, locator, freshnessSeconds, finalBlockID),
					contentBlocks, false, blockCount, firstBlockIndex, lastBlockLength);

		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// and signedInfos in the MerkleTree blocks. 
		// For now, this generates the root signature too, so can
		// ask for the signature for each block.
		_bulkSigner.signBlocks(contentObjects, signingKey);
		getFlowControl().put(contentObjects);

		return nextSegmentIndex(
				SegmentationProfile.getSegmentNumber(contentObjects[firstBlockIndex + blockCount - 1].name()), 
				contentObjects[firstBlockIndex + blockCount - 1].contentLength());
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
	 * @throws InvalidAlgorithmParameterException 
	 * @throws BadPaddingException 
	 * @throws IllegalBlockSizeException 
	 */
	public long putFragment(
			ContentName name, long segmentNumber, 
			byte [] content, int offset, int length,
			ContentType type, 
			Timestamp timestamp, 
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, SignatureException, 
							NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException {

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

		if (null != _keys) {
			try {
				// Make a separate cipher, so this segmenter can be used by multiple callers at once.
				Cipher thisCipher = _keys.getSegmentEncryptionCipher(null, segmentNumber);
				content = thisCipher.doFinal(content, offset, length);
				offset = 0;
				length = content.length;
				// Override content type to mark encryption.
				// Note: we don't require that writers use our facilities for encryption, so
				// content previously encrypted may not be marked as type ENCR. So on the decryption
				// side we don't require that encrypted data be marked ENCR -- if you give us a
				// decryption key, we'll try to decrypt it.
				type = ContentType.ENCR; 
				
			} catch (IllegalBlockSizeException e) {
				Library.logger().warning("Unexpected IllegalBlockSizeException for an algorithm we have already used!");
				throw new InvalidKeyException("Unexpected IllegalBlockSizeException for an algorithm we have already used!", e);
			} catch (BadPaddingException e) {
				Library.logger().warning("Unexpected BadPaddingException for an algorithm we have already used!");
				throw new InvalidAlgorithmParameterException("Unexpected BadPaddingException for an algorithm we have already used!", e);
			}
		}
		
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
		
		return nextSegmentIndex(segmentNumber, co.contentLength());
	}
	
	protected ContentObject[] buildBlocks(ContentName rootName,
			long baseSegmentNumber, SignedInfo signedInfo, 
			byte[] content, int offset, int length, int blockWidth) 
							throws InvalidKeyException, InvalidAlgorithmParameterException, IOException {
		
		int blockCount = CCNMerkleTree.blockCount(length, blockWidth);
		ContentObject [] blocks = new ContentObject[blockCount];

		long nextSegmentIndex = baseSegmentNumber;
		
		for (int i=0; i < blockCount; ++i) {
			InputStream dataStream = new ByteArrayInputStream(content, offset, length);
			if (null != _keys) {
				// DKS TODO -- move to streaming version to cut down copies. Here using input
				// streams, eventually push down with this at the end of an output stream.

				// Make a separate cipher, so this segmenter can be used by multiple callers at once.
				Cipher thisCipher = _keys.getSegmentEncryptionCipher(null, nextSegmentIndex);
				Library.logger().finest("Created new encryption cipher "+thisCipher);
				// Override content type to mark encryption.
				// Note: we don't require that writers use our facilities for encryption, so
				// content previously encrypted may not be marked as type ENCR. So on the decryption
				// side we don't require that encrypted data be marked ENCR -- if you give us a
				// decryption key, we'll try to decrypt it.
				signedInfo.setType(ContentType.ENCR);

				dataStream = new CipherInputStream(dataStream, thisCipher);
			}
			blocks[i] =  
				new ContentObject(
						SegmentationProfile.segmentName(rootName, nextSegmentIndex),
						signedInfo,
						dataStream, blockWidth);
			Library.logger().finest("Created content object - segment "+nextSegmentIndex+" before encr="+content[offset]+" after encr="+blocks[i].content()[0]);

			nextSegmentIndex = nextSegmentIndex(nextSegmentIndex, 
												blocks[i].contentLength());
			offset += blockWidth;
			length -= blockWidth;
		}
		return blocks;
	}

	/**
	 * Allow callers who have an opinion about how to segment data.
	 * @param rootName
	 * @param baseSegmentNumber
	 * @param signedInfo
	 * @param contentBlocks
	 * @param isDigest
	 * @param blockCount
	 * @param firstBlockIndex
	 * @param lastBlockLength
	 * @return
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidAlgorithmParameterException 
	 * @throws InvalidKeyException 
	 * @throws BadPaddingException 
	 * @throws IllegalBlockSizeException 
	 */
	protected ContentObject[] buildBlocks(ContentName rootName,
			long baseSegmentNumber, SignedInfo signedInfo,
			byte[][] contentBlocks, boolean isDigest, int blockCount,
			int firstBlockIndex, int lastBlockLength) 
					throws InvalidKeyException, InvalidAlgorithmParameterException {
		
		ContentObject [] blocks = new ContentObject[blockCount];
		if (blockCount == 0)
			return blocks;

		/**
		 * Encryption handling much less efficient here. But we're not sure we
		 * need this interface, so live with it till we need to improve it.
		 */
		long nextSegmentIndex = baseSegmentNumber;
		
		byte [] blockContent;
		int i;
		for (i=firstBlockIndex; i < (firstBlockIndex + blockCount - 1); ++i) {
			blockContent = contentBlocks[i];
			if (null != _keys) {
				try {
					// Make a separate cipher, so this segmenter can be used by multiple callers at once.
					Cipher thisCipher = _keys.getSegmentEncryptionCipher(null, nextSegmentIndex);
					blockContent = thisCipher.doFinal(contentBlocks[i]);

					// Override content type to mark encryption.
					// Note: we don't require that writers use our facilities for encryption, so
					// content previously encrypted may not be marked as type ENCR. So on the decryption
					// side we don't require that encrypted data be marked ENCR -- if you give us a
					// decryption key, we'll try to decrypt it.
					signedInfo.setType(ContentType.ENCR);
					
				} catch (IllegalBlockSizeException e) {
					Library.logger().warning("Unexpected IllegalBlockSizeException for an algorithm we have already used!");
					throw new InvalidKeyException("Unexpected IllegalBlockSizeException for an algorithm we have already used!", e);
				} catch (BadPaddingException e) {
					Library.logger().warning("Unexpected BadPaddingException for an algorithm we have already used!");
					throw new InvalidAlgorithmParameterException("Unexpected BadPaddingException for an algorithm we have already used!", e);
				}

			}
			blocks[i] =  
				new ContentObject(
						SegmentationProfile.segmentName(rootName, nextSegmentIndex),
						signedInfo,
						blockContent, (Signature)null);
			nextSegmentIndex = nextSegmentIndex(nextSegmentIndex, blocks[i].contentLength());
		}
		blockContent = contentBlocks[i];
		if (null != _keys) {
			try {
				Cipher thisCipher = _keys.getSegmentEncryptionCipher(null, nextSegmentIndex);
				blockContent = thisCipher.doFinal(contentBlocks[i], 0, lastBlockLength);
				lastBlockLength = blockContent.length;
				
			} catch (IllegalBlockSizeException e) {
				Library.logger().warning("Unexpected IllegalBlockSizeException for an algorithm we have already used!");
				throw new InvalidKeyException("Unexpected IllegalBlockSizeException for an algorithm we have already used!", e);
			} catch (BadPaddingException e) {
				Library.logger().warning("Unexpected BadPaddingException for an algorithm we have already used!");
				throw new InvalidAlgorithmParameterException("Unexpected BadPaddingException for an algorithm we have already used!", e);
			}
		}
		blocks[i] =  
			new ContentObject(
					SegmentationProfile.segmentName(rootName, nextSegmentIndex),
					signedInfo,
					contentBlocks[i], 0, lastBlockLength,
					(Signature)null);
		return blocks;
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
	
	public void setTimeout(int timeout) {
		getFlowControl().setTimeout(timeout);
    }

	/**
	 * How many content bytes will it take to represent content of length
	 * length, including any padding incurred by encryption?
	 * DKS TODO -- this only works on the blocks asked about; if you ask about
	 *   the length pre-segmentation it will likely give you a wrong answer.
	 * @param inputLength
	 * @return
	 */
	public long outputLength(int inputLength) {
		if (null == _keys) {
			return inputLength;
		} else {
			return _keys.getCipher().getOutputSize(inputLength);
		}
	}
}
