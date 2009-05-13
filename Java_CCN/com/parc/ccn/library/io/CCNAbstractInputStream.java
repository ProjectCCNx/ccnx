package com.parc.ccn.library.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.security.crypto.ContentKeys;

public abstract class CCNAbstractInputStream extends InputStream {

	protected static final int MAX_TIMEOUT = 2000;

	protected CCNLibrary _library;

	protected ContentObject _currentBlock = null;
	protected ByteArrayInputStream _currentBlockStream = null;
	protected InputStream _blockReadStream = null; // includes filters, etc.
	
	/**
	 * This is the name we are querying against, prior to each
	 * fragment/segment number.
	 */
	protected ContentName _baseName = null;
	protected PublisherPublicKeyDigest _publisher = null;
	protected Long _startingBlockIndex = null;
	protected int _timeout = MAX_TIMEOUT;
	
	/**
	 *  Encryption/decryption handler
	 */
	protected Cipher _cipher;
	protected ContentKeys _keys;
	
	/**
	 * If this content uses Merkle Hash Trees or other structures to amortize
	 * signature cost, we can amortize verification cost as well by caching verification
	 * data. Store the currently-verified root signature, so we don't have to re-verify;
	 * and the verified root hash. For each piece of incoming content, see if it aggregates
	 * to the same root, if so don't reverify signature. If not, assume it's part of
	 * a new tree and change the root.
	 */
	protected byte [] _verifiedRootSignature = null;
	protected byte [] _verifiedProxy = null;

	public CCNAbstractInputStream(
			ContentName baseName, Long startingBlockIndex,
			PublisherPublicKeyDigest publisher, CCNLibrary library) 
					throws XMLStreamException, IOException {
		super();
		
		if (null == baseName) {
			throw new IllegalArgumentException("baseName cannot be null!");
		}
		_library = library; 
		if (null == _library) {
			_library = CCNLibrary.getLibrary();
		}
		_publisher = publisher;	
		
		// So, we assume the name we get in is up to but not including the sequence
		// numbers, whatever they happen to be. If a starting block is given, we
		// open from there, otherwise we open from the leftmost number available.
		// We assume by the time you've called this, you have a specific version or
		// whatever you want to open -- this doesn't crawl versions.  If you don't
		// offer a starting block index, but instead offer the name of a specific
		// segment, this will use that segment as the starting block. 
		if ((null == startingBlockIndex)  && (SegmentationProfile.isSegment(baseName))) {
			_startingBlockIndex = SegmentationProfile.getSegmentNumber(baseName);
		} else {
			_startingBlockIndex = startingBlockIndex;
		}
		if (null == _startingBlockIndex) {
			_startingBlockIndex = SegmentationProfile.baseSegment();
		}
		_baseName = baseName;
	}
	
	public CCNAbstractInputStream(
			ContentName baseName, Long startingBlockIndex,
			PublisherPublicKeyDigest publisher,
			ContentKeys keys, CCNLibrary library) 
					throws XMLStreamException, IOException {
		
		this(baseName, startingBlockIndex, publisher, library);
		
		if (null != keys) {
			keys.OnlySupportDefaultAlg();
			_keys = keys;
			_cipher = keys.getCipher();
		}
	}
	
	public CCNAbstractInputStream(ContentObject starterBlock, 			
			CCNLibrary library) throws IOException  {
		super();
		if (null == starterBlock) {
			throw new IllegalArgumentException("starterBlock cannot be null!");
		}
		_library = library; 
		if (null == _library) {
			_library = CCNLibrary.getLibrary();
		}
		setCurrentBlock(starterBlock);
		_publisher = starterBlock.signedInfo().getPublisherKeyID();
		_baseName = SegmentationProfile.segmentRoot(starterBlock.name());
		try {
			_startingBlockIndex = SegmentationProfile.getSegmentNumber(starterBlock.name());
		} catch (NumberFormatException nfe) {
			throw new IOException("Stream starter block name does not contain a valid segment number, so the stream does not know what content to start with.");
		}
	}

	public CCNAbstractInputStream(ContentObject starterBlock, 			
			ContentKeys keys,
			CCNLibrary library) throws IOException {

		this(starterBlock, library);
		
		keys.OnlySupportDefaultAlg();
		_keys = keys;
		_cipher = keys.getCipher();
	}

