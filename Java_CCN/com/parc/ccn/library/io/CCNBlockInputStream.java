package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.NoSuchPaddingException;
import javax.xml.stream.XMLStreamException;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;

/**
 * This input stream expects to do packet-oriented reading of
 * fixed chunks. The chunks can be individually signed or authenticated
 * using a Merkle Hash Tree, but read will return when it gets a single
 * block of content, and will not fill buffers across content blocks.
 * The intent is to read packet-oriented protocols; possibly a better
 * abstraction is to move this to be a subclass of DatagramSocket.
 */
public class CCNBlockInputStream extends CCNAbstractInputStream {

	public CCNBlockInputStream(ContentName baseName, Long startingBlockIndex, 
							   PublisherPublicKeyDigest publisher, CCNLibrary library) throws XMLStreamException, IOException, NoSuchAlgorithmException, NoSuchPaddingException {
		super(baseName, startingBlockIndex, null, null, null, publisher, library);
		setTimeout(CCNBase.NO_TIMEOUT);
	}

	public CCNBlockInputStream(ContentName baseName, PublisherPublicKeyDigest publisher, CCNLibrary library) 
															throws XMLStreamException, IOException, NoSuchAlgorithmException, NoSuchPaddingException {
		this(baseName, null, publisher, library);
	}

	public CCNBlockInputStream(ContentName baseName) throws XMLStreamException, IOException, NoSuchAlgorithmException, NoSuchPaddingException {
		this(baseName, null, null, null);
	}

	public CCNBlockInputStream(ContentName baseName, CCNLibrary library) throws XMLStreamException, IOException, NoSuchAlgorithmException, NoSuchPaddingException {
		this(baseName, null, null, library);
	}

	public CCNBlockInputStream(ContentName baseName, long blockNumber) throws XMLStreamException, IOException, NoSuchAlgorithmException, NoSuchPaddingException {
		this(baseName, blockNumber, null, null);
	}
	
	public CCNBlockInputStream(ContentObject starterBlock, CCNLibrary library) throws XMLStreamException, IOException, NoSuchAlgorithmException, NoSuchPaddingException {
		super(starterBlock, null, null, null, library);
	}

	protected int readInternal(byte [] buf, int offset, int len) throws IOException {
		
		Library.logger().info("CCNBlockInputStream: reading " + len + " bytes into buffer of length " + 
				((null != buf) ? buf.length : "null") + " at offset " + offset);
		// is this the first block?
		if (null == _currentBlock) {
			_currentBlock = getFirstBlock();
			_blockOffset = 0;
			if (null == _currentBlock)
				return 0; // nothing to read
		} 
		
		// Now we have a block in place. Read from it. If we run out of block before
		// we've read len bytes, return what we read. On next read, pull next block.
		int remainingBytes = _currentBlock.contentLength() - _blockOffset;
		
		if (remainingBytes <= 0) {
			_currentBlock = getNextBlock();
			_blockOffset = 0;
			if (null == _currentBlock) {
				// in socket implementation, this would be EAGAIN
				return 0;
			}
			remainingBytes = _currentBlock.contentLength();
		}
		// Read minimum of remainder of this block and available buffer.
		int readCount = (remainingBytes > len) ? len : remainingBytes;
		if (null != buf) { // use for skip
			System.arraycopy(_currentBlock.content(), _blockOffset, buf, offset, readCount);
		}
		_blockOffset += readCount;
		Library.logger().info("CCNBlockInputStream: read " + readCount + " bytes from block " + _currentBlock.name());
		return readCount;
	}
}
