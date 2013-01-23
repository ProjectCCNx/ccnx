/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.io;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNSegmenter;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Basic output stream class which generates segmented content under a given
 * name prefix. Segment naming is generated according to the SegmentationProfile;
 * by default names are sequentially numbered. Name prefixes are taken as specified
 * (no versions or other information is added by this class). Segments are
 * fixed length (see CCNBlockOutputStream for non fixed-length segments).
 */
public class CCNOutputStream extends CCNAbstractOutputStream {

	/**
	 * Amount of data we keep around prior to forced flush to segmenter, in terms of segmenter
	 * blocks. We write to a limit lower than the maximum, to allow for expansion
	 * due to encryption. We believe we want a low number here to allow for effective interleaving
	 * of threading in read/write/repo running within the same JVM. We have to have at least one
	 * extra block to allow for holding back data for final block writing etc (see notes about this
	 * below). In practice most flushing to the segmenter will result in in creating BLOCK_BUF_COUNT - 1
	 * ContentObjects in the segmenter.
	 */
	public static final int BLOCK_BUF_COUNT = 128;	// Must be at least 2

	/**
	 * elapsed length written
	 */
	protected long _totalLength = 0;

	/**
	 * write pointer - offset into the current write buffer at which to write
	 */
	protected int _blockOffset = 0;

	/**
	 * write pointer - current write buffer at which to write
	 */
	protected int _blockIndex = 0;

	/**
	 * write buffers
	 */
	protected byte [][] _buffers = null;
	/**
	 * base name index of the current set of data to output;
	 * incremented according to the segmentation profile.
	 */
	protected long _baseNameIndex;

	/**
	 * // timestamp we use for writing, set to time first segment is written
	 */
	protected CCNTime _timestamp;

	protected Integer _freshnessSeconds; // if null, use default

	protected CCNDigestHelper _dh;
	protected boolean _nameSpaceAdded = false;
	protected boolean _flushed = false;	// Used to avoid double writes of final block
										// on redundant close calls

	protected boolean _FCIsOurs = false; // Did we create our flow controller?  If so we should close it.

	/**
	 * Constructor for a simple CCN output stream.
	 * @param baseName name prefix under which to write content segments
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName baseName, CCNHandle handle) throws IOException {
		this(baseName, (PublisherPublicKeyDigest)null, handle);
	}

	/**
	 * Constructor for a simple CCN output stream.
	 * @param baseName name prefix under which to write content segments
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName baseName,
						   PublisherPublicKeyDigest publisher,
						   CCNHandle handle) throws IOException {
		this(baseName, null, publisher, null, null, handle);
	}

	/**
	 * Constructor for a simple CCN output stream.
	 * @param baseName name prefix under which to write content segments
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName baseName, ContentKeys keys, CCNHandle handle) throws IOException {
		this(baseName, null, null, null, keys, handle);
	}

	/**
	 * Constructor for a simple CCN output stream.
	 * @param baseName name prefix under which to write content segments
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName baseName,
			  			   KeyLocator locator,
			  			   PublisherPublicKeyDigest publisher,
			  			   ContentKeys keys,
			  			   CCNHandle handle) throws IOException {
		this(baseName, locator, publisher, null, keys, handle);
	}

	/**
	 * Constructor for a simple CCN output stream.
	 * @param baseName name prefix under which to write content segments
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param type type to mark content (see ContentType), if null, DATA is used; if
	 * 			content encrypted, ENCR is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName baseName,
						   KeyLocator locator,
						   PublisherPublicKeyDigest publisher,
						   ContentType type,
						   ContentKeys keys,
						   CCNHandle handle) throws IOException {
		this(baseName, locator, publisher, type, keys, new CCNFlowControl(baseName, handle));
		_FCIsOurs = true;
	}

	/**
	 * Special purpose constructor.
	 */
	protected CCNOutputStream() {}

	/**
	 * Low-level constructor used by clients that need to specify flow control behavior.
	 * @param baseName name prefix under which to write content segments
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param type type to mark content (see ContentType), if null, DATA is used; if
	 * 			content encrypted, ENCR is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param flowControl flow controller used to buffer output content
	 * @throws IOException if flow controller setup fails
	 */
	public CCNOutputStream(ContentName baseName,
                           KeyLocator locator,
                           PublisherPublicKeyDigest publisher,
                           ContentType type,
                           ContentKeys keys,
                           CCNFlowControl flowControl) throws IOException {
		this(baseName, locator, publisher, type, keys, new CCNSegmenter(flowControl, null));
	}

