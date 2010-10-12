/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.logging.Level;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.security.crypto.CCNAggregatedSigner;
import org.ccnx.ccn.impl.security.crypto.CCNMerkleTree;
import org.ccnx.ccn.impl.security.crypto.CCNMerkleTreeSigner;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.security.crypto.UnbufferedCipherInputStream;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.SegmentationProfile.SegmentNumberType;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.Signature;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Combines segmentation, signing and encryption. This is used
 * to prepare data for writing out to ccnd. The intent is to provide a user-friendly,
 * efficient, minimum-copy interface, with some support for extensibility.
 * 
 * <ul>
 * <li>
 *   Segmentation is the division of a large piece of content into multiple
 *   smaller content objects. A segment component is appended to the content
 *   name to distinguish different segments.
 *   @see org.ccnx.ccn.profiles.SegmentationProfile
 * 
 *    This class currently supports a range of quite complex
 *    segmentation options. At this point, only a subset of these are supported
 *    by the higher level org.ccnx.ccn.io interfaces.
 *    
 *    Contiguous blocks (fixed or variable size), or sparse blocks, e.g. at various
 *    time offsets. Configurations set the numbering scheme. The two interfaces
 *    are either contiguous writes, or (for the sparse case), writes of individual
 *    segments specified by offset (can be multi-buffered, as may be multiple KB).
 *    
 *    Simplest way to handle might be to expect contiguous blocks (either
 *    increments or byte count) and remember what we last wrote, so next
 *    call gets next value. Clients writing sparse blocks (only byte count
 *    or scaled byte count makes sense for this) can override by setting
 *    counter on a call.</li>
 *    
 * <li>Signing Control -- per ContentObject signing with a choice of signature algorithm,
 *    or amortized signing. The default is Merkle Hash Tree based amortization, later
 *    there will be other options.
 *    @see org.ccnx.ccn.impl.security.crypto.CCNMerkleTreeSigner</li>
 *    
 * <li>Stock low-level encryption. --
 *    Given a key K, an IV, and a chosen encryption algorithm segment content so as
 *    to meet a desired net data length with potential block expansion, and encrypt.
 *    
 *    For this, we use the standard Java encryption mechanisms, augmented by
 *    alternative providers (e.g. BouncyCastle for AES-CTR). We just need
 *    a Cipher, a SecretKeySpec, and an IvParameterSpec holding the relevant
 *    key data.
 *    
 *    For block ciphers, we require a certain amount of extra space in the
 *    blocks to accommodate padding (a minimum of 1 bytes for PKCS5 padding,
 *    for example). 
 *    DKS TODO -- deal with the padding and length expansion
 *    	For the moment, until we deal with padding we use only AES-CTR.</li>
 * </ul>
 *    
 * Overall this class attempts to minimize copying of data. Data must be copied into final
 * ContentObjects returned by the signing operations. On the way, it may need
 * to pass through a block encrypter, which may perform local copies. Higher-level
 * constructs, such as streams, may buffer it above.
 */
public class CCNSegmenter {
	// TODO: Provide functionality that let client stream
	// classes limit copies (e.g. by partially creating data-filled content objects,
	// and not signing them till flush()).

	public static final String PROP_BLOCK_SIZE = "ccn.lib.blocksize";
	public static final long LAST_SEGMENT = Long.valueOf(-1);

	protected int _blockSize = SegmentationProfile.DEFAULT_BLOCKSIZE;
	protected int _blockIncrement = SegmentationProfile.DEFAULT_INCREMENT;
	protected int _byteScale = SegmentationProfile.DEFAULT_SCALE;
	protected SegmentNumberType _sequenceType = SegmentNumberType.SEGMENT_FIXED_INCREMENT;

	protected CCNHandle _handle;

	/**
	 * Eventually may not contain this; callers may access it exogenously.
	 */
	protected CCNFlowControl _flowControl;

	/**
	 * Handle multi-block amortized signing. If null, default to single-block signing.
	 */
	protected CCNAggregatedSigner _bulkSigner;
	
