package com.parc.ccn.library.io;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.PublisherKeyID;
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

	public CCNBlockInputStream(ContentName baseName, Integer startingBlockIndex, 
							   PublisherKeyID publisher, CCNLibrary library) throws XMLStreamException, IOException {
		super(baseName, startingBlockIndex, publisher, library);
		setTimeout(CCNBase.NO_TIMEOUT);
	}

	public CCNBlockInputStream(ContentName baseName, PublisherKeyID publisher, CCNLibrary library) 
															throws XMLStreamException, IOException {
		this(baseName, null, publisher, library);
	}

	public CCNBlockInputStream(ContentName baseName) throws XMLStreamException, IOException {
		this(baseName, null, null, null);
	}

	public CCNBlockInputStream(ContentName baseName, CCNLibrary library) throws XMLStreamException, IOException {
		this(baseName, null, null, library);
	}

	public CCNBlockInputStream(ContentName baseName, int blockNumber) throws XMLStreamException, IOException {
		this(baseName, blockNumber, null, null);
	}

	protected int readInternal(byte [] buf, int offset, int len) throws IOException {
		
		Library.logger().info("CCNInputStream: reading " + len + " bytes into buffer of length " + 
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
		int remainingBytes = _currentBlock.content().length - _blockOffset;
		
		if (remainingBytes <= 0) {
			_currentBlock = getNextBlock();
			_blockOffset = 0;
			if (null == _currentBlock) {
				// in socket implementation, this would be EAGAIN
				return 0;
			}
			remainingBytes = _currentBlock.content().length;
		}
		// Read minimum of remainder of this block and available buffer.
		int readCount = (remainingBytes > len) ? len : remainingBytes;
		if (null != buf) { // use for skip
			System.arraycopy(_currentBlock.content(), _blockOffset, buf, offset, readCount);
		}
		_blockOffset += readCount;
		return readCount;
	}
}
