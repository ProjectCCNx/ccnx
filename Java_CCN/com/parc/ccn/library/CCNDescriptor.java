package com.parc.ccn.library;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
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
	byte [] _verifiedRootSignature = null;
	
	/**
	 * Start out with an implementation in terms of
	 * get returning us a block of data, rather than
	 * calling us back. Update as necessary. For
	 * languages other than Java might need to handle
	 * this a different way.
	 */
	ContentObject _currentBlock = null;
	long _readOffset = 0; // offset into current block
	
	/**
	 * Open header of existing object for reading. Eventually might
	 * want to not start from header, and might need to make this
	 * constructor also work for write for open.
	 * @param headerObject
	 * @param verified
	 * @throws XMLStreamException if the object is not a valid Header
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	public CCNDescriptor(CompleteName name,
						 CCNLibrary library) throws XMLStreamException, IOException, InterruptedException {
		_library = library; 
		if (null == _library) {
			throw new IllegalArgumentException("Unexpected null library in CCNDescriptor constructor!");
		}

		ContentName nameToOpen = name.name();
		if (StandardCCNLibrary.isFragment(name.name())) {
			// DKS TODO: should we do this?
			nameToOpen = StandardCCNLibrary.fragmentRoot(nameToOpen);
		}
		if (!_library.isVersioned(nameToOpen)) {
			// if publisherID is null, will get any publisher
			nameToOpen = 
				_library.getLatestVersionName(nameToOpen, 
									 name.authenticator().publisherKeyID());
		}
		
		// Should have name of root of version we want to
		// open. Get the header block. Already stripped to
		// root. We've altered the header semantics, so that
		// we can just get headers rather than a plethora of
		// fragments. 
		ContentName headerName = StandardCCNLibrary.headerName(nameToOpen);
		// This might not be unique - 
		// we could have here either multiple versions of
		// a given number, or multiple of a given number
		// by a given publisher. If the latter, pick by latest
		// after verifying. If the former, pick by latest
		// version crossed with trust.
		// DKS TODO figure out how to intermix trust information.
		// DKS TODO -- overloaded authenticator as query;
		// doesn't work well - would have to check that right things
		// are asked.
		// DKS TODO -- does get itself do a certain amount of
		// prefiltering? Can it mark objects as verified?
		// do we want to use the low-level get, as the high-level
		// one might unfragment?
		ArrayList<ContentObject> headers = _library.get(headerName, name.authenticator(),false);
		
		if ((null == headers) || (headers.size() == 0)) {
			Library.logger().info("No available content named: " + headerName.toString());
			throw new FileNotFoundException("No available content named: " + headerName.toString());
		}
		// So for each header, we assume we have a potential document.
		
		// First we verify. (Or should get have done this for us?)
		// We don't bother complaining unless we have more than one
		// header that matches. Given that we would complain for
		// that, we need an enumerate that operates at this level.)
		Iterator<ContentObject> headerIt = headers.iterator();
		while (headerIt.hasNext()) {
			ContentObject header = headerIt.next();
			// TODO: DKS: should this be header.verify()?
			// Need low-level verify as well as high-level verify...
			// Low-level verify just checks that signer actually signed.
			// High-level verify checks trust.
			try {
				if (!_library.verify(header, null)) {
					Library.logger().warning("Found header: " + header.name().toString() + " that fails to verify.");
					headerIt.remove();
				}
			} catch (Exception e) {
				Library.logger().warning("Got an " + e.getClass().getName() + " exception attempting to verify header: " + header.name().toString() + ", treat as failure to verify.");
				Library.warningStackTrace(e);
				headerIt.remove();
			}
		}
		if (headers.size() == 0) {
			Library.logger().info("No available verifiable content named: " + headerName.toString());
			throw new FileNotFoundException("No available verifiable content named: " + headerName.toString());
		}
		if (headers.size() > 1) {
			Library.logger().info("Found " + headers.size() + " headers matching the name: " + headerName.toString());
			throw new IOException("CCNException: More than one (" + headers.size() + ") valid header found for name: " + headerName.toString() + " in open!");
		}
		
		ContentObject headerObject = headers.get(0);
		
		if (headerObject == null) {
			Library.logger().info("Found only null headers matching the name: " + headerName.toString());
			throw new IOException("CCNException: No non-null header found for name: " + headerName.toString() + " in open!");
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
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	public long read(byte[] buf, long offset, long len) throws IOException, InterruptedException {
		
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
				result = seek(StandardCCNLibrary.getFragmentNumber(_currentBlock.name())+1);
			}
			long readCount = ((_currentBlock.content().length - _readOffset) > len) ? len : (_currentBlock.content().length - _readOffset);
			System.arraycopy(_currentBlock.content(), (int)_readOffset, buf, (int)offset, (int)readCount);
			_readOffset += readCount;
			offset += readCount;
			lenToRead -= readCount;
		}
		return 0;
	}
	
	/**
	 * Support ideas of seek, etc, even if fuse doesn't
	 * require them. Seek actually does a get on the appropriate content block.
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	public int seek(long offset, Enum<SeekWhence> whence) throws IOException, InterruptedException {
		// Assumption that block indices begin at 0.
		if (SeekWhence.SEEK_END == whence) {
			
		} else if ((SeekWhence.SEEK_SET == whence) || (null == _currentBlock)) {
			// if _currentBlock is null, we are at the beginning regardless of
			// whether we were asked for SEEK_CUR
			
		} else {
			// SEEK_CUR
			// how many bytes are left in this block?
			long bytesRemaining = _currentBlock.content().length - _readOffset;
			offset -= bytesRemaining;
			
			int blockIncrement = (int)Math.floor(offset/_header.blockSize());
			
			int thisBlock = StandardCCNLibrary.getFragmentNumber(_currentBlock.name());
			
			seek(thisBlock+blockIncrement);
			_readOffset += offset % _header.blockSize();
		}
		return 0;
	}
	
	protected int seek(int blockNumber) throws IOException, InterruptedException {
		_currentBlock = getBlock(blockNumber);
		_readOffset = 0;
		return 0;
	}
	
	public long tell() {
		if (null == _currentBlock)
			return 0;
		return ((_header.blockSize() * StandardCCNLibrary.getFragmentNumber(_currentBlock.name())) + _readOffset);
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
					Library.logger().warning("Found block: " + block.name().toString() + " whose digest fails to verify.");
					blockIt.remove();
				}
				// Compare to see whether this block matches the root signature we previously verified, if
				// not, verify and store the current signature.
				if ((null == _verifiedRootSignature) || (!Arrays.equals(_verifiedRootSignature, block.signature()))) {
					if (!ContentObject.verifyContentSignature(block.name(), block.authenticator(), block.signature(), null)) {
						Library.logger().warning("Found block: " + block.name().toString() + " whose signature fails to verify.");
						blockIt.remove();
					} else {
						_verifiedRootSignature = block.signature();
					}
				} // otherwise, it matches previously verified signature.
				
			} catch (Exception e) {
				Library.logger().warning("Got an " + e.getClass().getName() + " exception attempting to verify block: " + block.name().toString() + ", treat as failure to verify.");
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
		return blocks.get(0);
	}
}