	public void setTimeout(int timeout) {
		_timeout = timeout;
	}

	@Override
	public int read() throws IOException {
		byte [] b = new byte[1];
		if (read(b, 0, 1) < 0) {
			return -1;
		}
		return (0x000000FF & b[0]);
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	/**
	 * Reads a packet/block into the buffer. If the buffer is shorter than
	 * the packet's length, reads out of the current block for now.
	 * Aim is really to do packet-sized reads. Probably ought to be a DatagramSocket subclass.
	 * @param buf the buffer into which to write.
	 * @param offset the offset into buf at which to write data
	 * @param len the number of bytes to write
	 * @return -1 if at EOF, or number of bytes read
	 * @throws IOException 
	 */
	@Override
	public int read(byte[] buf, int offset, int len) throws IOException {

		if (null == buf)
			throw new NullPointerException("Buffer cannot be null!");
		
		return readInternal(buf, offset, len);
	}
	
	protected abstract int readInternal(byte [] buf, int offset, int len) throws IOException;
	
	/**
	 * Set up current block for reading, including prep for decryption if necessary.
	 * Called after getBlock/getFirstBlock/getNextBlock, which take care of verifying
	 * the block for us. So we assume newBlock is valid.
	 * @throws IOException 
	 */
	protected void setCurrentBlock(ContentObject newBlock) throws IOException {
		_currentBlock = null;
		_currentBlockStream = null;
		_blockReadStream = null;
		if (null == newBlock) {
			Library.logger().info("Setting current block to null! Did a block fail to verify?");
			return;
		}
		
		_currentBlock = newBlock;
		_currentBlockStream = new ByteArrayInputStream(_currentBlock.content());
		if (null != _cipher) {
			try {
				// Reuse of current block OK. Don't expect to have two separate readers
				// independently use this stream without state confusion anyway.
				_cipher = _keys.getSegmentDecryptionCipher(_cipher, SegmentationProfile.getSegmentNumber(_currentBlock.name()));
			} catch (InvalidKeyException e) {
				Library.logger().warning("InvalidKeyException: " + e.getMessage());
				throw new IOException("InvalidKeyException: " + e.getMessage());
			} catch (InvalidAlgorithmParameterException e) {
				Library.logger().warning("InvalidAlgorithmParameterException: " + e.getMessage());
				throw new IOException("InvalidAlgorithmParameterException: " + e.getMessage());
			}
			_blockReadStream = new CipherInputStream(_currentBlockStream, _cipher);
		} else {
			_blockReadStream = _currentBlockStream;
			if (_currentBlock.signedInfo().getType().equals(ContentType.ENCR)) {
				Library.logger().warning("Asked to read encrypted content, but not given a key to decrypt it. Decryption happening at higher level?");
			}
		}
	}

	/**
	 * Three navigation options: get first (leftmost) block, get next block,
	 * or get a specific block.
	 * Have to assume that everyone is using our segment number encoding. Probably
	 * easier to ask raw streams to use that encoding (e.g. for packet numbers)
	 * than to flag streams as to whether they are using integers or segments.
	 **/
	protected ContentObject getBlock(long number) throws IOException {

        // Block name requested should be interpreted literally, not taken
        // relative to baseSegment().
		ContentName blockName = SegmentationProfile.segmentName(_baseName, number);

		if (_currentBlock != null) {
			//what block do we have right now?  maybe we already have it
			if (currentBlockNumber() == number){
				//we already have this block..
				return _currentBlock;
			}
		}
		
		Library.logger().info("getBlock: getting block " + blockName);
		ContentObject block = _library.getLower(blockName, 1, _timeout);

		if (null == block) {
			Library.logger().info("Cannot get block " + number + " of file " + _baseName + " expected block: " + blockName.toString());
			throw new IOException("Cannot get block " + number + " of file " + _baseName + " expected block: " + blockName.toString());
		} else {
			Library.logger().info("getBlock: retrieved block " + block.name());
		}
		// So for the block, we assume we have a potential document.
		if (!verifyBlock(block)) {
			return null;
		}
		return block;
	}
	

	protected ContentObject getNextBlock() throws IOException {
		
		// Check to see if finalBlockID is the current block. If so, there should
		// be no next block. (If the writer makes a mistake and guesses the wrong
		// value for finalBlockID, they won't put that wrong value in the block they're
		// guessing itself -- unless they want to try to extend a "closed" stream.
		// Normally by the time they write that block, they either know they're done or not.
		if (null != _currentBlock.signedInfo().getFinalBlockID()) {
			if (Arrays.equals(_currentBlock.signedInfo().getFinalBlockID(), _currentBlock.name().lastComponent())) {
				Library.logger().info("getNextBlock: there is no next block. We have block: " + 
						DataUtils.printHexBytes(_currentBlock.name().lastComponent()) + " which is marked as the final block.");
				return null;
			}
		}
		
		Library.logger().info("getNextBlock: getting block after " + _currentBlock.name());
		return getBlock(nextBlockIndex());
	}

	protected ContentObject getFirstBlock() throws IOException {
		if (null != _startingBlockIndex) {
			return getBlock(_startingBlockIndex);
		} else {
			throw new IOException("Stream does not have a valid starting block number.");
		}
	}

	boolean verifyBlock(ContentObject block) {

		// First we verify. 
		// Low-level verify just checks that signer actually signed.
		// High-level verify checks trust.
		try {

			// We could have several options here. This block could be simply signed.
			// or this could be part of a Merkle Hash Tree. If the latter, we could
			// already have its signing information.
			if (null == block.signature().witness()) {
				return block.verify(null);
			}

			// Compare to see whether this block matches the root signature we previously verified, if
			// not, verify and store the current signature.
			// We need to compute the proxy regardless.
			byte [] proxy = block.computeProxy();

			// OK, if we have an existing verified signature, and it matches this block's
			// signature, the proxy ought to match as well.
			if ((null != _verifiedRootSignature) && (Arrays.equals(_verifiedRootSignature, block.signature().signature()))) {
				if ((null == proxy) || (null == _verifiedProxy) || (!Arrays.equals(_verifiedProxy, proxy))) {
					Library.logger().warning("Found block: " + block.name() + " whose digest fails to verify; block length: " + block.contentLength());
					Library.logger().info("Verification failure: " + block.name() + " timestamp: " + block.signedInfo().getTimestamp() + " content length: " + block.contentLength() + 
							" content digest: " + DataUtils.printBytes(block.contentDigest()) + " proxy: " + 
							DataUtils.printBytes(proxy) + " expected proxy: " + DataUtils.printBytes(_verifiedProxy));
	 				return false;
				}
			} else {
				// Verifying a new block. See if the signature verifies, otherwise store the signature
				// and proxy.
				if (!ContentObject.verify(proxy, block.signature().signature(), block.signedInfo(), block.signature().digestAlgorithm(), null)) {
					Library.logger().warning("Found block: " + block.name().toString() + " whose signature fails to verify; block length: " + block.contentLength() + ".");
					return false;
				} else {
					// Remember current verifiers
					_verifiedRootSignature = block.signature().signature();
					_verifiedProxy = proxy;
				}
			} 
			Library.logger().info("Got block: " + block.name().toString() + ", verified.");
		} catch (Exception e) {
			Library.logger().warning("Got an " + e.getClass().getName() + " exception attempting to verify block: " + block.name().toString() + ", treat as failure to verify.");
			Library.warningStackTrace(e);
			return false;
		}
		return true;
	}

	public long blockIndex() {
		if (null == _currentBlock) {
			return SegmentationProfile.baseSegment();
		} else {
			// This needs to work on streaming content that is not traditional fragments.
			// The segmentation profile tries to do that, though it is seeming like the
			// new segment representation means we will have to assume that representation
			// even for stream content.
			return SegmentationProfile.getSegmentNumber(_currentBlock.name());
		}
	}
	
	/**
	 * Return the index of the next block of stream data.
	 * Default segmentation generates sequentially-numbered stream
	 * blocks but this method may be overridden in subclasses to 
	 * perform re-assembly on streams that have been segemented differently.
	 * @return
	 */
	public long nextBlockIndex() {
		if (null == _currentBlock) {
			return _startingBlockIndex.longValue();
		} else {
			return blockIndex() + 1;
		}
	}
	
	protected long currentBlockNumber(){
		if (null == _currentBlock) {
			return -1; // make sure we don't match inappropriately
		}
		return blockIndex();
	}
	
}