	/**
	 * Low-level constructor used by subclasses that need to specify segmenter behavior.
	 * @param baseName name prefix under which to write content segments
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param type type to mark content (see ContentType), if null, DATA is used; if
	 * 			content encrypted, ENCR is used.
	 * @param keys the ContentKeys to use to encrypt, or null if unencrypted or access
	 * 	controlled (keys automatically retrieved)
	 * @param segmenter segmenter used to segment and sign content
	 * @throws IOException if flow controller setup fails
	 */
	protected CCNOutputStream(ContentName baseName,
							  KeyLocator locator,
							  PublisherPublicKeyDigest publisher,
							  ContentType type,
							  ContentKeys keys,
							  CCNSegmenter segmenter) throws IOException {

		super((SegmentationProfile.isSegment(baseName) ? SegmentationProfile.segmentRoot(baseName) : baseName),
			  locator, publisher, type, keys, segmenter);

		_buffers = new byte[BLOCK_BUF_COUNT][];
		// Always make the first one; it simplifies error handling later and only is superfluous if we
		// attempt to write an empty stream, which is rare.
		_buffers[0] = new byte[_segmenter.getBlockSize()];

		_baseNameIndex = SegmentationProfile.baseSegment();

		_dh = new CCNDigestHelper();
		startWrite(); // set up flow controller to write
	}

	@Override
	protected void startWrite() throws IOException {
		super.startWrite();
		_segmenter.getFlowControl().startWrite(_baseName, Shape.STREAM);
	}

	/**
	 * Set the segmentation block size to use. Constraints: needs to be
	 * a multiple of the likely encryption block size (which is, conservatively, 32 bytes).
	 * Default is 4096.
	 * @param blockSize in bytes
	 */
	public synchronized void setBlockSize(int blockSize) throws IOException {
		if (blockSize <= 0) {
			throw new IllegalArgumentException("Cannot set negative or zero block size!");
		}
		if (blockSize == getBlockSize()) {
			// doing nothing, return
			return;
		}
        if (_totalLength > 0) {
            throw new IOException("Cannot set block size after writing");
        }

		getSegmenter().setBlockSize(blockSize);
        // Nothing written, throw away first buffer and replace it with one of the right size
        _buffers[0] = new byte[blockSize];
	}

	/**
	 * Get segmentation block size.
	 * @return block size in bytes
	 */
	public int getBlockSize() {
		return getSegmenter().getBlockSize();
	}

	public void setFreshnessSeconds(Integer freshnessSeconds) {
		_freshnessSeconds = freshnessSeconds;
	}

	@Override
	public void close() throws IOException {
		try {
			_segmenter.getFlowControl().beforeClose();
			closeNetworkData();
			_segmenter.getFlowControl().afterClose();
			if (_FCIsOurs)
				_segmenter.getFlowControl().close();
			Log.info(Log.FAC_IO, "CCNOutputStream close: {0}", _baseName);
		} catch (InvalidKeyException e) {
			Log.logStackTrace(Level.WARNING, e);
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			Log.logStackTrace(Level.WARNING, e);
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		} catch (InterruptedException e) {
			Log.logStackTrace(Level.WARNING, e);
			throw new IOException("Low-level network failure!: " + e.getMessage());
		}
	}

	@Override
	public void flush() throws IOException {
		flush(false, true); // if there is a partial block, don't flush it
	}

	/**
	 * Internal flush.
	 * @param flushLastBlock Should we flush the last (partial) block, or hold it back
	 *   for it to be filled.
	 * @throws IOException on a variety of types of error.
	 */
	protected void flush(boolean flushLastBlock, boolean flushNow) throws IOException {
		try {
			flushToNetwork(flushLastBlock, flushNow);
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
		} catch (InvalidAlgorithmParameterException e) {
			throw new IOException("Cannot sign content -- Invalid algorithm parameter!: " + e.getMessage());
		}
	}

	/**
	 * Actually write bytes to the network.
	 * @param buf as in write(byte[], int, int)
	 * @param offset as in write(byte[], int, int)
	 * @param len as in write(byte[])
	 * @throws IOException on network errors
	 * @throws InvalidKeyException if we cannot encrypt content as specified
	 * @throws SignatureException if we cannot sign content
	 * @throws NoSuchAlgorithmException if encryption requests invalid algorithm
	 * @throws InvalidAlgorithmParameterException
	 */
	protected synchronized void writeToNetwork(byte[] buf, long offset, long len) throws IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
		if ((len < 0) || (null == buf) || ((offset + len) > buf.length))
			throw new IllegalArgumentException("Invalid argument!");

