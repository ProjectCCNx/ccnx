package com.parc.ccn.library.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;

/**
 * Perform sequential reads on any block-oriented CCN content, namely that
 * where the name component after the specified name prefix is an integer, and the
 * content blocks are indicated by increasing (but not necessarily sequential)
 * integers. For example, a file could be divided into sequential blocks,
 * while an audio stream might have blocks named by time offsets into the
 * stream. 
 * 
 * This input stream will read from a sequence of blocks, authenticating
 * each as it goes, and caching what information it can. All it assumes
 * is that the last component of the name is an increasing integer
 * value, where we start with the name we are given, and get right siblings
 * (next blocks) moving forward.
 * @author smetters
 *
 */
public class CCNInputStream extends InputStream implements CCNInterestListener {
	
	protected CCNLibrary _library = null;
	
	protected ContentObject _currentBlock = null;
	protected int _blockOffset = 0; // read/write offset into current block
	protected boolean _atEOF = false;
	protected int _readlimit = 0;
	protected int _markOffset = 0;
	protected int _markBlock = 0;

	/**
	 * This is the name we are querying against, prior to each
	 * fragment/segment number.
	 */
	protected ContentName _baseName = null;
	protected PublisherKeyID _publisher = null;
	protected Integer _startingBlockIndex = null; // if null, start with lowest available
	
	protected ContentName _headerName = null;
	/**
	 * The content authenticator associated with the 
	 * corresponding header information. We only need
	 * the publisher ID and the root object content digest,
	 * but might want to have access to the other
	 * authentication information.
	 */
	protected ContentAuthenticator _headerAuthenticator = null;
	
	/**
	 * The header information for that object, once
	 * we've read it. 
	 */
	protected Header _header = null;
	
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
	
	protected int _timeout = CCNBase.NO_TIMEOUT;

	public CCNInputStream(ContentName name, Integer startingBlockIndex, PublisherKeyID publisher, CCNLibrary library) throws XMLStreamException, IOException, InterruptedException {
		if (null == name)
			throw new IllegalArgumentException("Name cannot be null!");
		
		_library = library; 
		if (null == _library) {
			_library = CCNLibrary.getLibrary();
		}
		_publisher = publisher;	
		
		// So, we assume the name we get in is up to but not including the sequence
		// numbers, whatever they happen to be. If a starting block is given, we
		// open from there, otherwise we open from the leftmost number available.
		// We assume by the time you've called this, you have a specific version or
		// whatever you want to open -- this doesn't crawl versions. If you have fragmented
		// content, the baseName needs the fragment marker on it.
		_baseName = name;
		_startingBlockIndex = startingBlockIndex;
		
		// Asynchronously attempt to retrieve a header block, if one exists.
		retrieveHeader(_baseName, (null != publisher) ? new PublisherID(publisher) : null);
	}
	
	public CCNInputStream(ContentName name, PublisherKeyID publisher, CCNLibrary library) throws XMLStreamException, IOException, InterruptedException {
		this(name, null, publisher, library);
	}
	
	public CCNInputStream(ContentName name) throws XMLStreamException, IOException, InterruptedException {
		this(name, null, null, null);
	}
	
	public CCNInputStream(ContentName name, int blockNumber) throws XMLStreamException, IOException, InterruptedException {
		this(name, blockNumber, null, null);
	}
	
	public void setTimeout(int timeout) {
		_timeout = timeout;
	}

	@Override
	public int available() throws IOException {
		if (null != _header) {
			return (int)(_header.length() - (blockIndex()-_header.start())*_header.blockSize() - _blockOffset);
		} else if (null != _currentBlock) {
			return _currentBlock.content().length - _blockOffset;
		}
		return 0; /* unknown */
	}
		
	@Override
	public void close() throws IOException {
		// don't have to do anything.
	}

