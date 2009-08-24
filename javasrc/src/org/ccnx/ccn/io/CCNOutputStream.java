package org.ccnx.ccn.io;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.Arrays;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNSegmenter;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * This particular output stream class embodies the following assumptions:
 * - content is buffered
 * - content is automatically fragmented, using the standard library fragmentation
 *    mechanisms, independently of the block size in which it is written
 * - content is written with an associated header
 * - content is authenticated using a bulk signer (e.g. a MHT); each time flush() is called,
 *    available buffered data is written in a new MHT. The number of blocks in each
 *    MHT is a maximum of BLOCK_BUF_COUNT (TODO: calculate overhead), and a minimum of
 *    the number of blocks with data when flush() is called.
 *    
 * Version 2: now that the bulk signer interface supports contiguous buffers, and
 * the segmenter will segment, remove the overhead here of having blocked buffers.
 * (This is preparation for removing the buffers entirely and streaming directly
 * through the segmenter.)
 * 
 * Also keep track of what we've flushed; right now if we call close multiple times,
 * we write the last partial block multiple times.
 *    
 * @author smetters
 *
 */
public class CCNOutputStream extends CCNAbstractOutputStream {

	/**
	 * Amount of data we keep around prior to forced flush, in terms of segmenter
	 * blocks. We write to a limit lower than the maximum, to allow for expansion
	 * due to encryption.
	 * TODO calculate this dynamically based on the bulk signing method and overhead thereof
	 */
	protected static final int BLOCK_BUF_COUNT = 128;

	protected long _totalLength = 0; // elapsed length written
	protected int _blockOffset = 0; // write pointer - offset into the write buffer at which to write
	protected byte [] _buffer = null;
	protected long _baseNameIndex; // base name index of the current set of data to output;
								  // incremented according to the segmentation profile.
	protected Timestamp _timestamp; // timestamp we use for writing, set to first time we write
	protected ContentType _type; // null == DATA

	protected CCNDigestHelper _dh;

