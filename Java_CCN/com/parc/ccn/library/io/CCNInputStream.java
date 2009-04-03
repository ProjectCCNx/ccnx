package com.parc.ccn.library.io;

import java.io.IOException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;

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
 * 
 * This input stream works with data with and without a header block; it
 * opportunistically queries for a header block and uses its information if
 * one is available. That means an extra interest for content that does not have
 * a header block.
 * 
 * Read size is independent of fragment size; the stream will pull additional
 * content fragments dynamically when possible to fill out the requested number
 * of bytes.
 * 
 * TODO change sequence numbers from strings to binary.
 * @author smetters
 *
 */
public class CCNInputStream extends CCNAbstractInputStream implements CCNInterestListener {
	
	protected boolean _atEOF = false;
	protected int _readlimit = 0;
	protected int _markOffset = 0;
	protected int _markBlock = 0;

	protected ContentName _headerName = null;
	/**
	 * The content signedInfo associated with the 
	 * corresponding header information. We only need
	 * the publisher ID and the root object content digest,
	 * but might want to have access to the other
	 * authentication information.
	 */
	protected SignedInfo _headerSignedInfo = null;
	
	/**
	 * The header information for that object, once
	 * we've read it. 
	 */
	protected Header _header = null;
	
	public CCNInputStream(ContentName name, Integer startingBlockIndex, PublisherKeyID publisher, 
			CCNLibrary library) throws XMLStreamException, IOException {

		super(name, startingBlockIndex, publisher, library);

		// Asynchronously attempt to retrieve a header block, if one exists.
		retrieveHeader(_baseName, (null != publisher) ? new PublisherID(publisher) : null);
	}
	
	public CCNInputStream(ContentName name, PublisherKeyID publisher, CCNLibrary library) 
			throws XMLStreamException, IOException {
		this(name, null, publisher, library);
	}
	
	public CCNInputStream(ContentName name) throws XMLStreamException, IOException {
		this(name, null, null, null);
	}
	