	/**
	 * The first segment, useful for obtaining starting segment number and digest to characterize
	 * set of segmented content.
	 */
	protected ContentObject _firstSegment = null;

	/**
	 * Create a segmenter with default (Merkle hash tree) bulk signing
	 * behavior, making a new handle for it to use.
	 * @throws ConfigurationException if there is a problem creating the handle
	 * @throws IOException if there is a problem creating the handle
	 */
	public CCNSegmenter() throws ConfigurationException, IOException {
		this(CCNHandle.open());
	}

	/**
	 * Create a segmenter with default (Merkle hash tree) bulk signing
	 * behavior.
	 * @param handle the handle to use, will open a new one if null
	 * @throws IOException if there is a problem creating the handle
	 */
	public CCNSegmenter(CCNHandle handle) throws IOException {
		this(new CCNFlowControl(handle));
	}

	/**
	 * Create a segmenter with default (Merkle hash tree) bulk signing
	 * behavior.
	 * @param flowControl the specified flow controller to use
	 */
	public CCNSegmenter(CCNFlowControl flowControl) {
		this(flowControl, null);
	}

	/**
	 * Create a segmenter, specifying the signing behavior to use.
	 * @param flowControl the specified flow controller to use
	 * @param signer the bulk signer to use. If null, will use default Merkle hash tree behavior.
	 */
	public CCNSegmenter(CCNFlowControl flowControl, CCNAggregatedSigner signer) {
		if ((null == flowControl) || (null == flowControl.getHandle())) {
			// Tries to get a library or make a flow control, yell if we fail.
			throw new IllegalArgumentException("CCNSegmenter: must provide a valid library or flow controller.");
		} 
		_flowControl = flowControl;
		_handle = _flowControl.getHandle();
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
				if( Log.isLoggable(Level.INFO))
					Log.info("Using specified fragmentation block size " + _blockSize);
			} catch (NumberFormatException e) {
				// Do nothing
				Log.warning("Error: malformed property value " + PROP_BLOCK_SIZE + ": " + blockString + " should be an integer.");
			}
		}
	}

	/**
	 * Factory method to create a standard segmenter that generates blocks of fixed length in bytes.
	 * @param blockSize number of bytes to put in each block (the last block will have an odd number of
	 * 	bytes)
	 * @param flowControl the flow controller to use
	 * @return the new segmenter
	 */
	public static CCNSegmenter getBlockSegmenter(int blockSize, CCNFlowControl flowControl) {
		CCNSegmenter segmenter = new CCNSegmenter(flowControl);
		segmenter.useFixedIncrementSequenceNumbers(1);
		segmenter.setBlockSize(blockSize);
		return segmenter;
	}

	/**
	 * Factory method to create a standard segmenter that generates blocks of variable length in bytes,
	 * whose segment numbers are scaled by a fixed increment. This could be used, for example to generate
	 * segments that had variable number of bytes in each, and naming them by scaling the byte
	 * offset by a scale useful to the application - e.g. to end up with millisecond offsets
	 * for a video or audio stream, etc.
	 * NOTE: the reader infrastructure currently expects incrementing segment numbers; a special
	 * stream class is necessary to read data generated this way. That would not be difficult to
	 * write.
	 * @param scale multiplier to apply to the byte count before recording it as a sequence number
	 * @param flowControl the flow controller to use
	 * @return the new segmenter
	 */
	public static CCNSegmenter getScaledByteCountSegmenter(int scale, CCNFlowControl flowControl) {
		CCNSegmenter segmenter = new CCNSegmenter(flowControl);
		segmenter.useScaledByteCountSequenceNumbers(scale);
		return segmenter;
	}

	/**
	 * Factory method to create a standard segmenter that generates blocks of variable length in bytes,
	 * whose segment numbers are scaled by a fixed increment. This could be used, for example to generate
	 * segments that had variable number of bytes in each, and naming them by byte offset.
	 * NOTE: This is used by CCNBlockInputStream and CCNBlockOutputStream; the other stream classes
	 *   will not read data generated this way.
	 * @param flowControl the flow controller to use
	 * @return the new segmenter
	 */
	public static CCNSegmenter getByteCountSegmenter(CCNFlowControl flowControl) {
		return getScaledByteCountSegmenter(1, flowControl);
	}

	public CCNHandle getLibrary() { return _handle; }

	public CCNFlowControl getFlowControl() { return _flowControl; }

	/**
	 * Return the first segment.
	 * @return The first segment or null if no segments generated yet
	 */
	public ContentObject getFirstSegment() {
		return _firstSegment;
	}
	
	/**
	 * Sets the segmentation block size to use
	 * @param blockSize block size in bytes
	 */
	public void setBlockSize(int blockSize) {
		_blockSize = blockSize;
	}

	/**
	 * Gets the current block size
	 * @return block size in bytes
	 */
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
	 * Sets the increment between block numbers.
	 * @param blockIncrement
	 */
	public void setBlockIncrement(int blockIncrement) { _blockIncrement = blockIncrement; }

	/**
	 * Gets the increment between block numbers.
	 * @return
	 */
	public int getBlockIncrement() { return _blockIncrement; }

	public void setByteScale(int byteScale) { _byteScale = byteScale; }
	public int getByteScale() { return _byteScale; }


	/**
	 * Puts a complete data item, segmenting it if necessary. The
	 * assumption of this method is that this single call puts
	 * all the blocks of the item; if multiple calls to the
	 * segmenter will be required to output an item, use other
	 * methods to manage segment identifiers.
	 * 
	 * If the data is small enough this doesn't fragment. Otherwise, does.
	 * If multi-fragment, uses the naming profile and specified
	 * bulk signer (default: Merkle Hash Tree) to generate names and signatures.
	 * @param keys the keys to use for encrypting this segment, or null if unencrypted. The
	 *   specific Key/IV used for this segment will be obtained by calling keys.getSegmentEncryptionCipher().
	 * @return ContentObject of the data that was put (in the case
	 * of fragmented data, the first fragment is returned). This way the
	 * caller can then easily link to the data if
	 * they need to, or put again with a different name.
	 * @throws InvalidAlgorithmParameterException 
	 **/
	public long put(
			ContentName name, byte [] content, int offset, int length,
			boolean lastSegments,
			SignedInfo.ContentType type,
			Integer freshnessSeconds, 
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher,
			ContentKeys keys) 
	throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException {

		if (null == content) {
			throw new IOException("Content cannot be null!");
		}
		
		if (null == publisher) {
			publisher = _handle.keyManager().getDefaultKeyID();
		}

		if (null == locator)
			locator = _handle.keyManager().getKeyLocator(publisher);

		if (null == type) {
			type = ContentType.DATA;
		}

		// Remove existing segmentation markers on end of name, at point right
		// before put. If do it sooner, have to re-do it just to be sure.
		if (SegmentationProfile.isSegment(name)) {
			Log.info("Asked to store fragments under fragment name: " + name + ". Stripping fragment information");
		}

		// DKS TODO -- take encryption overhead into account
		// DKS TODO -- hook up last segment
		if (outputLength(length, keys) >= getBlockSize()) {
			return fragmentedPut(name, content, offset, length, (lastSegments ? CCNSegmenter.LAST_SEGMENT : null),
					type, freshnessSeconds, locator, publisher, keys);
		} else {
			try {
				// We should only get here on a single-fragment object, where the lastBlocks
				// argument is false (omitted).
				return putFragment(name, SegmentationProfile.baseSegment(), 
						content, offset, length, type,
						null, freshnessSeconds, 
						Long.valueOf(SegmentationProfile.baseSegment()), 
						locator, publisher, keys);
			} catch (IOException e) {
				Log.warning("This should not happen: put failed with an IOException.");
				Log.warningStackTrace(e);
				throw e;
			}
		}
	}

	/** 
	 * Segments content, builds segment names and ContentObjects, signs
	 * them, and writes them to the flow controller to go out to the network.
	 * Low-level segmentation interface. Assume arguments have been cleaned
	 * prior to arrival -- name is not already segmented, type is set, etc.
	 * 
	 * Starts segmentation at segment SegmentationProfile().baseSegment().
	 * @param name name prefix to use for the segments
	 * @param content content buffer containing content to put
	 * @param offset offset into buffer at which to start reading content to put
	 * @param length number of bytes of buffer to put
	 * @param finalSegmentIndex the expected segment number of the last segment of this stream,
	 * 				null to omit, Long(-1) to set as the last segment of this put, whatever
	 * 				its number turns out to be
	 * @param type the type for the content
	 * @param freshnessSeconds the number of seconds this content should be considered fresh, or null
	 * 			to leave unset
	 * @param locator the key locator to use
	 * @param publisher the publisher to use
	 * @param keys the keys to use for encrypting this segment, or null if unencrypted. The
	 *   specific Key/IV used for this segment will be obtained by calling keys.getSegmentEncryptionCipher().
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
			PublisherPublicKeyDigest publisher,
			ContentKeys keys) 
	throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, 
	IOException, InvalidAlgorithmParameterException {

		return fragmentedPut(name, SegmentationProfile.baseSegment(),
				content, offset, length, getBlockSize(), type,
				null, freshnessSeconds, finalSegmentIndex, locator, publisher,
				keys);
	}

	/** 
	 * Segments content, builds segment names and ContentObjects, signs
	 * them, and writes them to the flow controller to go out to the network.
	 * @param name name prefix to use for the segments
	 * @param baseSegmentNumber the segment number to start this batch with
	 * @param content content buffer containing content to put
	 * @param offset offset into buffer at which to start reading content to put
	 * @param length number of bytes of buffer to put
	 * @param blockWidth the block size to use
	 * @param type the type for the content
	 * @param timestamp the timestamp for the content
	 * @param freshnessSeconds the number of seconds this content should be considered fresh, or null
	 * 			to leave unset
	 * @param finalSegmentIndex the expected segment number of the last segment of this stream,
	 * 				null to omit, Long(-1) to set as the last segment of this put, whatever
	 * 				its number turns out to be
	 * @param locator the key locator to use
	 * @param publisher the publisher to use
	 * @param keys the keys to use for encrypting this segment, or null if unencrypted. The
	 *   specific Key/IV used for this segment will be obtained by calling keys.getSegmentEncryptionCipher().
	 * @return returns the segment identifier for the next segment to be written, if any.
	 * 		If the caller doesn't want to override this, they can hand this number back
	 * 	    to a subsequent call to fragmentedPut.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException 
	 * @throws InvalidAlgorithmParameterException 
	 * @see fragmentedPut(ContentName, byte[], int, int, Long, ContentType, Integer, KeyLocator, PublisherPublicKeyDigest)
	 * Starts segmentation at segment SegmentationProfile().baseSegment().
	 */
	public long fragmentedPut(
			ContentName name, long baseSegmentNumber,
			byte [] content, int offset, int length, int blockWidth,
			ContentType type, 
			CCNTime timestamp,
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher,
			ContentKeys keys) throws InvalidKeyException, 
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
			publisher = getFlowControl().getHandle().keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = getFlowControl().getHandle().keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = getFlowControl().getHandle().keyManager().getKeyLocator(publisher);

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		// DKS -- someone should have done this for us already, can we remove it?
		if( Log.isLoggable(Level.INFO))
			Log.info("Adding namespace in segmenter, probably redundantly. Namespace: {0}", rootName);
		getFlowControl().addNameSpace(rootName);

		if (null == type) {
			type = ContentType.DATA;
		}

		byte [] finalBlockID = null;
		if (null != finalSegmentIndex) {
			if (finalSegmentIndex.longValue() == CCNSegmenter.LAST_SEGMENT) {
				// compute final segment number
				// compute final segment number; which might be this one if blockCount == 1
				int blockCount = CCNMerkleTree.blockCount(length, blockWidth);
				finalBlockID = SegmentationProfile.getSegmentNumberNameComponent(
						lastSegmentIndex(baseSegmentNumber, (blockCount-1)*blockWidth, 
								blockCount));
			} else {
				finalBlockID = SegmentationProfile.getSegmentNumberNameComponent(finalSegmentIndex);
			}
		}

		ContentObject [] contentObjects = 
			buildBlocks(rootName, baseSegmentNumber, 
					new SignedInfo(publisher, timestamp, type, locator, freshnessSeconds, finalBlockID),
					content, offset, length, blockWidth, keys);

		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// and signedInfos in the MerkleTree blocks. 
		// For now, this generates the root signature too, so can
		// ask for the signature for each block.
		_bulkSigner.signBlocks(contentObjects, signingKey);
		if (null == _firstSegment) {
			_firstSegment = contentObjects[0];
		}
		getFlowControl().put(contentObjects);

		return nextSegmentIndex(
				SegmentationProfile.getSegmentNumber(contentObjects[contentObjects.length - 1].name()), 
				contentObjects[contentObjects.length - 1].contentLength());
	}

	/** 
	 * Takes pre-segmented content, builds segment names and ContentObjects, signs
	 * them, and writes them to the flow controller to go out to the network.
	 * @param name name prefix to use for the segments
	 * @param baseSegmentNumber the segment number to start this batch with
	 * @param contentBlocks content buffers containing content to put, one buffer per ContentObject
	 * @param blockCount the number of these content buffers to write
	 * @param firstBlockIndex the index into the content buffer array to start writing blocks
	 * @param lastBlockLength the number of bytes of the last block to be written to use -- this
	 * 	allows a fixed set of byte [] to be used to buffer content for segmentation, and still cope
	 * 	with variable-length last blocks
	 * @param type the type for the content
	 * @param timestamp the timestamp for the content
	 * @param freshnessSeconds the number of seconds this content should be considered fresh, or null
	 * 			to leave unset
	 * @param finalSegmentIndex the expected segment number of the last segment of this stream,
	 * 				null to omit, Long(-1) to set as the last segment of this put, whatever
	 * 				its number turns out to be
	 * @param locator the key locator to use
	 * @param publisher the publisher to use
	 * @param keys the keys to use for encrypting this segment, or null if unencrypted. The
	 *   specific Key/IV used for this segment will be obtained by calling keys.getSegmentEncryptionCipher().
	 * @return returns the segment identifier for the next segment to be written, if any.
	 * 		If the caller doesn't want to override this, they can hand this number back
	 * 	    to a subsequent call to fragmentedPut.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException 
	 * @throws InvalidAlgorithmParameterException 
	 * @see fragmentedPut(ContentName, byte[], int, int, Long, ContentType, Integer, KeyLocator, PublisherPublicKeyDigest)
	 * Starts segmentation at segment SegmentationProfile().baseSegment().
	 */
	public long fragmentedPut(
			ContentName name, long baseSegmentNumber,
			byte contentBlocks[][], int blockCount, 
			int firstBlockIndex, int lastBlockLength,
			ContentType type, 
			CCNTime timestamp,
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher, 
			ContentKeys keys) throws InvalidKeyException, SignatureException, 
			NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException {

		if (blockCount == 0)
			return baseSegmentNumber;

		if (null == publisher) {
			publisher = getFlowControl().getHandle().keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = getFlowControl().getHandle().keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = getFlowControl().getHandle().keyManager().getKeyLocator(publisher);

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		getFlowControl().addNameSpace(rootName);

		if (null == type) {
			type = ContentType.DATA;
		}

		byte [] finalBlockID = null;
		if (null != finalSegmentIndex) {
			if (finalSegmentIndex.longValue() == CCNSegmenter.LAST_SEGMENT) {
				long length = 0;
				for (int j = firstBlockIndex; j < firstBlockIndex + blockCount - 1; j++) {
					length += contentBlocks[j].length;
				}
				// don't include last block length; want intervening byte count before the last block

				// compute final segment number
				finalBlockID = SegmentationProfile.getSegmentNumberNameComponent(
						lastSegmentIndex(baseSegmentNumber, length, blockCount));
			} else {
				finalBlockID = SegmentationProfile.getSegmentNumberNameComponent(finalSegmentIndex);
			}
		}

		ContentObject [] contentObjects = 
			buildBlocks(rootName, baseSegmentNumber, 
					new SignedInfo(publisher, timestamp, type, locator, freshnessSeconds, finalBlockID),
					contentBlocks, false, blockCount, firstBlockIndex, lastBlockLength, keys);

		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// and signedInfos in the MerkleTree blocks. 
		// For now, this generates the root signature too, so can
		// ask for the signature for each block.
		_bulkSigner.signBlocks(contentObjects, signingKey);
		if (null == _firstSegment) {
			_firstSegment = contentObjects[0];
		}
		getFlowControl().put(contentObjects);

		return nextSegmentIndex(
				SegmentationProfile.getSegmentNumber(contentObjects[firstBlockIndex + blockCount - 1].name()), 
				contentObjects[firstBlockIndex + blockCount - 1].contentLength());
	}

	/**
	 * Puts a single block of content of arbitrary length using a segment naming convention.
	 * @param name name prefix to use for the object, without the segment number
	 * @param segmentNumber the segment number to use for this object
	 * @param content content buffer containing content to put
	 * @param offset offset into buffer at which to start reading content to put
	 * @param length number of bytes of buffer to put
	 * @param type the type for the content
	 * @param timestamp the timestamp for the content
	 * @param freshnessSeconds the number of seconds this content should be considered fresh, or null
	 * 			to leave unset
	 * @param finalSegmentIndex the expected segment number of the last segment of this stream,
	 * 				null to omit, Long(-1) to set as the last segment of this put, whatever
	 * 				its number turns out to be
	 * @param locator the key locator to use
	 * @param publisher the publisher to use
	 * @param keys the keys to use for encrypting this segment, or null if unencrypted. The
	 *   specific Key/IV used for this segment will be obtained by calling keys.getSegmentEncryptionCipher().
	 * @return returns the segment identifier for the next segment to be written, if any.
	 * 		If the caller doesn't want to override this, they can hand this number back
	 * 	    to a subsequent call to fragmentedPut.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException 
	 * @throws InvalidAlgorithmParameterException 
	 */
	public long putFragment(
			ContentName name, long segmentNumber, 
			byte [] content, int offset, int length,
			ContentType type, 
			CCNTime timestamp, 
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher,
			ContentKeys keys) throws InvalidKeyException, SignatureException, 
			NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException {

		if (null == publisher) {
			publisher = _handle.keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = _handle.keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = _handle.keyManager().getKeyLocator(publisher);

		if (null == type) {
			type = ContentType.DATA;
		}

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		_flowControl.addNameSpace(rootName);

		byte [] finalBlockID = ((null == finalSegmentIndex) ? null : 
			((finalSegmentIndex.longValue() == LAST_SEGMENT) ? 
					SegmentationProfile.getSegmentNumberNameComponent(segmentNumber) : 
						SegmentationProfile.getSegmentNumberNameComponent(finalSegmentIndex)));

		if (null != keys) {
			try {
				// Make a separate cipher, so this segmenter can be used by multiple callers at once.
				Cipher thisCipher = keys.getSegmentEncryptionCipher(rootName, publisher, segmentNumber);
				content = thisCipher.doFinal(content, offset, length);
				offset = 0;
				length = content.length;
				// Override content type to mark encryption.
				// Note: we don't require that writers use our facilities for encryption, so
				// content previously encrypted may not be marked as type ENCR. So on the decryption
				// side we don't require that encrypted data be marked ENCR -- if you give us a
				// decryption key, we'll try to decrypt it.
				type = ContentType.ENCR; 

			} catch (IllegalArgumentException e) {
				Log.warning("Exception: " + e);
				Log.warning("Exception: offset " + offset + " length " + length + " content length " +
						((null == content) ? "null" : content.length));
				Log.warningStackTrace(e);
				throw e;
			} catch (IllegalBlockSizeException e) {
				Log.warning("Unexpected IllegalBlockSizeException for an algorithm we have already used!");
				throw new InvalidKeyException("Unexpected IllegalBlockSizeException for an algorithm we have already used!", e);
			} catch (BadPaddingException e) {
				Log.warning("Unexpected BadPaddingException for an algorithm we have already used!");
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
		if (null == _firstSegment) {
			_firstSegment = co;
		}
		if( Log.isLoggable(Level.FINER))
			Log.finer("CCNSegmenter: putting " + co.name() + " (timestamp: " + co.signedInfo().getTimestamp() + ", length: " + length + ")");
		_flowControl.put(co);

		return nextSegmentIndex(segmentNumber, co.contentLength());
	}

	/**
	 * Helper method to build ContentObjects for segments out of a contiguous buffer.
	 * @param rootName
	 * @param baseSegmentNumber
	 * @param signedInfo
	 * @param content
	 * @param offset
	 * @param length
	 * @param blockWidth
	 * @param keys the keys to use for encrypting this segment, or null if unencrypted. The
	 *   specific Key/IV used for this segment will be obtained by calling keys.getSegmentEncryptionCipher().
	 * @return
	 * @throws InvalidKeyException
	 * @throws InvalidAlgorithmParameterException
	 * @throws IOException
	 */
	protected ContentObject[] buildBlocks(ContentName rootName,
			long baseSegmentNumber, SignedInfo signedInfo, 
			byte[] content, int offset, int length, int blockWidth,
			ContentKeys keys) 
	throws InvalidKeyException, InvalidAlgorithmParameterException, IOException {

		int blockCount = CCNMerkleTree.blockCount(length, blockWidth);
		ContentObject [] blocks = new ContentObject[blockCount];

		long nextSegmentIndex = baseSegmentNumber;

		for (int i=0; i < blockCount; ++i) {
			InputStream dataStream = new ByteArrayInputStream(content, offset, length);
			if (null != keys) {
				// DKS TODO -- move to streaming version to cut down copies. Here using input
				// streams, eventually push down with this at the end of an output stream.

				// Make a separate cipher, so this segmenter can be used by multiple callers at once.
				Cipher thisCipher = keys.getSegmentEncryptionCipher(rootName, signedInfo.getPublisherKeyID(), nextSegmentIndex);
				if (Log.isLoggable(Level.FINEST))
					Log.finest("Created new encryption cipher "+thisCipher);
				// Override content type to mark encryption.
				// Note: we don't require that writers use our facilities for encryption, so
				// content previously encrypted may not be marked as type ENCR. So on the decryption
				// side we don't require that encrypted data be marked ENCR -- if you give us a
				// decryption key, we'll try to decrypt it.
				signedInfo.setType(ContentType.ENCR);

				dataStream = new UnbufferedCipherInputStream(dataStream, thisCipher);
			}
			blocks[i] =  
				new ContentObject(
						SegmentationProfile.segmentName(rootName, nextSegmentIndex),
						signedInfo,
						dataStream, blockWidth);
			nextSegmentIndex = nextSegmentIndex(nextSegmentIndex, 
					blocks[i].contentLength());
			offset += blockWidth;
			length -= blockWidth;
		}
		return blocks;
	}

	/**
	 * Helper method to construct ContentObjects from pre-segmented blocks.
	 * @param rootName
	 * @param baseSegmentNumber
	 * @param signedInfo
	 * @param contentBlocks
	 * @param isDigest
	 * @param blockCount
	 * @param firstBlockIndex
	 * @param lastBlockLength
	 * @param keys the keys to use for encrypting this segment, or null if unencrypted. The
	 *   specific Key/IV used for this segment will be obtained by calling keys.getSegmentEncryptionCipher().
	 * @return
	 * @throws InvalidKeyException
	 * @throws InvalidAlgorithmParameterException
	 * @throws ContentEncodingException 
	 */
	protected ContentObject [] buildBlocks(ContentName rootName,
			long baseSegmentNumber, SignedInfo signedInfo,
			byte contentBlocks[][], boolean isDigest, int blockCount,
			int firstBlockIndex, int lastBlockLength,
			ContentKeys keys) 
	throws InvalidKeyException, InvalidAlgorithmParameterException, ContentEncodingException {

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
			if (null != keys) {
				try {
					// Make a separate cipher, so this segmenter can be used by multiple callers at once.
					Cipher thisCipher = keys.getSegmentEncryptionCipher(rootName, signedInfo.getPublisherKeyID(), nextSegmentIndex);
					// TODO -- incurs an extra copy
					blockContent = thisCipher.doFinal(contentBlocks[i]);

					// Override content type to mark encryption.
					// Note: we don't require that writers use our facilities for encryption, so
					// content previously encrypted may not be marked as type ENCR. So on the decryption
					// side we don't require that encrypted data be marked ENCR -- if you give us a
					// decryption key, we'll try to decrypt it.
					signedInfo.setType(ContentType.ENCR);

				} catch (IllegalBlockSizeException e) {
					Log.warning("Unexpected IllegalBlockSizeException for an algorithm we have already used!");
					throw new InvalidKeyException("Unexpected IllegalBlockSizeException for an algorithm we have already used!", e);
				} catch (BadPaddingException e) {
					Log.warning("Unexpected BadPaddingException for an algorithm we have already used!");
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
		if (null != keys) {
			try {
				Cipher thisCipher = keys.getSegmentEncryptionCipher(rootName, signedInfo.getPublisherKeyID(), nextSegmentIndex);
				blockContent = thisCipher.doFinal(contentBlocks[i], 0, lastBlockLength);
				lastBlockLength = blockContent.length;

			} catch (IllegalBlockSizeException e) {
				Log.warning("Unexpected IllegalBlockSizeException for an algorithm we have already used!");
				throw new InvalidKeyException("Unexpected IllegalBlockSizeException for an algorithm we have already used!", e);
			} catch (BadPaddingException e) {
				Log.warning("Unexpected BadPaddingException for an algorithm we have already used!");
				throw new InvalidAlgorithmParameterException("Unexpected BadPaddingException for an algorithm we have already used!", e);
			}
		}
		// ContentObject constructor copies the content blocks, so that the buffer here
		// can be reused by caller.
		blocks[i] =  
			new ContentObject(
					SegmentationProfile.segmentName(rootName, nextSegmentIndex),
					signedInfo,
					blockContent, 0, lastBlockLength,
					(Signature)null);
		return blocks;
	}


	/**
	 * Increment segment number according to the numbering profile in force.
	 * @param lastSegmentNumber the last segment number we emitted
	 * @param lastSegmentLength the length of the last segment we emitted
	 * @return
	 */
	public long nextSegmentIndex(long lastSegmentNumber, long lastSegmentLength) {
		if (SegmentNumberType.SEGMENT_FIXED_INCREMENT == _sequenceType) {
			return lastSegmentNumber + getBlockIncrement();
		} else if (SegmentNumberType.SEGMENT_BYTE_COUNT == _sequenceType) {
			return lastSegmentNumber + (getByteScale() * lastSegmentLength);
		} else {
			Log.warning("Unknown segmentation type: " + _sequenceType);
			return lastSegmentNumber + 1;
		}
	}

	/**
	 * Compute the index of the last block of a set of segments, according to the
	 * numbering profile.
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
			Log.warning("Unknown segmentation type: " + _sequenceType);
			return currentSegmentNumber + (blocksRemaining - 1);
		}
	}

	/**
	 * Set the timeout on the contained flow controller.
	 * @param timeout
	 */
	public void setTimeout(int timeout) {
		getFlowControl().setTimeout(timeout);
	}

	/**
	 * How many content bytes will it take to represent content of length
	 * length, including any padding incurred by encryption?
	 * @param inputLength
	 * @return the output length
	 */
	public long outputLength(int inputLength, ContentKeys keys) {
		if (null == keys) {
			return inputLength;
		} else {
			return keys.getCipher().getOutputSize(inputLength);
		}
	}
}