	@Override
	public synchronized void mark(int readlimit) {
		_readlimit = readlimit;
		_markBlock = blockIndex();
		_markOffset = _blockOffset;
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public int read() throws IOException {
		byte [] b = new byte[1];
		read(b, 0, 1);
		return b[0];
	}
 
	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	/**
	 * Reads from this object into buf. Starts at the current position in
	 * the object. By default, that starts at the beginning of the object (byte 0)
	 * after open, and subsequent calls to read pick up where the last left off
	 * (analogous to fread), but it can be moved by calling seek.
	 * 
	 * DKS TODO cope with int/long problem in System.arraycopy here and in write. 
	 * @param buf the buffer into which to write.
	 * @param offset the offset into buf at which to write data
	 * @param len the number of bytes to write
	 * @return
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	@Override
	public int read(byte[] buf, int offset, int len) throws IOException {
		
		if (null == buf)
			throw new NullPointerException("Buffer cannot be null!");
		
		return readInternal(buf, offset, len);
	}
	
	protected int readInternal(byte [] buf, int offset, int len) throws IOException {
		
		if (_atEOF)
			return 0;
				
		// is this the first block?
		if (null == _currentBlock) {
			_currentBlock = getFirstBlock();
			_blockOffset = 0;
			if (null == _currentBlock)
				return 0; // nothing to read
		} 
		
		// Now we have a block in place. Read from it. If we run out of block before
		// we've read len bytes, pull next block.
		int lenToRead = len;
		int lenRead = 0;
		while (lenToRead > 0) {
			if (_blockOffset >= _currentBlock.content().length) {
				// DKS make sure we don't miss a byte...
				_currentBlock = getNextBlock();
				_blockOffset = 0;
				if (null == _currentBlock) {
					_atEOF = true;
					return lenRead;
				}
			}
			long readCount = ((_currentBlock.content().length - _blockOffset) > lenToRead) ? lenToRead : (_currentBlock.content().length - _blockOffset);
			if (null != buf) { // use for skip
				System.arraycopy(_currentBlock.content(), (int)_blockOffset, buf, (int)offset, (int)readCount);
			}
			_blockOffset += readCount;
			offset += readCount;
			lenToRead -= readCount;
			lenRead += readCount;
		}
		return lenRead;
	}

	@Override
	public synchronized void reset() throws IOException {
		getBlock(_markBlock);
		_blockOffset = _markOffset;
	}
	
	@Override
	public long skip(long n) throws IOException {
		if (n < 0)
			return 0;
		
		if (null != _header) {
			// Calculate where to go to.
			int blockIndex = blockIndex();
			long blockOffset = n-((null == _currentBlock) ? 0 : (_currentBlock.content().length-_blockOffset));
			
			int blocksToSkip = (int)(1.0*blockOffset/_header.blockSize());
			
			if (_header.count()-blockIndex < blocksToSkip) {
				blocksToSkip = _header.count() - blockIndex - 1;
				_atEOF = true;
			}
			
			blockOffset -= blocksToSkip*_header.blockSize();
			
			getBlock(blockIndex + blocksToSkip);
			
			if (blockOffset < _currentBlock.content().length) {
				_blockOffset = (int)blockOffset;
				blockOffset = 0;
			} else {
				_blockOffset = _currentBlock.content().length;
				blockOffset -= _currentBlock.content().length;				
			}
			return n-blockOffset;
		} else {
			return read(null, 0, (int)n);
		}
	}
	
	protected void retrieveHeader(ContentName baseName, PublisherID publisher) throws IOException {
		Interest headerInterest = new Interest(CCNLibrary.headerName(baseName), publisher);
		headerInterest.additionalNameComponents(1);
		Library.logger().info("retrieveHeader: base name " + baseName);
		Library.logger().info("retrieveHeader: header name " + CCNLibrary.headerName(baseName));
		_library.expressInterest(headerInterest, this);
	}

	public Interest handleContent(ArrayList<ContentObject> results,
								  Interest interest) {
		// This gives us back the header.
		for (ContentObject co : results) {
			Library.logger().info("CCNInputStream: retrieved header: " + co.name() + " type: " + co.authenticator().typeName());
			if (null != _header) {
				continue;
			} else if (co.authenticator().type() == ContentAuthenticator.ContentType.HEADER) {
				// First we verify. (Or should get have done this for us?)
				// We don't bother complaining unless we have more than one
				// header that matches. Given that we would complain for
				// that, we need an enumerate that operates at this level.)
					// TODO: DKS: should this be header.verify()?
					// Need low-level verify as well as high-level verify...
					// Low-level verify just checks that signer actually signed.
					// High-level verify checks trust.
				try {
					if (!_library.verify(co, null)) {
						Library.logger().warning("Found header: " + co.name().toString() + " that fails to verify.");
						return interest; // try again
					} else {
						_headerName = co.name();
						_headerAuthenticator = co.authenticator();
						_header = new Header();
						// DKS TODO -- this will swap around when headers become subclasses of content object
						_header.decode(co.content());
						return null; // done
					}
				} catch (Exception e) {
					Library.logger().warning("Got an " + e.getClass().getName() + " exception attempting to verify or decode header: " + co.name().toString() + ", treat as failure to verify.");
					Library.warningStackTrace(e);
					return interest; // try again
				}
			}
		}
		if (null == _header)
			return interest;
		return null;
	}
	
	/**
	 * Three navigation options: get first (leftmost) block, get next block,
	 * or get a specific block.
	 **/
	protected ContentObject getBlock(int number) throws IOException {
		
		if (null != _header) {
			// Return null if we go past the end.
			if (number < _header.start()) 
				throw new IOException("Illegal block number " + number + " below initial value " + _header.start() + ".");
		
			if (number >= (_header.start() + _header.count())) {
				// Past the last block.
				Library.logger().info("Seek past the last block: " + number + " asked for, maximum available is: " + _header.start()+_header.count());
				return null;
			}
		}

		ContentName blockName = new ContentName(_baseName, ContentName.componentParseNative(Integer.toString(number)));

		Library.logger().info("getBlock: getting block " + blockName);
		/*
		 * TODO: Paul R. Comment - as above what to do about timeouts?
		 */
		ContentObject block;
		try {
			block = _library.getNextLevel(blockName, _timeout);
		} catch (InterruptedException e) {
			throw new IOException("Interrupted retrieving block: " + blockName + ": " + e.getMessage());
		}
		
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
		try {
			Library.logger().info("getNextBlock: getting block after " + _currentBlock.name());
			return _library.getNext(_currentBlock, _currentBlock.name().count()-2, null, _timeout);
		} catch (InterruptedException e) {
			throw new IOException("Interrupted retrieving next block after: " + _currentBlock.name() + ": " + e.getMessage());
		}
	}
	
	protected ContentObject getFirstBlock() throws IOException {
		if (null != _startingBlockIndex) {
			return getBlock(_startingBlockIndex);
		}
		// DKS TODO FIX - use get left child; the following is a first stab at that.
		try {
			Library.logger().info("getFirstBlock: getting " + _baseName);
			ContentObject result =  _library.get(_baseName, _timeout);
			Library.logger().info("getFirstBlock: retrieved " + result.name());
			return result;
		} catch (InterruptedException e) {
			throw new IOException("Interrupted retrieving first block: " + _baseName + ": " + e.getMessage());
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
			if (null == block.signature().witness())
				return block.verify(null);
			
			// Compare to see whether this block matches the root signature we previously verified, if
			// not, verify and store the current signature.
			// We need to compute the proxy regardless.
			byte [] proxy = block.computeProxy();

			// OK, if we have an existing verified signature, and it matches this block's
			// signature, the proxy ought to match as well.
			if ((null != _verifiedRootSignature) || (Arrays.equals(_verifiedRootSignature, block.signature().signature()))) {
				if ((null == proxy) || (null == _verifiedProxy) || (!Arrays.equals(_verifiedProxy, proxy))) {
					Library.logger().warning("Found block: " + block.name().toString() + " whose digest fails to verify.");
				}
			} else {
				// Verifying a new block. See if the signature verifies, otherwise store the signature
				// and proxy.
				if (!ContentObject.verify(proxy, block.signature().signature(), block.authenticator(), block.signature().digestAlgorithm(), null)) {
					Library.logger().warning("Found block: " + block.name().toString() + " whose signature fails to verify.");		
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

	public int blockIndex() {
		if (null == _currentBlock)
			return 0;
		return (Integer.parseInt(ContentName.componentPrintNative(_currentBlock.name().component(_currentBlock.name().count()-1))));
	}

	protected int blockCount() {
		if (null == _header) {
			return 0;
		}
	
		return (int)(Math.ceil(1.0*_header.length()/_header.blockSize()));
	}
}