	public CCNOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, ContentType type,
			ContentKeys keys, CCNHandle library) throws IOException {
		this(name, locator, publisher, type, new CCNSegmenter(new CCNFlowControl(name, library),
				null, keys));
	}

	public CCNOutputStream(ContentName name,
			PublisherPublicKeyDigest publisher, ContentType type,
			CCNHandle library) throws IOException {
		this(name, null, publisher, type, new CCNSegmenter(new CCNFlowControl(name, library)));
	}

	public CCNOutputStream(ContentName name,
			PublisherPublicKeyDigest publisher,
			CCNHandle library) throws IOException {
		this(name, null, publisher, null, new CCNSegmenter(new CCNFlowControl(name, library)));
	}

	public CCNOutputStream(ContentName name, CCNHandle library) throws IOException {
		this(name, null, null, library);
	}

	public CCNOutputStream(ContentName name, ContentType type, CCNHandle library) throws IOException {
		this(name, null, type, library);
	}
	
	public CCNOutputStream() {}	// special purpose constructor

	protected CCNOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, ContentType type,
			CCNSegmenter segmenter) throws IOException {

		super(locator, publisher, segmenter);

		ContentName nameToOpen = name;
		_segmenter.getFlowControl().startWrite(name, Shape.STREAM_WITH_HEADER);
		if (SegmentationProfile.isSegment(nameToOpen)) {
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
			// DKS -- should we offset output index to next one? might have closed
			// previous stream, so likely not
		}

		// Should have name of root of version we want to open. 
		_baseName = nameToOpen;
		_buffer = new byte[BLOCK_BUF_COUNT * segmenter.getBlockSize()];
		_baseNameIndex = SegmentationProfile.baseSegment();
		_type = type; // null = DATA

		_dh = new CCNDigestHelper();
		startWrite(); // set up flow controller to write
	}

	protected CCNOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher,
			CCNFlowControl flowControl) throws IOException {
		this(name, locator, publisher, null, new CCNSegmenter(flowControl));
	}

	@Override
	protected void startWrite() throws IOException {
		_segmenter.getFlowControl().startWrite(_baseName, Shape.STREAM);
	}
	
	/**
	 * Set the fragmentation block size to use. Constraints: needs to be
	 * a multiple of the likely encryption block size (which is, conservatively, 32 bytes).
	 * @param blockSize
	 */
	public void setBlockSize(int blockSize) {
		if (blockSize <= 0) {
			throw new IllegalArgumentException("Cannot set negative or zero block size!");
		}
		// We have an existing buffer. That might contain existing data. Changing the
		// buffer size here to get the right number of blocks might require a forced flush
		// or all sorts of complicated hijinks. For now, just stick with the same buffer;
		// if we manage to go buffer-free, this won't matter.
		getSegmenter().setBlockSize(blockSize);
	}

	public int getBlockSize() {
		return getSegmenter().getBlockSize();
	}

	@Override
	public void close() throws IOException {
		try {
			_segmenter.getFlowControl().beforeClose();
			closeNetworkData();
			_segmenter.getFlowControl().afterClose();
		} catch (InvalidKeyException e) {
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		} catch (InterruptedException e) {
			throw new IOException("Low-level network failure!: " + e.getMessage());
		}
	}

	@Override
	public void flush() throws IOException {
		flush(false); // if there is a partial block, don't flush it
	}

	protected void flush(boolean flushLastBlock) throws IOException {
		try {
			flushToNetwork(flushLastBlock);
		} catch (InvalidKeyException e) {
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		} catch (InterruptedException e) {
			throw new IOException("Low-level network failure!: " + e.getMessage());
		} catch (InvalidAlgorithmParameterException e) {
			throw new IOException("Cannot encrypt content -- bad algorithm parameter!: " + e.getMessage());
		} 
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		try {
			writeToNetwork(b, off, len);
		} catch (InvalidKeyException e) {
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		} catch (InterruptedException e) {
			throw new IOException("Low-level network failure!: " + e.getMessage());
		}
	}

	protected int writeToNetwork(byte[] buf, long offset, long len) throws IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException {
		if ((len <= 0) || (null == buf) || (buf.length == 0) || (offset >= buf.length))
			throw new IllegalArgumentException("Invalid argument!");

		long bytesToWrite = len;

		// Here's an advantage of the old, complicated way -- with that, only had to allocate
		// as many blocks as you were going to write. 
		while (bytesToWrite > 0) {

			long thisBufAvail = _buffer.length - _blockOffset;
			long toWriteNow = (thisBufAvail > bytesToWrite) ? bytesToWrite : thisBufAvail;

			System.arraycopy(buf, (int)offset, _buffer, (int)_blockOffset, (int)toWriteNow);
			_dh.update(buf, (int) offset, (int)toWriteNow); // add to running digest of data

			bytesToWrite -= toWriteNow; // amount of data left to write in current call
			_blockOffset += toWriteNow; // write offset into current block buffer
			offset += toWriteNow; // read offset into input buffer
			_totalLength += toWriteNow; // increment here so we can write log entries on partial writes
			Library.logger().finest("write: added " + toWriteNow + " bytes to buffer. blockOffset: " + _blockOffset + "( " + (thisBufAvail - toWriteNow) + " left in block), " + _totalLength + " written.");

			if (_blockOffset >= _buffer.length) {
				// We're out of buffers. Time to flush to the network.
				Library.logger().info("write: about to sync one tree's worth of blocks (" + BLOCK_BUF_COUNT +") to the network.");
				flush(); // will reset _blockIndex and _blockOffset
			}
		}
		return 0;
	}

	protected void closeNetworkData() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException {
		// flush last partial buffers. Remove previous code to specially handle
		// small data objects (written as single blocks without headers); instead
		// write them as single-fragment files. Subclasses will determine whether or not
		// to write a header.
		Library.logger().info("closeNetworkData: final flush, wrote " + _totalLength + " bytes.");
		flush(true); // true means write out the partial last block, if there is one
	}

	/** 
	 * @param flushLastBlock Do we flush a partially-filled last block in the current set
	 *   of blocks? Not normally, we want to fill blocks. So if a user calls a manual
	 *   flush(), we want to flush all full blocks, but not a last partial -- readers of
	 *   this block-fragmented content (other streams make other sorts of fragments, this one
	 *   is designed for files of same block size) expect all fragments but the last to be
	 *   the same size. The only time we flush a last partial is on close(), when it is the
	 *   last block of the data. This flag, passed in only by internal methods, tells us it's
	 *   time to do that.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InterruptedException
	 * @throws IOException
	 * @throws InvalidAlgorithmParameterException 
	 */
	protected void flushToNetwork(boolean flushLastBlock) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException, InvalidAlgorithmParameterException {		

		/**
		 * XXX - Can the blockbuffers have holes?
		 *     DKS: no. The blockCount argument to putMerkleTree is intended to tell
		 *     it how many of the blockBuffers array it should touch (are non-null).
		 *     If there are holes, there are a bigger problem.
		 */

		/**
		 * Partial last block handling. If we are in the middle of writing a file, we only
		 * flush complete blocks; up to _blockOffset % getBlockSize().
		 */
		if (0 == _blockOffset) {
			// nothing to write
			return;
		} else if ((_blockOffset <= getBlockSize()) && (!flushLastBlock)) {
			// Only a single block written. We don't put out partial blocks until
			// close is called (or otherwise have flushLastBlock=true), so it's
			// easy to understand holding in that case. However, if we want to 
			// set finalBlockID, we can't do that till we know it -- till close is
			// called. So we should hold off on writing the last full block as well,
			// until we are closed. Unfortunately that means if you call flush()
			// right before close(), you'll tend to sign all but the last block,
			// and sign that last one separately when you actually flush it.
			return;
		}

		if (null == _timestamp)
			_timestamp = SignedInfo.now();

		// First, are we flushing dangling blocks (e.g. on close())? If not, we always
		// keep at least a partial block behind. There are two reasons for this; first to
		// ensure we always write full blocks until the end, and second, to allow us to
		// mark the last block as such. So adjust the number of blocks to write
		// accordingly. 
		boolean preservePartial = false;
		int saveBytes = 0;
		
		// Now, we have a partially or completely full buffer. Do we have a partial last block we want to preserve?
		// If we're not flushing, we want to save a final block (whole or partial) and move
		// it down.
		if (!flushLastBlock) {
			saveBytes = _blockOffset % getBlockSize();
			if (0 == saveBytes) {
				saveBytes = getBlockSize(); // full last block, save it anyway so can mark as last.
			}
			preservePartial = true;
		} // otherwise saveBytes = 0, so ok
		
		// Three cases -- 
		// 1) we have nothing to flush (0 bytes or < a single block) (handled above)
		// 2) we're flushing a single block and can put it out with a straight signature
		// 3) we're flushing more than one block, and need to use a bulk signer.
		// The reading/verification code will
		// cope just fine with a single file written in a mix of bulk and straight signature
		// verified blocks.
		if ((_blockOffset - saveBytes) <= getBlockSize()) {
			// Single block to write. If we get here, we are forcing a flush (see above
			// discussion about holding back partial or even a single full block till
			// forced flush/close in order to set finalBlockID).

			Library.logger().info("flush: asked to put a single block to the network, are we finishing the file? " + flushLastBlock + ".");

			// DKS TODO -- think about types, freshness, fix markers for impending last block/first block
			if ((_blockOffset - saveBytes) < getBlockSize()) {
				Library.logger().warning("flush(): writing hanging partial last block of file: " + (_blockOffset-saveBytes) + " bytes, block total is " + getBlockSize() + ", holding back " + saveBytes + " bytes, called by close? " + flushLastBlock);
			} else {
				Library.logger().warning("flush(): writing single full block of file: " + _baseName + ", holding back " + saveBytes + " bytes.");
			}
			_baseNameIndex = 
				_segmenter.putFragment(_baseName, _baseNameIndex, 
					_buffer, 0, (_blockOffset-saveBytes), 
					_type, _timestamp, null, (flushLastBlock ? _baseNameIndex : null), 
					_locator, _publisher);
		} else {
			Library.logger().info("flush: putting merkle tree to the network, baseName " + _baseName +
					" basenameindex " + ContentName.componentPrintURI(SegmentationProfile.getSegmentID(_baseNameIndex)) + "; " 
					+ _blockOffset + 
					" bytes written, holding back " + saveBytes + " flushing final blocks? " + flushLastBlock + ".");
			// Generate Merkle tree (or other auth structure) and signedInfos and put contents.
			// We always flush all the blocks starting from 0, so the baseBlockIndex is always 0.
			// DKS TODO fix last block marking
			_baseNameIndex = 
				_segmenter.fragmentedPut(_baseName, _baseNameIndex, _buffer, 0, _blockOffset-saveBytes, getBlockSize(),
									     _type, _timestamp, null, 
									     (flushLastBlock ? CCNSegmenter.LAST_SEGMENT : null), 
									     _locator, _publisher);
		}

		if (preservePartial) {
			System.arraycopy(_buffer, _blockOffset-saveBytes, _buffer, 0, saveBytes);
			_blockOffset = saveBytes;
		} else {
			_blockOffset = 0;
		}
		// zeroise unused bytes
		Arrays.fill(_buffer, _blockOffset, _buffer.length, (byte)0);
	}
	
	protected long lengthWritten() { 
		return _totalLength;
	}
}