		long bytesToWrite = len;
		int blockSize = getBlockSize();

		// Fill up to a buffer if we can to align the writes
		if (_blockOffset % blockSize != 0 && (_blockOffset + bytesToWrite) > blockSize) {
			int copySize = blockSize - _blockOffset;
			System.arraycopy(buf, (int)offset, _buffers[_blockIndex], _blockOffset, copySize);
			_dh.update(buf, (int) offset, copySize); // add to running digest of data
			offset += copySize;
			_blockOffset = blockSize;
			bytesToWrite -= copySize;
		}

		if (_blockOffset % blockSize == 0 && bytesToWrite > 0) {
			// Flush all complete blocks we have to the segmenter
			if (_blockIndex > 0 || _blockOffset > 0) {
				_baseNameIndex =
			        _segmenter.fragmentedPut(_baseName, _baseNameIndex, _buffers, _blockIndex+1,
			                                 0, blockSize,
			                                 _type, _timestamp, _freshnessSeconds, null,
			                                 _locator, _publisher, _keys);
				_blockOffset = _blockIndex = 0;
			}

			// Now if we have more than a blocksize worth of data, we can avoid copying by
			// sending all full blocks we have directly to the sequencer
			if (bytesToWrite >= blockSize) {
				long contiguousBytesToWrite = (bytesToWrite / blockSize) * blockSize;
				bytesToWrite -= contiguousBytesToWrite;
				_dh.update(buf, (int) offset, (int)contiguousBytesToWrite); // add to running digest of data

				if (!_nameSpaceAdded) {
					if( Log.isLoggable(Level.INFO))
						Log.info("Adding namespace in writeToNetwork. Namespace: {0}", _baseName);
					_segmenter.getFlowControl().addNameSpace(_baseName);
					_nameSpaceAdded = true;
				}

				_baseNameIndex = _segmenter.fragmentedPut(_baseName, _baseNameIndex,
						buf, (int)offset, (int)contiguousBytesToWrite, blockSize, _type, null,
						_freshnessSeconds, null, _locator, _publisher, _keys);
				offset += contiguousBytesToWrite;
				_totalLength += contiguousBytesToWrite;
			}
		}

