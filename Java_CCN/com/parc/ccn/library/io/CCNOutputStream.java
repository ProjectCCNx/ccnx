package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.crypto.CCNMerkleTree;

/**
 * This particular output stream class embodies the following assumptions:
 * - content is buffered
 * - content is automatically fragmented, using the standard library fragmentation
 *    mechanisms, independently of the block size in which it is written
 * - content is written with an associated header
 * - content is authenticated using Merkle Hash Trees; each time flush() is called,
 *    available buffered data is written in a new MHT. The number of blocks in each
 *    MHT is a maximum of BLOCK_BUF_COUNT (TODO: calculate overhead), and a minimum of
 *    the number of blocks with data when flush() is called.
 *    
 * TODO contemplate renaming this class.
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
	
	protected CCNDigestHelper _dh;
	
	protected ArrayList<byte []> _roots = new ArrayList<byte[]>();

	public CCNOutputStream(ContentName name, PublisherKeyID publisher,
						   KeyLocator locator, PrivateKey signingKey,
						   CCNSegmenter cw) throws XMLStreamException, IOException {
		
		super(publisher, locator, signingKey, cw);
		
		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(nameToOpen)) {
			// DKS TODO: should we do this?
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		}
				
		// Assume if name is already versioned, caller knows what name
		// to write. If caller specifies authentication information,
		// ignore it for now.
		if (!VersioningProfile.isVersioned(nameToOpen)) {
			// if publisherID is null, will get any publisher
			ContentName currentVersionName = 
				_library.getLatestVersionName(nameToOpen, null);
			if (null == currentVersionName) {
				nameToOpen = VersioningProfile.versionName(nameToOpen, VersioningProfile.baseVersion());
			} else {
				nameToOpen = VersioningProfile.versionName(currentVersionName, (VersioningProfile.getVersionNumber(currentVersionName) + 1));
			}
		}
		// Should have name of root of version we want to
		// open. Get the header block. Already stripped to
		// root. We've altered the header semantics, so that
		// we can just get headers rather than a plethora of
		// fragments. 
		_baseName = nameToOpen;
		_blockBuffers = new byte[BLOCK_BUF_COUNT][];
		_baseNameIndex = SegmentationProfile.baseSegment();
		
		_dh = new CCNDigestHelper();
	}
	
	public CCNOutputStream(ContentName name, PublisherKeyID publisher,
			   KeyLocator locator, PrivateKey signingKey,
			   CCNLibrary library) throws XMLStreamException, IOException {
		this(name, publisher, locator, signingKey, new CCNSegmenter(name, library));
	}

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

	@Override
	public void close() throws IOException {
		try {
			closeNetworkData();
			_writer.waitForPutDrain();
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
			Library.logger().info("write: added " + toWriteNow + " bytes to block. blockIndex: " + _blockIndex + " ( " + (BLOCK_BUF_COUNT-_blockIndex-1) + " left)  blockOffset: " + _blockOffset + "( " + (thisBufAvail - toWriteNow) + " left in block), " + _totalLength + " written.");

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
				_writer.put(_baseName, _blockBuffers[0], ContentType.LEAF, _publisher, _locator, _signingKey);
			} else {
				byte [] tempBuf = new byte[_blockOffset];
				System.arraycopy(_blockBuffers[0],0,tempBuf,0,_blockOffset);
				Library.logger().finest("close(): writing single-block file in one put, copied buffer length = " + _blockOffset);
				_writer.put(_baseName, tempBuf, ContentType.LEAF, _publisher, _locator, _signingKey);
			}
		} else {
			Library.logger().info("closeNetworkData: final flush, wrote " + _totalLength + " bytes.");
			flush(true); // true means write out the partial last block, if there is one
			writeHeader();
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
	protected void flushToNetwork(boolean flushLastBlock) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException {		// DKS TODO needs to cope with partial last block. 
		
		/**
		 * Kludgy fix added by paul r. 1/20/08 - Right now the digest algorithm lower down the chain doesn't like
		 * null pointers within the blockbuffers. So if this is the case, we create a temporary smaller "blockbuffer"
		 * with only filled entries
		 * DKS -- removed kludgy fix. There should be no holes in the 
		 * blockbuffers, if there are, there is a different bug somewhere
		 * else.
		 * There are larger issues with buffers and sync, we should discuss.
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
			// single-block file which is handled separately. warn.
			blockWriteCount++;
			
			Library.logger().info("flush: putting single block to the network.");
			
			if (_blockOffset < _blockBuffers[_blockIndex].length) {
				Library.logger().warning("flush(): asked to write last partial block of a single block file: " + _blockOffset + " bytes, block total is " + _blockBuffers[_blockIndex].length + ", should have been written as a raw single-block file by close()!");
				byte [] tempBuf = new byte[_blockOffset];
				System.arraycopy(_blockBuffers[_blockIndex],0,tempBuf,0,_blockOffset);
				_writer.putFragment(_baseName, _baseNameIndex, tempBuf, _timestamp, _publisher, _locator, _signingKey);
			} else {
				_writer.putFragment(_baseName, _baseNameIndex, _blockBuffers[_blockIndex], _timestamp, _publisher, _locator, _signingKey);				
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
						
			Library.logger().info("flush: putting merkle tree to the network, " + (blockWriteCount) + " blocks.");
			// Generate Merkle tree (or other auth structure) and signedInfos and put contents.
			// We always flush all the blocks starting from 0, so the baseBlockIndex is always 0.
			CCNMerkleTree tree =
				_writer.putMerkleTree(_baseName, _baseNameIndex, _blockBuffers, blockWriteCount, 0, lastBlockSize,
								   		_timestamp, _publisher, _locator, _signingKey);
			_roots.add(tree.root());
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
		_baseNameIndex += blockWriteCount;
		_blockIndex = 0; // we always move down to block 0, even if we preserve a partial
		if (!preservePartial)
			_blockOffset = 0; 
	}
	
	protected void writeHeader() throws InvalidKeyException, SignatureException, IOException, InterruptedException {
		// What do we put in the header if we have multiple merkle trees?
		_writer.putHeader(_baseName, (int)_totalLength, _blockSize, _dh.digest(), 
				((_roots.size() > 0) ? _roots.get(0) : null),
				_timestamp, _publisher, _locator, _signingKey);
		Library.logger().info("Wrote header: " + CCNSegmenter.headerName(_baseName));
	}

}