	public CCNInputStream(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, null, library);
	}
	
	public CCNInputStream(ContentName name, int blockNumber) throws XMLStreamException, IOException {
		this(name, blockNumber, null, null);
	}
	
	public CCNInputStream(ContentObject starterBlock, CCNLibrary library) throws XMLStreamException, IOException {
		super(starterBlock, library);
	}
	
	@Override
	public int available() throws IOException {
		
		if(_currentBlock!=null){
			return _currentBlock.content().length - _blockOffset;
		}
		else{
			return 0;
		}
			
		//int available = 0;
		//if (null != _header) {
		//	available =  (int)(_header.length() - blockIndex()*_header.blockSize() - _blockOffset);
		//	//available =  (int)(_header.length() - (blockIndex()-_header.start())*_header.blockSize() - _blockOffset);
		//} else if (null != _currentBlock) {
		//	available =  _currentBlock.content().length - _blockOffset;
		//}
		//Library.logger().info("available(): " + available);
		//return available; /* unknown */
	}
	
	public boolean eof() { 
		//Library.logger().info("Checking eof: there yet? " + _atEOF);
		return _atEOF; 
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

	protected int readInternal(byte [] buf, int offset, int len) throws IOException {
		
		if (_atEOF)
			return 0;
		
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
		// we've read len bytes, pull next block.
		int lenToRead = len;
		int lenRead = 0;
		while (lenToRead > 0) {
			if (_blockOffset >= _currentBlock.content().length) {
				// DKS make sure we don't miss a byte...
				_currentBlock = getNextBlock();
				_blockOffset = 0;
				if (null == _currentBlock) {
					Library.logger().info("next block was null, setting _atEOF");
					_atEOF = true;
					return lenRead;
				}
			}
			int readCount = ((_currentBlock.content().length - _blockOffset) > lenToRead) ? lenToRead : (_currentBlock.content().length - _blockOffset);
			if (null != buf) {} // use for skip
				Library.logger().info("before arraycopy: content length "+_currentBlock.content().length+" _blockOffset "+_blockOffset+" dst length "+buf.length+" dst index "+offset+" len to copy "+readCount);
				System.arraycopy(_currentBlock.content(), _blockOffset, buf, offset, readCount);
			
			_blockOffset += readCount;
			offset += readCount;
			lenToRead -= readCount;
			lenRead += readCount;
			Library.logger().info("     read " + readCount + " bytes for " + lenRead + " total, " + lenToRead + " remaining.");
			
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
		
		Library.logger().info("in skip("+n+")");
		
		if (n < 0)
			return 0;
		
		if(null==_header){
			return readInternal(null,0, (int)n);
		}
		
		int[] toGetBlockAndOffset = null;
		long toGetPosition = 0;
		
		int currentBlock = -1;
		int currentBlockOffset = 0;
		long currentPosition = 0;
		
		
		if(_currentBlock==null){
			//we do not have a block already
			//skip position is n
			currentPosition = 0;
			toGetPosition = n;
		}
		else{
		    //we already have a block...  need to handle some tricky cases
			currentBlock = blockIndex();
			currentBlockOffset = _blockOffset;
			currentPosition = _header.blockLocationToPosition(currentBlock, currentBlockOffset);
			toGetPosition = currentPosition + n;
		}
		//make sure we don't skip past end of the object
		if(toGetPosition >= _header.length()){
			toGetPosition = _header.length();
			_atEOF = true;
		}
			
		toGetBlockAndOffset = _header.positionToBlockLocation(toGetPosition);
		
		//make sure the position makes sense
		//is this a valid block?
		if(toGetBlockAndOffset[0] >= _header.blockCount()){
			//this is not a valid block number, subtract 1
			if(toGetBlockAndOffset[0] > 0)
				toGetBlockAndOffset[0]--;
			//now we have the last block if the position was too long
		}
		
		//is the offset > 0?
		if(toGetBlockAndOffset[1] < 0)
			toGetBlockAndOffset[1] = 0;
			
		//now we should get the block and check the offset
		getBlock(toGetBlockAndOffset[0]);
		if(_currentBlock==null){
			//we had an error getting the block
			throw new IOException("Error getting block "+toGetBlockAndOffset[0]+" in CCNInputStream.skip("+n+")");
		}
		else{
			//we have a valid block!
			//first make sure the offset is valid
			if(toGetBlockAndOffset[1] <= _currentBlock.content().length){
				//this is good, our offset is somewhere in this block
			}
			else{
				//our offset is past the end of our block, reset to the end.
				toGetBlockAndOffset[1] = _currentBlock.content().length;
			}
			_blockOffset = toGetBlockAndOffset[1];
			return _header.blockLocationToPosition(toGetBlockAndOffset[0], toGetBlockAndOffset[1]) - currentPosition;
		}
		
		/*
		 This is the second version of the code...  to be removed when above is tested
		
		
		if (null != _header) {
					
			
			// Calculate where to go to.
			int blockIndex = blockIndex();
			Library.logger().info("current block content length check: "+_currentBlock.content().length);
			//long blockOffset = n-((null == _currentBlock || currentBlockNumber() == 0) ? 0 : (_currentBlock.content().length-_blockOffset));
			//if(blockOffset < 0)
			//	blockOffset = _blockOffset;
			
			long blockOffset = 0;
			int blocksToSkip = 0;
			
			//Library.logger().info("Currently in block "+blockIndex+" with _blockOffset "+_blockOffset+" wanting to skip "+n+" bytes:  total blocks "+_header.count()+" total size "+_header.length());
			
			if(null == _currentBlock){
				//we do not have a current block, use the base offset
				Library.logger().info("do not have a current block...");
				_blockOffset = (int)n;
				return n;
			}
			else{
				Library.logger().info("Currently in block "+blockIndex+" with _blockOffset "+_blockOffset+" wanting to skip "+n+" bytes:  total blocks "+_header.count()+" total size "+_header.length());
				//we already have a block
				//need to check if we need to skip over a block (or more) completely
				if(_currentBlock.content().length - _blockOffset > n){
					//there are more bytes left in our current block then we want to skip	
					_blockOffset = _blockOffset + (int)n;
					return n;
				}
				else{
					//we want to skip more than we have left in our current block
					//will this cause us to go past the end of the content?
					//this assumes blocks start with 0
					int targetByte = _header.blockSize() * blockIndex + _blockOffset + (int)n;
					if(targetByte > _header.length()){
						long adjustedSkip = n - (targetByte - _header.length()); 
						//this would put us past the end...
						getBlock(_header.count() - 1);
						if(_currentBlock!=null)
							_blockOffset = _currentBlock.content().length;
						else
							_blockOffset = 0;
						Library.logger().info("setting eof");
						_atEOF = true;
					    return adjustedSkip;
					}
					else{
						//how many blocks do we need to skip?
						//first subtract out the remainder of this block
						blockOffset = n - (_currentBlock.content().length - _blockOffset);
						//now divide the rest to skip by the block size
						blocksToSkip = (int)(1.0*blockOffset/_header.blockSize());
						blockOffset = blockOffset - blocksToSkip*_header.blockSize();
						getBlock(blockIndex + blocksToSkip);
						if(_currentBlock !=null){
							if(blockOffset < _currentBlock.content().length){
								_blockOffset = (int)blockOffset;
								return n;
							}
							else{
								_blockOffset = _currentBlock.content().length;
								return n - (blockOffset - _currentBlock.content().length);
							}
						}
						else{
							//we had an error getting the needed block...  need to handle
							throw new IOException("Error getting block "+blockIndex + blocksToSkip+" in CCNInputStream.skip("+n+")");
						}			
					}
				}
			}
			*/		
			/*old code
			int blocksToSkip = (int)(1.0*blockOffset/_header.blockSize());
			
			Library.logger().info("_blockOffset "+_blockOffset+" blocksToSkip "+blocksToSkip);
			
			if (_header.count()-blockIndex < blocksToSkip) {
				blocksToSkip = _header.count() - blockIndex - 1;
				Library.logger().info("setting eof");
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
			*/
		/*
		} else {
			return readInternal(null, 0, (int)n);
		}
		*/
		
	}
	
	protected void retrieveHeader(ContentName baseName, PublisherID publisher) throws IOException {
		Interest headerInterest = new Interest(SegmentationProfile.headerName(baseName), publisher);
		headerInterest.additionalNameComponents(1);
		Library.logger().info("retrieveHeader: base name " + baseName);
		Library.logger().info("retrieveHeader: header name " + SegmentationProfile.headerName(baseName));
		_library.expressInterest(headerInterest, this);
	}

	public Interest handleContent(ArrayList<ContentObject> results,
								  Interest interest) {
		// This gives us back the header.
		for (ContentObject co : results) {
			Library.logger().info("CCNInputStream: retrieved header: " + co.name() + " type: " + co.signedInfo().getTypeName());
			if (null != _header) {
				continue;
			} else if (co.signedInfo().getType() == SignedInfo.ContentType.HEADER) {
				// First we verify. (Or should get have done this for us?)
				// We don't bother complaining unless we have more than one
				// header that matches. Given that we would complain for
				// that, we need an enumerate that operates at this level.)
					// TODO: DKS: should this be header.verify()?
					// Need low-level verify as well as high-level verify...
					// Low-level verify just checks that signer actually signed.
					// High-level verify checks trust.
				try {
					if (!co.verify(null)) {
						Library.logger().warning("Found header: " + co.name().toString() + " that fails to verify.");
						return interest; // try again
					} else {
						_headerName = co.name();
						_headerSignedInfo = co.signedInfo();
						_header = Header.contentToHeader(co);
						Library.logger().fine("Found header specifies " + _header.blockCount() + " blocks");
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
	
	protected int blockCount() {
		if (null == _header) {
			return 0;
		}
		return _header.blockCount();
	}

	//protected int currentBlockNumber(){
	//	int ind = -1;
	//	if(_currentBlock!=null)
	//		ind = Integer.valueOf(_currentBlock.name().stringComponent(_currentBlock.name().count()-1));
	//	//Library.logger().info("checking block number: "+ind);
	//	return ind;
	//}
	
	public long seek(long position) throws IOException {
		Library.logger().info("Seeking stream to " + position + ": have header? " + ((_header == null) ? "no." : "yes."));
		if (null != _header) {
			int [] blockAndOffset = _header.positionToBlockLocation(position);
			Library.logger().info("seek:  position: " + position + " block: " + blockAndOffset[0] + " offset: " + blockAndOffset[1]);
			Library.logger().info("currently have block "+ currentBlockNumber());
			if(currentBlockNumber() == blockAndOffset[0]){
				//already have the correct block
				if(_blockOffset == blockAndOffset[1]){
					//already have the correct offset
				}
				else
					_blockOffset = blockAndOffset[1];
				return position;
			
			}
			
			_currentBlock = getBlock(blockAndOffset[0]);
			_blockOffset = blockAndOffset[1];
			long check = _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			Library.logger().info("current position: block "+blockAndOffset[0]+" _blockOffset "+_blockOffset+" ("+check+")");
			if(_currentBlock!=null)
				_atEOF=false;
			// Might be at end of stream, so different value than came in...
			//long check = _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			//Library.logger().info("return val check: "+check);
			
			//return _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			//skip(check);
			
			//Library.logger().info(" _blockOffset "+_blockOffset);
			return check;
		} else {
			getFirstBlock();
			return skip(position);
		}
	}

	public long tell() {
		if (null != _header) {
			return _header.blockLocationToPosition(blockIndex(), _blockOffset);
		} else {
			return _blockOffset; // could implement a running count...
		}
	}
	
	public long length() {
		if (null != _header)
			return _header.length();
		return 0;
	}
	
	public ContentName baseName() { return _baseName; }
}