		// Here's an advantage of the old, complicated way -- with that, only had to allocate
		// as many blocks as you were going to write.
		while (bytesToWrite > 0) {
			if (null == _buffers[_blockIndex]) {
				_buffers[_blockIndex] = new byte[blockSize];
			}

			// Increment _blockIndex here, if do it at end of loop gets confusing
			// Already checked for need to flush and flush at end of last loop
			if (_blockOffset >= blockSize) {
				_blockIndex++;
				_blockOffset = 0;
				if (null == _buffers[_blockIndex]) {
					_buffers[_blockIndex] = new byte[blockSize];
				}
			}

			long thisBufAvail = blockSize - _blockOffset;
			long toWriteNow = (thisBufAvail > bytesToWrite) ? bytesToWrite : thisBufAvail;

			System.arraycopy(buf, (int)offset, _buffers[_blockIndex], _blockOffset, (int)toWriteNow);
			_dh.update(buf, (int) offset, (int)toWriteNow); // add to running digest of data

			bytesToWrite -= toWriteNow; // amount of data left to write in current call
			_blockOffset += toWriteNow; // write offset into current block buffer
			offset += toWriteNow; // read offset into input buffer
			_totalLength += toWriteNow; // increment here so we can write log entries on partial writes
			if (Log.isLoggable(Log.FAC_IO, Level.FINEST ))
				Log.finest(Log.FAC_IO, "write: added " + toWriteNow + " bytes to buffer. blockOffset: " + _blockOffset + "( " + (thisBufAvail - toWriteNow) + " left in block), " + _totalLength + " written.");

			if ((_blockOffset >= blockSize) && ((_blockIndex+1) >= _buffers.length)) {
				// We're out of buffers. Time to flush to the network.
				Log.fine(Log.FAC_IO, "write: about to sync one tree's worth of blocks (" + BLOCK_BUF_COUNT +") to the network.");
				flush(false, false); // will reset _blockIndex and _blockOffset
			}
		}
	}

	/**
	 * Flush partial hanging block if we have one.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	protected void closeNetworkData() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException {
		// flush last partial buffers. Remove previous code to specially handle
		// small data objects (written as single blocks without headers); instead
		// write them as single-fragment files. Subclasses will determine whether or not
		// to write a header.
		if(Log.isLoggable(Log.FAC_IO, Level.FINE))
			Log.fine(Log.FAC_IO, "closeNetworkData: final flush, wrote " + _totalLength + " bytes, base index " + _baseNameIndex);
		flush(true, true); // true means write out the partial last block, if there is one
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
	protected synchronized void flushToNetwork(boolean flushLastBlock, boolean flushNow) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException, InvalidAlgorithmParameterException {
		if (_flushed)
			return;
		int blockSize = getBlockSize();

		/**
		 * XXX - Can the blockbuffers have holes?
		 *     DKS: no. The blockCount argument to putMerkleTree is intended to tell
		 *     it how many of the blockBuffers array it should touch (are non-null).
		 *     If there are holes, there is a bigger problem.
		 */

		/**
		 * Partial last block handling. If we are in the middle of writing a file, we only
		 * flush complete blocks; up to _blockOffset % blockSize. The only time
		 * we emit a 0-length block is if we've been told to flush the last block (i.e.
		 * we're closing the file) without having written anything at all. So for
		 * 0-length files we emit a single block with 0-length content (which has
		 * a Content element, but no contained BLOB).
		 */
		if (!flushNow && 0 == _blockIndex) {
			if ((_blockOffset <= blockSize) && (!flushLastBlock)) {
				// We've written only a single block's worth of data (or less),
				// but we are not forcing a flush of the last block, so don't write anything.
				// We don't put out partial blocks until
				// close is called (or otherwise have flushLastBlock=true), so it's
				// easy to understand holding in that case. However, if we want to
				// set finalBlockID, we can't do that till we know it -- till close is
				// called. So we should hold off on writing the last full block as well,
				// until we are closed. Unfortunately that means if you call flush()
				// right before close(), you'll tend to sign all but the last block,
				// and sign that last one separately when you actually flush it.
				return;
			}
		}

		if (flushLastBlock)
			_flushed = true;

		if (null == _timestamp)
			_timestamp = CCNTime.now();

		boolean preservePartial = false;
		int saveBytes = 0;

		if (!flushLastBlock) {
			saveBytes = _blockOffset;
			preservePartial = true;
		} // otherwise saveBytes = 0, so ok

		if (Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "flush: outputting to the segmenter, baseName " + _baseName +
                     " basenameindex " + Component.printURI(SegmentationProfile.getSegmentNumberNameComponent(_baseNameIndex)) + "; " 
                     + _blockOffset + 
                     " bytes written, holding back " + saveBytes + " flushing final blocks? " + flushLastBlock + ".");
		// Flush to segmenter to generate ContentObjects, sign when appropriate, and output to flow controller
		// We always flush all the blocks starting from 0, so the baseBlockIndex is always 0.
		// Two cases:
		// no partial, write all blocks including potentially short last block
		// don't write last block (whole or partial), write n-1 full blocks
		_baseNameIndex = _segmenter.fragmentedPut(_baseName, _baseNameIndex, _buffers,
                                 (preservePartial && !flushLastBlock ? _blockIndex : _blockIndex+1),
                                 0,
                                 (preservePartial && !flushLastBlock ? blockSize : _blockOffset),
                                 _type, _timestamp, _freshnessSeconds,
                                 (flushLastBlock ? CCNSegmenter.LAST_SEGMENT : null),
                                 _locator, _publisher, _keys, flushNow);

		if (preservePartial) {
			//System.arraycopy(_buffers[_blockIndex], _blockOffset-saveBytes, _buffers[0], 0, saveBytes);
			byte[] tmp = _buffers[_blockIndex];
			_buffers[_blockIndex] = _buffers[0];
			_buffers[0] = tmp;
			_blockOffset = saveBytes;
		} else {
			_blockOffset = 0;
		}
		_blockIndex = 0;

		if (Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "HEADER: CCNOutputStream: flushToNetwork: new _baseNameIndex {0}", _baseNameIndex);
	}

	/**
	 * @return number of bytes that have been written on this stream.
	 */
	protected long lengthWritten() {
		return _totalLength;
	}
}
