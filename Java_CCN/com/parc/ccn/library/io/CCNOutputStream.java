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
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;
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
	protected int _baseBlockIndex; // base index of current set of block buffers.
	protected int _blockSize = Header.DEFAULT_BLOCKSIZE;
	
	protected Timestamp _timestamp; // timestamp we use for writing, set to first time we write
	
	protected CCNDigestHelper _dh;
	
	protected ArrayList<byte []> _roots = new ArrayList<byte[]>();

	public CCNOutputStream(ContentName name, PublisherKeyID publisher,
						   KeyLocator locator, PrivateKey signingKey,
						   CCNLibrary library) throws XMLStreamException, IOException {
		
		super(publisher, locator, signingKey, library);
		
		ContentName nameToOpen = name;
		if (CCNLibrary.isFragment(nameToOpen)) {
			// DKS TODO: should we do this?
			nameToOpen = CCNLibrary.fragmentRoot(nameToOpen);
		}
				
		// Assume if name is already versioned, caller knows what name
		// to write. If caller specifies authentication information,
		// ignore it for now.
		if (!CCNLibrary.isVersioned(nameToOpen)) {
			// if publisherID is null, will get any publisher
			ContentName currentVersionName = 
				_library.getLatestVersionName(nameToOpen, null);
			if (null == currentVersionName) {
				nameToOpen = CCNLibrary.versionName(nameToOpen, CCNLibrary.baseVersion());
			} else {
				nameToOpen = CCNLibrary.versionName(currentVersionName, (_library.getVersionNumber(currentVersionName) + 1));
			}
		}
		// Should have name of root of version we want to
		// open. Get the header block. Already stripped to
		// root. We've altered the header semantics, so that
		// we can just get headers rather than a plethora of
		// fragments. 
		_baseName = nameToOpen;
		_blockBuffers = new byte[BLOCK_BUF_COUNT][];
		_baseBlockIndex = CCNLibrary.baseFragment();
		
		_dh = new CCNDigestHelper();
	}

	@Override
	public void close() throws IOException {
		try {
			closeNetworkData();
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
		try {
			flushToNetwork();
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
				_blockBuffers[_blockIndex] = new byte[Header.DEFAULT_BLOCKSIZE];
				_blockOffset = 0;
			}

			long thisBufAvail = _blockBuffers[_blockIndex].length - _blockOffset;
			long toWriteNow = (thisBufAvail > bytesToWrite) ? bytesToWrite : thisBufAvail;

			System.arraycopy(buf, (int)offset, _blockBuffers[_blockIndex], (int)_blockOffset, (int)toWriteNow);
			_dh.update(buf, (int) offset, (int)toWriteNow);

			bytesToWrite -= toWriteNow; 
			_blockOffset += toWriteNow;
			Library.logger().finer("write: added " + toWriteNow + " bytes to block. blockIndex: " + _blockIndex + " ( " + (BLOCK_BUF_COUNT-_blockIndex-1) + " left)  blockOffset: " + _blockOffset + "( " + (thisBufAvail - toWriteNow) + " left in block).");

			if (_blockOffset >= _blockBuffers[_blockIndex].length) {
				Library.logger().finer("write: finished writing block " + _blockIndex);
				if (_blockIndex+1 >= BLOCK_BUF_COUNT) {
					Library.logger().fine("write: about to sync one tree's worth of blocks (" + BLOCK_BUF_COUNT +") to the network.");
					flush();
				} else {
					++_blockIndex;
					_blockOffset = 0;
				}
			}
		}
		_totalLength += len;
		return 0;
	}
	
	protected void closeNetworkData() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException {
		// Special case; if we don't need to fragment, don't. Can't
		// do this in sync(), as we can't tell a manual sync from a close.
		// Though that means a manual sync(); close(); on a short piece of
		// content would end up with unnecessary fragmentation...
		if ((_baseBlockIndex == CCNLibrary.baseFragment()) && 
				((_blockIndex == 0) || ((_blockIndex == 1) && (_blockOffset == 0)))) {
			// maybe need put with offset and length
			if ((_blockIndex == 1) || (_blockOffset == _blockBuffers[0].length)) {
				Library.logger().finest("close(): writing single-block file in one put, length: " + _blockBuffers[0].length);
				_library.put(_baseName, _blockBuffers[0], _type, _publisher, _locator, _signingKey);
			} else {
				byte [] tempBuf = new byte[_blockOffset];
				System.arraycopy(_blockBuffers[0],0,tempBuf,0,_blockOffset);
				Library.logger().finest("close(): writing single-block file in one put, copied buffer length = " + _blockOffset);
				_library.put(_baseName, tempBuf, _type, _publisher, _locator, _signingKey);
			}
		} else {
			// DKS TODO Needs to cope with partial last block.
			flush();
			writeHeader();
		}
	}

	/** 
	 * DKS TODO: make it easier to write streams with block names that say correspond
	 *  to byte or time offsets... Right now this API focuses on writing fragmented files.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InterruptedException
	 * @throws IOException
	 */
	protected void flushToNetwork() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException {
		// DKS TODO needs to cope with partial last block. 
		if (null == _timestamp)
			_timestamp = SignedInfo.now();
		
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
	
		Library.logger().info("sync: putting merkle tree to the network, " + (_blockIndex+1) + " blocks.");
		// Generate Merkle tree (or other auth structure) and signedInfos and put contents.
		CCNMerkleTree tree =
			_library.putMerkleTree(_baseName, _baseBlockIndex, _blockBuffers, _blockIndex+1, 0,
								   _timestamp, _publisher, _locator, _signingKey);
		_roots.add(tree.root());
		
		// Set contents of blocks to 0
		for (int i=0; i < _blockBuffers.length; ++i) {
			if (null != _blockBuffers[i])
				Arrays.fill(_blockBuffers[i], 0, _blockBuffers[i].length, (byte)0);
		}
		_baseBlockIndex += _blockIndex+1;
		_blockIndex = 0;
		// DKS TODO -- look at flush behavior. Arrange it so that
		// file stream interface flushes only to last block, not partial
		// block, except on close; so all blocks are complete. Other
		// streams might want partial blocks.
		_blockOffset = 0; // if we flush with a partial last block, we still flush it...
	}
	
	protected void writeHeader() throws InvalidKeyException, SignatureException, IOException, InterruptedException {
		// What do we put in the header if we have multiple merkle trees?
		_library.putHeader(_baseName, (int)_totalLength, _blockSize, _dh.digest(), 
				((_roots.size() > 0) ? _roots.get(0) : null),
				_type,
				_timestamp, _publisher, _locator, _signingKey);
		Library.logger().info("Wrote header: " + CCNLibrary.headerName(_baseName));
	}

}
