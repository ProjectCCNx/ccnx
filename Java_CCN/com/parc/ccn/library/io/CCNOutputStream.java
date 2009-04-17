package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.security.crypto.CCNDigestHelper;

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
 * @author smetters
 *
 */
public class CCNOutputStream extends CCNAbstractOutputStream {

	/**
	 * Maximum number of blocks we keep around before we build a
	 * Merkle tree and flush. Should be based on lengths of merkle
	 * paths and signatures and so on.
	 */
	protected static final int BLOCK_BUF_COUNT = 128;

	protected int _totalLength = 0;
	protected int _blockOffset = 0; // offset into current block
	protected byte [][] _blockBuffers = null;
	protected int _baseNameIndex; // base name index of current set of block buffers.
	protected int _blockSize = SegmentationProfile.DEFAULT_BLOCKSIZE;

	protected Timestamp _timestamp; // timestamp we use for writing, set to first time we write
	protected ContentType _type; // null == DATA

	protected CCNDigestHelper _dh;

	public CCNOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws XMLStreamException, IOException {
		this(name, locator, publisher, null, new CCNSegmenter(new CCNFlowControl(name, library)));
	}
	
	public CCNOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, ContentType type,
			CCNLibrary library) throws XMLStreamException, IOException {
		this(name, locator, publisher, type, new CCNSegmenter(new CCNFlowControl(name, library)));
	}

	public CCNOutputStream(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, null, library);
	}

	public CCNOutputStream(ContentName name, ContentType type, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, null, type, library);
	}

	protected CCNOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, ContentType type,
			CCNSegmenter segmenter) throws XMLStreamException, IOException {

		super(locator, publisher, segmenter);

		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(nameToOpen)) {
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
			// DKS -- should we offset output index to next one? might have closed
			// previous stream, so likely not
		}

		// Should have name of root of version we want to open. 
		_baseName = nameToOpen;
		_blockBuffers = new byte[BLOCK_BUF_COUNT][];
		_baseNameIndex = SegmentationProfile.baseSegment();
		_type = type; // null = DATA

		_dh = new CCNDigestHelper();
	}

	protected CCNOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher,
			CCNFlowControl flowControl) throws XMLStreamException, IOException {
		this(name, locator, publisher, null, new CCNSegmenter(flowControl));
	}

	/**
	 * Set the fragmentation block size to use
	 * @param blockSize
	 */
	public void setBlockSize(int blockSize) {
		_blockSize = blockSize;
		_segmenter.setBlockSize(blockSize);
	}

	public int getBlockSize() {
		return _blockSize;
	}

	@Override
	public void close() throws IOException {
		try {
			closeNetworkData();
			_segmenter.getFlowControl().waitForPutDrain();
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

		while (bytesToWrite > 0) {
			if (null == _blockBuffers[_blockIndex]) {
				_blockBuffers[_blockIndex] = new byte[SegmentationProfile.DEFAULT_BLOCKSIZE];
				_blockOffset = 0;
			}

			long thisBufAvail = _blockBuffers[_blockIndex].length - _blockOffset;
			long toWriteNow = (thisBufAvail > bytesToWrite) ? bytesToWrite : thisBufAvail;

			System.arraycopy(buf, (int)offset, _blockBuffers[_blockIndex], (int)_blockOffset, (int)toWriteNow);
			_dh.update(buf, (int) offset, (int)toWriteNow); // add to running digest of data

			bytesToWrite -= toWriteNow; // amount of data left to write in current call
			_blockOffset += toWriteNow; // write offset into current block buffer
			offset += toWriteNow; // read offset into input buffer
			_totalLength += toWriteNow; // increment here so we can write log entries on partial writes
			Library.logger().finest("write: added " + toWriteNow + " bytes to block. blockIndex: " + _blockIndex + " ( " + (BLOCK_BUF_COUNT-_blockIndex-1) + " left)  blockOffset: " + _blockOffset + "( " + (thisBufAvail - toWriteNow) + " left in block), " + _totalLength + " written.");

			if (_blockOffset >= _blockBuffers[_blockIndex].length) {
				Library.logger().info("write: finished writing block " + _blockIndex);
				if (_blockIndex+1 >= BLOCK_BUF_COUNT) {
					// We're out of buffers. Time to flush to the network.
					Library.logger().info("write: about to sync one tree's worth of blocks (" + BLOCK_BUF_COUNT +") to the network.");
					flush(); // will reset _blockIndex and _blockOffset
				} else {
					++_blockIndex; // move to next block buffer
					_blockOffset = 0;
				}
			}
		}
		return 0;
	}

	protected void closeNetworkData() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException {
		// Special case; if we don't need to fragment, don't; write a single block
		// with no header in the place we would normally write the header. Can't
		// do this in flush(), as we wouldn't know here whether to write a header or
		// not, and flush() wouldn't know not to use a fragment marker in the name.
		if ((_baseNameIndex == SegmentationProfile.baseSegment()) && 
				((_blockIndex == 0) || ((_blockIndex == 1) && (_blockOffset == 0)))) {
			// maybe need put with offset and length
			if ((_blockIndex == 1) || (_blockOffset == _blockBuffers[0].length)) {
				Library.logger().finest("close(): writing single-block file in one put, length: " + _blockBuffers[0].length);
				_segmenter.put(_baseName, _blockBuffers[0], 0, _blockBuffers[0].length, true,
							   ContentType.DATA, 
							   null, _locator, _publisher);
			} else {
				Library.logger().finest("close(): writing single-block file in one put, copied buffer length = " + _blockOffset);
				_segmenter.put(_baseName, _blockBuffers[0], 0, _blockOffset, true,
							   ContentType.DATA, null, _locator, _publisher);
			}
		} else {
			Library.logger().info("closeNetworkData: final flush, wrote " + _totalLength + " bytes.");
			flush(true); // true means write out the partial last block, if there is one
		}
	}

	/** 
	 * @param flushLastBlock Do we flush a partially-filled last block in the current set
	 *   of byte buffers? Not normally, we want to fill blocks. So if a user calls a manual
	 *   flush(), we want to flush all full buffers, but not a last partial -- readers of
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
	 */
	protected void flushToNetwork(boolean flushLastBlock) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException {		

		/**
		 * XXX - Can the blockbuffers have holes?
		 *     DKS: no. The blockCount argument to putMerkleTree is intended to tell
		 *     it how many of the blockBuffers array it should touch (are non-null).
		 *     If there are holes, there are a bigger problem.
		 */

		/**
		 * Partial last block handling. If we are in the middle of writing a file, we only
		 * flush complete blocks. We can be on a complete block boundary in one of two ways,
		 * either _blockOffset = 0, and _blockIndex is the count of blocks to write, or
		 * _blockOffset >= the length of the current block, and _blockIndex+1 is the count
		 * of blocks to write. Rather than requiring the caller to promise to only use
		 * one of these options, handle both, by collapsing them into a single case.
		 */
		if (0 == _blockOffset) {
			if (0 == _blockIndex) {
				// nothing to write
				return;
			}
			_blockIndex -= 1;
			_blockOffset = _blockBuffers[_blockIndex].length;
		} else if ((0 == _blockIndex) && (_blockOffset < _blockBuffers[_blockIndex].length) && (!flushLastBlock)) {
			// only a partial block written, and we're not flushing those yet, nothing to write
			return;
		}

		if (null == _timestamp)
			_timestamp = SignedInfo.now();

		// Two cases: if we're flushing only a single block, we can put it out with a 
		// straight signature without a Merkle Tree. The reading/verification code will
		// cope just fine with a single file written in a mix of MHT and straight signature
		int blockWriteCount = _blockIndex; // default -- skip partial
		boolean preservePartial = false;
		// verified blocks.
		if (0 == _blockIndex) {
			// single block to write. if we get here, it should be a full block. We only write
			// partials in response to close(), and close() on a single-block write is a 
			// single-block file which is handled separately. We might get here if someone
			// calls flush() immediately prior to calling close(); there is a risk if we
			// don't flush that they might actually not call close...
			blockWriteCount++;

			Library.logger().info("flush: asked to put a single block to the network.");

			// DKS TODO -- think about types, freshness, fix markers for impending last block/first block
			if (_blockOffset < _blockBuffers[_blockIndex].length) {
				if (!flushLastBlock) {
					Library.logger().warning("flush(): asked to write last partial block when not calling close. Assume close() will be called next, flush then.");
					return;
				}
				Library.logger().warning("flush(): writing hanging partial last block of file: " + _blockOffset + " bytes, block total is " + _blockBuffers[_blockIndex].length + ", called by close().");
				_segmenter.putFragment(_baseName, _baseNameIndex, 
									   _blockBuffers[_blockIndex], 0, _blockOffset, 
									   ContentType.DATA, _timestamp, null, null, _locator, _publisher);
			} else {
				_segmenter.putFragment(_baseName, _baseNameIndex, 
									   _blockBuffers[_blockIndex], 0, _blockBuffers[_blockIndex].length,
									   ContentType.DATA, _timestamp, null, null, _locator, _publisher);				
			}
		} else {
			// Now, we have a set of buffers. Do we have a partial last block we want to preserve?
			// We know that _blockOffset points into a partially or completely full block, not at the
			// bottom of the next block (see above). We also know we have at least 2 blocks.
			int lastBlockSize = _blockBuffers[_blockIndex-1].length;
			if (flushLastBlock || (_blockOffset >= _blockBuffers[_blockIndex].length)) {
				// last block is full or we're flushing
				blockWriteCount++;
				lastBlockSize = _blockOffset;
			} else {
				preservePartial = true;
			}

			Library.logger().info("flush: putting merkle tree to the network, " + blockWriteCount + " blocks, last block length " + lastBlockSize + " flushing final blocks? " + flushLastBlock + ".");
			// Generate Merkle tree (or other auth structure) and signedInfos and put contents.
			// We always flush all the blocks starting from 0, so the baseBlockIndex is always 0.
			// DKS TODO fix last block marking
			_segmenter.fragmentedPut(_baseName, _baseNameIndex, _blockBuffers, 
					blockWriteCount, 0, lastBlockSize,
					ContentType.DATA, _timestamp, null, null, _locator, _publisher);
		}

		int startEraseBlock = 0;
		if (preservePartial) {
			startEraseBlock = 1;
			System.arraycopy(_blockBuffers[_blockIndex], 0, _blockBuffers[0], 0, _blockOffset);
			Arrays.fill(_blockBuffers[0], _blockOffset, _blockBuffers[0].length, (byte)0);
		}
		// Set contents of blocks to 0, except potentially first.
		for (int i=startEraseBlock; i < _blockBuffers.length; ++i) {
			if (null != _blockBuffers[i])
				Arrays.fill(_blockBuffers[i], 0, _blockBuffers[i].length, (byte)0);
		}

		// Increment names
		// DKS TODO -- allow for name increments to be handled by segmenter
		// how stateless should it be?
		_baseNameIndex += blockWriteCount;
		_blockIndex = 0; // we always move down to block 0, even if we preserve a partial
		if (!preservePartial)
			_blockOffset = 0; 
	}
}
