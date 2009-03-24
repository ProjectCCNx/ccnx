package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import org.junit.Assert;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.SegmentationProfile.SegmentNumberType;

/**
 * This class acts as a packet-oriented stream of data. It might be
 * better implemented as a subclass of DatagramSocket. Given a base name
 * and signing information, it writes content blocks under that base name,
 * where each block is named according to the base name concatenated with a
 * sequence number identifying the specific block. 
 * 
 * Each call to write writes an individual CCN data item. There is no buffering,
 * content is immediately flushed to the network. Each block is individually
 * signed using the specified algorithm and key.
 * 
 * For now, it is very low-level and makes no assumptions about the structure
 * of the name prefix it uses. It assumes the caller has sorted out issues
 * about versioning and fragmentation markers, if any are necessary. On
 * the C library side, it mimics the behavior of ccnsendchunks.
 * 
 * It does offer flexible content name increment options. The creator
 * can specify an initial block id (default is 0), and an increment (default 1)
 * for fixed-width blocks, or blocks can be identified by byte offset
 * in the running stream, or by another integer metric (e.g. time offset),
 * by supplying a multiplier to conver the byte offset into a metric value.
 * Finally, writers can specify the block identifier with a write.
 * @author smetters
 *
 */
public class CCNBlockOutputStream extends CCNAbstractOutputStream {

	protected SegmentNumberType _sequenceType = SegmentNumberType.SEGMENT_FIXED_INCREMENT;
	protected int _blockWidth = SegmentationProfile.DEFAULT_INCREMENT; // increment for fixed-width block naming
	protected int _blockScale = SegmentationProfile.DEFAULT_SCALE;
	protected int _bytesWritten = 0; // byte count for offset
	protected SignedInfo.ContentType _type;
	
	/**
	 * Default, fixed increment, sequential-numbered blocks (unless overridden on write).
	 * @param name
	 * @param publisher
	 * @param locator
	 * @param signingKey
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNBlockOutputStream(ContentName baseName, SignedInfo.ContentType type,
								KeyLocator locator, PublisherKeyID publisher,
								CCNFlowControl flowControl) throws XMLStreamException, IOException {
		// DKS TODO -- this stream defines a certain set of segmenter behaviors, set them up
		super(locator, publisher, new CCNSegmenter(flowControl));
		
		_type = type;

		ContentName nameToOpen = baseName;
		
		// If someone gave us a fragment name, at least strip that.
		if (SegmentationProfile.isSegment(nameToOpen)) {
			// DKS TODO: should we do this?
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		}

		// Don't go looking for or adding versions. Might be unversioned,
		// unfragmented content (e.g. RTP streams). Assume caller knows
		// what name they want.
		_baseName = nameToOpen;
	}
	
	public CCNBlockOutputStream(ContentName baseName, SignedInfo.ContentType type) throws XMLStreamException, IOException {
		this(baseName, type, null, null, null);
	}
		
	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		byte [] tempBuf = new byte[len];
		System.arraycopy(b,off,tempBuf,0,len);
		write(tempBuf, null);
	}

	/**
	 * Force the value of the block index. This resets future counter-based
	 * indices, if they are used.
	 */
	public void write(byte[] b, Integer blockIndex) throws IOException {
		try {
			// DKS TODO -- change from string sequence numbers to binary.
			if (blockIndex != null) {
				_blockIndex = blockIndex; // reset block index
			} else {
				// compute automatically
				updateBlockIndex();
			}
			Assert.fail("Need to fix block numbering!");
			ContentName blockName = ContentName.fromNative(_baseName, Integer.toString(_blockIndex));
			_segmenter.put(blockName, b, _type, _locator, _publisher);
			_bytesWritten += b.length;
		} catch (InvalidKeyException e) {
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		} 
	}
	
	protected void updateBlockIndex() {
		switch(_sequenceType) {
			case SEGMENT_FIXED_INCREMENT:
				_blockIndex += _blockWidth;
				break;
			case SEGMENT_BYTE_COUNT:
				_blockIndex = _bytesWritten * _blockScale; // if not scaling, blockScale set to 1
				break;
		}
	}

}
