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
import com.parc.ccn.security.crypto.ContentKeys;

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
			   PublisherPublicKeyDigest publisher, ContentKeys keys, CCNLibrary library) throws XMLStreamException, IOException, NoSuchAlgorithmException, NoSuchPaddingException {
		super(baseName, startingBlockIndex, keys, publisher, library);
		setTimeout(CCNBase.NO_TIMEOUT);
	}

	public CCNBlockInputStream(ContentName baseName, Long startingBlockIndex,
							   PublisherPublicKeyDigest publisher, CCNLibrary library) throws XMLStreamException, IOException, NoSuchAlgorithmException, NoSuchPaddingException {
		super(baseName, startingBlockIndex, null, publisher, library);
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
		super(starterBlock, null, library);
	}

	protected int readInternal(byte [] buf, int offset, int len) throws IOException {
		
		Library.logger().info("CCNBlockInputStream: reading " + len + " bytes into buffer of length " + 
				((null != buf) ? buf.length : "null") + " at offset " + offset);
		// is this the first block?
		if (null == _currentBlock) {
			setCurrentBlock(getFirstBlock());
			if (null == _currentBlock)
				return 0; // nothing to read
		} 
		
		// Now we have a block in place. Read from it. If we run out of block before
		// we've read len bytes, return what we read. On next read, pull next block.
		int remainingBytes = _blockReadStream.available();
		
		if (remainingBytes <= 0) {
			setCurrentBlock(getNextBlock());
			if (null == _currentBlock) {
				// in socket implementation, this would be EAGAIN
				return 0;
			}
			remainingBytes = _blockReadStream.available();
		}
		// Read minimum of remainder of this block and available buffer.
		long readCount = (remainingBytes > len) ? len : remainingBytes;
		if (null != buf) { // use for skip
			readCount = _blockReadStream.read(buf, offset, len);
		} else {
			readCount = _blockReadStream.skip(len);
		}
		Library.logger().info("CCNBlockInputStream: read " + readCount + " bytes from block " + _currentBlock.name());
		return (int)readCount;
	}
}
