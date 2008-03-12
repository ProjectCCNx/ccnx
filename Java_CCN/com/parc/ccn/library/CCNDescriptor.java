package com.parc.ccn.library;

import javax.xml.stream.XMLStreamException;

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
						 boolean verified) throws XMLStreamException {
		_headerAuthenticator = headerObject.authenticator();
		_baseName = StandardCCNLibrary.headerRoot(headerObject.name());
		
		_header = new Header();
		_header.decode(headerObject.content());
	}
	
	/**
	 * Reads from this object into buf. Starts at the
	 * beginning of the object and continues. 
	 * @param buf the buffer into which to write.
	 * @param offset the offset into buf at which to write data
	 * @param len the number of bytes to write
	 * @return
	 */
	public long read(byte[] buf, long offset, long len) {
		
		// is this the first block?
		if (null == _currentBlock) {
			
		}
		return 0;
	}
	
	/**
	 * Support ideas of seek, etc, even if fuse doesn't
	 * require them.
	 */
	public int seek(long offset, Enum<SeekWhence> whence) {
		return 0;
	}
	
	public long tell() {
		return 0;
	}

}
