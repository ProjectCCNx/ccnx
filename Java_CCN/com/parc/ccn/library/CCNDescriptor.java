package com.parc.ccn.library;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.security.ContentAuthenticator;

/**
 * An object which contains state operation for 
 * multi-block (multi-name) operations, such as
 * traditional file system-style read and write
 * operations. First pass implementation considers
 * a given piece of content to consist of a 
 * header together with some number of blocks.
 * Initially implemented for requirements of read;
 * write requirements more complex and will be
 * added in. This is also currently optimized
 * for straight reads; should also implement seek.
 * 
 * This object uses the library functions to do verification
 * and build trust.
 * @author smetters
 *
 */
public class CCNDescriptor {
	
	public enum SeekWhence {SEEK_SET, SEEK_CUR, SEEK_END};
	
	CCNLibrary _library = null;
	
	/**
	 * The base name of the object we are actually
	 * reading or writing.
	 */
	ContentName _baseName = null;

	/**
	 * The content authenticator associated with the 
	 * corresponding header information. We only need
	 * the publisher ID and the root object content digest,
	 * but might want to have access to the other
	 * authentication information.
	 */
	ContentAuthenticator _headerAuthenticator = null;
	
	/**
	 * The header information for that object, once
	 * we've read it. 
	 */
	Header _header = null;
	/**
	 * We need some state information about what
	 * we've verified, assuming that the authentication
	 * structures for this object span all or sets of
	 * blocks. Start with the assumption that the signature
	 * spans all blocks. (Revise later.)
	 * Can also get this by verifying the header, and 
	 * checking that the header contains the same root.
	 */
	boolean _rootSignatureVerified = false;
	
	/**
	 * Start out with an implementation in terms of
	 * get returning us a block of data, rather than
	 * calling us back. Update as necessary. For
	 * languages other than Java might need to handle
	 * this a different way.
	 */
	ContentObject _currentBlock = null;
	long _readOffset = 0; // offset into current block
	boolean _currentBlockVerified = false;
	
	/**
	 * Right now, take callers word that they already
	 * verified this object. Verify blocks with respect
	 * to the information in the header. We could instead
	 * verify in the constructor and except if fail; letting
	 * callers use the constructor to verify.
	 * @param headerObject
	 * @param verified
	 * @throws XMLStreamException if the object is not a valid Header
	 */
	public CCNDescriptor(ContentObject headerObject, 
						 boolean verified,
						 CCNLibrary library) throws XMLStreamException {
		_library = library; 
		if (null == _library) {
			throw new IllegalArgumentException("Unexpected null library in CCNDescriptor constructor!");
		}

		_headerAuthenticator = headerObject.authenticator();
		_baseName = StandardCCNLibrary.headerRoot(headerObject.name());
		
		_header = new Header();
		_header.decode(headerObject.content());
	}
	
	/**
	 * Reads from this object into buf. Starts at the current position in
	 * the object. By default, that starts at the beginning of the object (byte 0)
	 * after open, and subsequent calls to read pick up where the last left off
	 * (analogous to fread), but it can be moved by calling seek.
	 * @param buf the buffer into which to write.
	 * @param offset the offset into buf at which to write data
	 * @param len the number of bytes to write
	 * @return
	 */
	public long read(byte[] buf, long offset, long len) {
		int result = 0;
		
		// is this the first block?
		if (null == _currentBlock) {
			// Sets _currentBlock and _readOffset.
			result = seek(0);
			if (result != 0) {
				return result; // errno analogy?
			}
		} 
		
		// Now we have a block in place. Read from it. If we run out of block before
		// we've read len bytes, pull next block.
		long lenToRead = len;
		while (lenToRead > 0) {
			if (_readOffset >= _currentBlock.content().length) {
				// DKS make sure we don't miss a byte...
				result = seek(StandardCCNLibrary.getFragment(_currentBlock.name())+1);
			}
		}
		return 0;
	}
	
	/**
	 * Support ideas of seek, etc, even if fuse doesn't
	 * require them. Seek actually does a get on the appropriate content block.
	 */
	public int seek(long offset, Enum<SeekWhence> whence) {
		return 0;
	}
	
	protected int seek(int blockNumber) {
		_readOffset = 0;
		return 0;
	}
	
	public long tell() {
		return 0;
	}
	
	protected ContentObject getBlock(int number) throws IOException, InterruptedException {
		
		ContentName blockName = StandardCCNLibrary.fragmentName(_baseName, number);

		ArrayList<ContentObject> blocks = _library.get(blockName, _headerAuthenticator, true);
		
		if ((null == blocks) || (blocks.size() == 0)) {
			Library.logger().info("Cannot get block " + number + " of file " + _baseName + " expected block: " + blockName.toString());
			throw new IOException("Cannot get block " + number + " of file " + _baseName + " expected block: " + blockName.toString());
		}
		// So for each header, we assume we have a potential document.
		
		// First we verify. (Or should get have done this for us?)
		// We don't bother complaining unless we have more than one
		// header that matches. Given that we would complain for
		// that, we need an enumerate that operates at this level.)
		Iterator<ContentObject> blockIt = blocks.iterator();
		while (blockIt.hasNext()) {
			ContentObject block = blockIt.next();
			// TODO: DKS: should this be header.verify()?
			// Need low-level verify as well as high-level verify...
			// Low-level verify just checks that signer actually signed.
			// High-level verify checks trust.
			try {
				if (!ContentObject.verifyContentDigest(block.authenticator().contentDigest(), block.content())) {
					Library.logger().warning("Found block: " + block.name().toString() + " that fails to verify.");
					blockIt.remove();
				}
				// Compare to see whether this block matches the root signature we previously verified, if
				// not, verify and store the current signature.
				// DKS TODO -- and move original verification inside constructor
				//if ()
			} catch (Exception e) {
				Library.logger().warning("Got an " + e.getClass().getName() + " exception attempting to verify header: " + block.name().toString() + ", treat as failure to verify.");
				Library.warningStackTrace(e);
				blockIt.remove();
			}
		}
		if (blocks.size() == 0) {
			Library.logger().info("No available verifiable content named: " + blockName.toString());
			throw new FileNotFoundException("No available verifiable content named: " + blockName.toString());
		}
		if (blocks.size() > 1) {
			Library.logger().info("Found " + blocks.size() + " blocks matching the name: " + blockName.toString());
			throw new IOException("CCNException: More than one (" + blocks.size() + ") valid block found for name: " + blockName.toString() + " in open!");
		}
		if (blocks.get(0) == null) {
			Library.logger().info("Found only null blocks matching the name: " + blockName.toString());
			throw new IOException("CCNException: No non-null blocks found for name: " + blockName.toString() + " in open!");
		}
		return null;
	}
}
