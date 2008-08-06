package com.parc.ccn.library;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;
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
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary.OpenMode;
import com.parc.ccn.security.crypto.CCNMerkleTree;
import com.parc.ccn.security.crypto.DigestHelper;

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
	
	/**
	 * Maximum number of blocks we keep around before we build a
	 * Merkle tree and flush. Should be based on lengths of merkle
	 * paths and signatures and so on.
	 */
	protected static final int BLOCK_BUF_COUNT = 128;
	
	public enum SeekWhence {SEEK_SET, SEEK_CUR, SEEK_END};
	
	protected StandardCCNLibrary _library = null;
	
	protected OpenMode _mode = null;
	
	/**
	 * The base name of the object we are actually
	 * reading or writing.
	 */
	protected ContentName _headerName = null;

	protected ContentName _baseName = null;
	
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
	 * We need some state information about what
	 * we've verified, assuming that the authentication
	 * structures for this object span all or sets of
	 * blocks. Start with the assumption that the signature
	 * spans all blocks. (Revise later.)
	 * Can also get this by verifying the header, and 
	 * checking that the header contains the same root.
	 * The proxy is the thing that is actually signed,
	 * e.g. the digest(?) of the content, or the Merkle
	 * tree root or whatever. The first block of a new
	 * tree, we need to compute the root, verify the
	 * signature, and then remember the root and the
	 * signature. For each subsequent block, if the
	 * signature matches, we merely need to determine
	 * whether the witness computes to the same proxy.
	 * 
	 * DKS NOTE: witness doesn't compute over the content
	 * proxy. It computes over the digest of the name,
	 * content authenticator, and content proxy -- same
	 * thing that would be used to verify the signature on
	 * a block.
	 */
	protected byte [] _verifiedRootSignature = null;
	protected byte [] _verifiedProxy = null;
	
	/**
	 * Write data
	 */
	long _totalLength = 0;
	
	/**
	 * Start out with an implementation in terms of
	 * get returning us a block of data, rather than
	 * calling us back. Update as necessary. For
	 * languages other than Java might need to handle
	 * this a different way.
	 */
	protected ContentObject _currentBlock = null;
	protected int _blockOffset = 0; // read/write offset into current block
	protected int _blockIndex = 0; // index into array of block buffers
	protected byte [][] _blockBuffers = null;
	protected int _baseBlockIndex; // base index of current set of block buffers.
	
	protected Timestamp _timestamp; // timestamp we use for writing, set to first time we write
	
	protected PublisherKeyID _publisher;
	protected KeyLocator _locator;
	protected PrivateKey _signingKey;

	protected ContentAuthenticator.ContentType _type;
	
	protected DigestHelper _dh;
	
	protected ArrayList<byte []> _roots = new ArrayList<byte[]>();

	/**
	 * Open header of existing object for reading, or prepare new
	 * name for writing. Eventually might
	 * want to not start from header. If name is not versioned,
	 * figures out either the latest version to open (read)
	 * or next version to write (write).
	 * @param mode 
	 * @param headerObject
	 * @param verified
	 * @throws XMLStreamException if the object is not a valid Header
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	public CCNDescriptor(CompleteName name,
						 OpenMode mode, 
						 PublisherKeyID publisher,
						 KeyLocator locator,
						 PrivateKey signingKey,
						 StandardCCNLibrary library) throws XMLStreamException, IOException, InterruptedException {
		_library = library; 
		if (null == _library) {
			throw new IllegalArgumentException("Unexpected null library in CCNDescriptor constructor!");
		}

		_mode = mode;
		_publisher = publisher;
		_locator = locator;
		_signingKey = signingKey;
		
		if (_mode == OpenMode.O_RDONLY)
			openForReading(name);
		else if (_mode == OpenMode.O_WRONLY)
			openForWriting(name);
		
	}
	
	public CCNDescriptor(CompleteName name,
			 OpenMode mode, StandardCCNLibrary library) throws XMLStreamException, IOException, InterruptedException {
		this(name, mode, null, null, null, library);
	}

		
	protected void openForWriting(CompleteName name) {
		ContentName nameToOpen = name.name();
		if (StandardCCNLibrary.isFragment(name.name())) {
			// DKS TODO: should we do this?
			nameToOpen = StandardCCNLibrary.fragmentRoot(nameToOpen);
		}
		
		if (null != name.authenticator()) {
			_type = name.authenticator().type();
		} else {
			_type = ContentAuthenticator.ContentType.LEAF;
		}
		
		// Assume if name is already versioned, caller knows what name
		// to write. If caller specifies authentication information,
		// ignore it for now.
		if (!_library.isVersioned(nameToOpen)) {
			// if publisherID is null, will get any publisher
			ContentName currentVersionName = 
				_library.getLatestVersionName(nameToOpen, null);
			if (null == currentVersionName) {
				nameToOpen = _library.versionName(nameToOpen, StandardCCNLibrary.baseVersion());
			} else {
				nameToOpen = _library.versionName(currentVersionName, (_library.getVersionNumber(currentVersionName) + 1));
			}
		}
		// Should have name of root of version we want to
		// open. Get the header block. Already stripped to
		// root. We've altered the header semantics, so that
		// we can just get headers rather than a plethora of
		// fragments. 
		_baseName = nameToOpen;
		_headerName = StandardCCNLibrary.headerName(_baseName);
		
		_blockBuffers = new byte[BLOCK_BUF_COUNT][];
		_baseBlockIndex = StandardCCNLibrary.baseFragment();
		
		_dh = new DigestHelper();
	}
	
	/**
	 * DKS TODO: Needs to handle both single-put and fragmented content.
	 * @param name
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws XMLStreamException
	 */
	protected void openForReading(CompleteName name) throws IOException, InterruptedException, XMLStreamException {

		ContentName nameToOpen = name.name();
		if (StandardCCNLibrary.isFragment(name.name())) {
			// DKS TODO: should we do this?
			nameToOpen = StandardCCNLibrary.fragmentRoot(nameToOpen);
		}
		if (!_library.isVersioned(nameToOpen)) {
			// if publisherID is null, will get any publisher
			nameToOpen = 
				_library.getLatestVersionName(nameToOpen,
							(null != name.authenticator()) ? 
									 name.authenticator().publisherKeyID() : null);
		}
		// Should have name of root of version we want to
		// open. Get the header block. Already stripped to
		// root. We've altered the header semantics, so that
		// we can just get headers rather than a plethora of
		// fragments. 
		Library.logger().fine("Opening header for " + name.name() + " at " + StandardCCNLibrary.headerName(nameToOpen));
		_headerName = StandardCCNLibrary.headerName(nameToOpen);
		
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
		ArrayList<ContentObject> headers = _library.get(_headerName, name.authenticator(), false);
		
		if ((null == headers) || (headers.size() == 0)) {
			Library.logger().info("No available content named: " + _headerName.toString());
			throw new FileNotFoundException("No available content named: " + _headerName.toString());
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
			Library.logger().info("No available verifiable content named: " + _headerName.toString());
			throw new FileNotFoundException("No available verifiable content named: " + _headerName.toString());
		}
		if (headers.size() > 1) {
			Library.logger().info("Found " + headers.size() + " headers matching the name: " + _headerName.toString());
			throw new IOException("CCNException: More than one (" + headers.size() + ") valid header found for name: " + _headerName.toString() + " in open!");
		}
		
		ContentObject headerObject = headers.get(0);
		
		if (headerObject == null) {
			Library.logger().info("Found only null headers matching the name: " + _headerName.toString());
			throw new IOException("CCNException: No non-null header found for name: " + _headerName.toString() + " in open!");
		}
		
		_headerAuthenticator = headerObject.authenticator();
		_baseName = StandardCCNLibrary.headerRoot(headerObject.name());
		
		_header = new Header();
		_header.decode(headerObject.content());
		Library.logger().info("Opened " + headerObject.name() + " for reading. Total length: " + _header.length() + " blocksize: " + _header.blockSize() + " total blocks: " + blockCount());
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
	public long read(byte[] buf, long offset, long len) throws IOException, InterruptedException {
		
		if (!openForReading())
			throw new IOException("CCNDescriptor for content name: " + _baseName + " is not open for reading!");
		
		int result = 0;
		
		// is this the first block?
		if (null == _currentBlock) {
			// Sets _currentBlock and _readOffset.
			result = seek(0);
			if (result != 0) {
				return result; // errno analogy?
			}
			if (null == _currentBlock)
				return 0; // nothing to read
		} 
		
		// Now we have a block in place. Read from it. If we run out of block before
		// we've read len bytes, pull next block.
		long lenToRead = len;
		long lenRead = 0;
		while (lenToRead > 0) {
			if (_blockOffset >= _currentBlock.content().length) {
				// DKS make sure we don't miss a byte...
				result = seek(StandardCCNLibrary.getFragmentNumber(_currentBlock.name())+1);
				if (null == _currentBlock) {
					return lenRead;
				}
			}
			long readCount = ((_currentBlock.content().length - _blockOffset) > lenToRead) ? lenToRead : (_currentBlock.content().length - _blockOffset);
			System.arraycopy(_currentBlock.content(), (int)_blockOffset, buf, (int)offset, (int)readCount);
			_blockOffset += readCount;
			offset += readCount;
			lenToRead -= readCount;
			lenRead += readCount;
		}
		return lenRead;
	}
	
	public long write(byte[] buf, long offset, long len) throws IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException {
		if (!openForWriting())
			throw new IOException("CCNDescriptor for content name: " + _baseName + " is not open for writing!");

		if ((len <= 0) || (null == buf) || (buf.length == 0) || (offset >= buf.length))
			return 0;
		
		long bytesToWrite = len;
		
		while (bytesToWrite > 0) {
			if (_blockIndex >= BLOCK_BUF_COUNT) {
				Library.logger().fine("write: about to sync one tree's worth of blocks (" + BLOCK_BUF_COUNT +") to the network.");
				sync();
			}
		
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
				++_blockIndex;
				_blockOffset = 0;
			}
		}
		_totalLength += len;
		return 0;
	}

	public int close() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException {
		if (openForWriting()) {
			// Special case; if we don't need to fragment, don't. Can't
			// do this in sync(), as we can't tell a manual sync from a close.
			// Though that means a manual sync(); close(); on a short piece of
			// content would end up with unnecessary fragmentation...
			if ((_baseBlockIndex == StandardCCNLibrary.baseFragment()) && 
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
				sync();
				writeHeader();
			}
		}
		return 0;
	}

	public void sync() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException {
		// DKS TODO needs to cope with partial last block. 
		if (!openForWriting())
			return;
		
		if (null == _timestamp)
			_timestamp = ContentAuthenticator.now();
	
		Library.logger().finer("sync: putting merkle tree to the network, " + (_blockIndex+1) + " blocks.");
		// Generate Merkle tree (or other auth structure) and authenticators and put contents.
		CCNMerkleTree tree =
			_library.putMerkleTree(_baseName, _baseBlockIndex, _blockBuffers, _blockIndex+1, _baseBlockIndex,
								_timestamp, _publisher, _locator, _signingKey);
		_roots.add(tree.root());
		
		// Set contents of blocks to 0
		for (int i=0; i < _blockBuffers.length; ++i) {
			if (null != _blockBuffers[i])
				Arrays.fill(_blockBuffers[i], 0, _blockBuffers[i].length, (byte)0);
		}
		_baseBlockIndex += _blockIndex;
		_blockIndex = 0;
	}
	
	protected void writeHeader() throws InvalidKeyException, SignatureException, IOException, InterruptedException {
		// What do we put in the header if we have multiple merkle trees?
		_library.putHeader(_baseName, (int)_totalLength, _dh.digest(), 
				((_roots.size() > 0) ? _roots.get(0) : null),
				_type,
				_timestamp, _publisher, _locator, _signingKey);
		Library.logger().info("Wrote header: " + StandardCCNLibrary.headerName(_baseName));
	}
	
	public boolean openForReading() {
		return ((null != _mode) && (OpenMode.O_RDONLY == _mode));
	}

	public boolean openForWriting() {
		return ((null != _mode) && (OpenMode.O_WRONLY == _mode));
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
			long bytesRemaining = _currentBlock.content().length - _blockOffset;
			offset -= bytesRemaining;
			
			int blockIncrement = (int)Math.floor(offset/_header.blockSize());
			
			int thisBlock = StandardCCNLibrary.getFragmentNumber(_currentBlock.name());
			
			seek(thisBlock+blockIncrement);
			_blockOffset += offset % _header.blockSize();
		}
		return 0;
	}
	
	protected int seek(int blockNumber) throws IOException, InterruptedException {
		_currentBlock = getBlock(blockNumber);
		_blockOffset = 0;
		return 0;
	}
	
	public long tell() {
		if (null == _currentBlock)
			return 0;
		return ((_header.blockSize() * StandardCCNLibrary.getFragmentNumber(_currentBlock.name())) + _blockOffset);
	}
	
	protected ContentObject getBlock(int number) throws IOException, InterruptedException {
		
		// Return null if we go past the end.
		if (number < StandardCCNLibrary.baseFragment()) 
			throw new IOException("Illegal block number " + number + " below initial value " + StandardCCNLibrary.baseFragment() + ".");
		
		if (number >= (StandardCCNLibrary.baseFragment() + blockCount())) {
			// Past the last block.
			Library.logger().info("Seek past the last block: " + number + " asked for, count available is: " + StandardCCNLibrary.baseFragment() + blockCount());
			return null;
		}

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
				// Compare to see whether this block matches the root signature we previously verified, if
				// not, verify and store the current signature.
				// We need to compute the proxy regardless.
				byte [] proxy = block.computeProxy();

				// OK, if we have an existing verified signature, and it matches this block's
				// signature, the proxy ought to match as well.
				if ((null != _verifiedRootSignature) || (Arrays.equals(_verifiedRootSignature, block.signature().signature()))) {
					if ((null == proxy) || (null == _verifiedProxy) || (!Arrays.equals(_verifiedProxy, proxy))) {
						Library.logger().warning("Found block: " + block.name().toString() + " whose digest fails to verify.");
						blockIt.remove();
					}
				} else {
					// Verifying a new block. See if the signature verifies, otherwise store the signature
					// and proxy.
					if (!ContentObject.verify(proxy, block.signature().signature(), block.authenticator(), block.signature().digestAlgorithm(), null)) {
						Library.logger().warning("Found block: " + block.name().toString() + " whose signature fails to verify.");
						blockIt.remove();						
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

	protected int blockCount() {
		if (!openForReading())
			return (_blockIndex+1);
		
		if (null == _header) {
			return -1;
		}
		
		return (int)(Math.ceil(1.0*_header.length()/_header.blockSize()));
	}
}
