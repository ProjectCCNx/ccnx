package com.parc.ccn.library.io;

import java.io.IOException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.content.HeaderData.HeaderObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.ContentKeys;

/**
 * This class takes a versioned input stream and adds the expectation
 * that it will have a header. 
 * TODO migrate header to new desired contents
 * TODO migrate header to new schema type
 * @author smetters
 *
 */
public class CCNFileInputStream extends CCNVersionedInputStream implements CCNInterestListener {

	/**
	 * The header information for that object, once
	 * we've read it. 
	 */
	protected HeaderObject _header = null;
	
	public CCNFileInputStream(ContentName name, Long startingBlockIndex,
			PublisherPublicKeyDigest publisher, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, startingBlockIndex, publisher, library);
		// Asynchronously attempt to retrieve a header block, if one exists.
		requestHeader(_baseName, publisher);
	}

	public CCNFileInputStream(ContentName name, Long startingBlockIndex,
			PublisherPublicKeyDigest publisher, ContentKeys keys, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, startingBlockIndex, publisher, keys, library);
		// Asynchronously attempt to retrieve a header block, if one exists.
		requestHeader(_baseName, publisher);
	}

	public CCNFileInputStream(ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, publisher, library);
	}

	public CCNFileInputStream(ContentName name) throws XMLStreamException,
			IOException {
		super(name);
		// Asynchronously attempt to retrieve a header block, if one exists.
		requestHeader(_baseName, null);
	}

	public CCNFileInputStream(ContentName name, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, library);
		// Asynchronously attempt to retrieve a header block, if one exists.
		requestHeader(_baseName, null);
	}

	public CCNFileInputStream(ContentName name, long startingBlockIndex)
			throws XMLStreamException, IOException {
		this(name, startingBlockIndex, null, null);
	}

	public CCNFileInputStream(ContentObject starterBlock, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(starterBlock, library);
		// Asynchronously attempt to retrieve a header block, if one exists.
		requestHeader(_baseName, null);
	}

	protected boolean headerRequested() {
		return (null != _header);
	}
	
	public boolean headerAvailable() {
		return (headerRequested() && _header.available());
	}
	
	protected void requestHeader(ContentName baseName, PublisherPublicKeyDigest publisher) throws IOException, XMLStreamException {
		if (headerRequested())
			return; // done already
		// DKS TODO match header interest to new header name
		/*
		Interest headerInterest = new Interest(SegmentationProfile.headerName(baseName), publisher);
		headerInterest.maxSuffixComponents(1);
		Library.logger().info("retrieveHeader: base name " + baseName);
		Library.logger().info("retrieveHeader: header name " + SegmentationProfile.headerName(baseName));
		_library.expressInterest(headerInterest, this);
		*/
		// Ask for the header, but update it in the background, as it may not be there yet.
		_header = new HeaderObject(SegmentationProfile.headerName(baseName), null, publisher, null, _library);
		Library.logger().info("Retrieving header : " + _header.getBaseName() + " in background.");
		_header.updateInBackground();
	}

	public Interest handleContent(ArrayList<ContentObject> results,
								  Interest interest) {
		Library.logger().warning("Unexpected: shouldn't be in handleContent, object should handle this.");
		if (null != _header) {
			// Already have header so should not have reached here
			// and do not need to renew interest
			return null;
		}
		ArrayList<byte[]> excludeList = new ArrayList<byte[]>();
		for (ContentObject co : results) {
			Library.logger().info("CCNInputStream: retrieved possible header: " + co.name() + " type: " + co.signedInfo().getTypeName());
			if (SegmentationProfile.isHeader(_baseName, co.name()) &&
					addHeader(co)) {
				// Low-level verify is done in addHeader
				// TODO: DKS: should this be header.verify()?
				// Need low-level verify as well as high-level verify...
				// Low-level verify just checks that signer actually signed.
				// High-level verify checks trust.
				// Got a header successfully, so no need to renew interest
				return null;
			} else {
				// This one isn't a valid header we can use so we don't
				// want to see it again.  Need to exclude by digest
				// which will not be represented in name()
				excludeList.add(co.contentDigest());
			}
		}
		if (null == _header) { 
			byte[][] excludes = null;
			if (excludeList.size() > 0) {
				excludes = new byte[excludeList.size()][];
				excludeList.toArray(excludes);
			}
			interest.excludeFilter().add(excludes);
			return interest;
		}
		return null;
	}
	
	protected boolean addHeader(ContentObject headerObject) {
		try {
			if (!headerObject.verify(null)) {
				Library.logger().warning("Found header: " + headerObject.name().toString() + " that fails to verify.");
				return false;
			} else {
				// DKS TODO -- use HeaderObject to read
				Library.logger().info("Got header object in handleContent, loading into _header. Name: " + headerObject.name());
				_header.update(headerObject);
				Library.logger().fine("Found header specifies " + _header.blockCount() + " blocks");
				return true; // done
			}
		} catch (Exception e) {
			Library.logger().warning("Got an " + e.getClass().getName() + " exception attempting to verify or decode header: " + headerObject.name().toString() + ", treat as failure to verify.");
			Library.warningStackTrace(e);
			return false; // try again
		}
	}

	protected ContentObject getFirstBlock() throws IOException {
		if (VersioningProfile.hasTerminalVersion(_baseName)) {
			return super.getFirstBlock();
		}
		Library.logger().info("getFirstBlock: getting latest version of " + _baseName);
		// This might get us the header instead...
		ContentObject result =  _library.getLatestVersion(_baseName, null, _timeout);
		if (null != result){
			// Now we know the version
			_baseName = SegmentationProfile.segmentRoot(result.name());
			Library.logger().info("getFirstBlock: retrieved " + result.name() + " type: " + result.signedInfo().getTypeName());
			if (SegmentationProfile.isHeader(_baseName, result.name())) {
				if (!addHeader(result)) { // verifies
					Library.logger().warning("Retrieved header spontaneously in getFirstBlock, but failed to process it.");
				}
				Library.logger().info("Retrieved header, setting _baseName to " + _baseName + " calling CCNInputStream.getFirstBlock.");
			}
			// Now need to verify the block we got
			if (!verifyBlock(result)) {
				return null;
			}
			if (!headerRequested())
				requestHeader(_baseName, new PublisherID(result.signedInfo().getPublisherKeyID()));
			return getBlock(_startingBlockIndex);
		}
		return result;
	}

	@Override
	public long skip(long n) throws IOException {
		
		Library.logger().info("in skip("+n+")");
		
		if (n < 0) {
			return 0;
		}
		
		if (!headerAvailable()){
			super.skip(n);
		}
		
		int[] toGetBlockAndOffset = null;
		long toGetPosition = 0;
		
		long currentBlock = -1;
		int currentBlockOffset = 0;
		long currentPosition = 0;
		
		if (_currentBlock == null) {
			//we do not have a block already
			//skip position is n
			currentPosition = 0;
			toGetPosition = n;
		} else {
		    //we already have a block...  need to handle some tricky cases
			currentBlock = blockIndex();
			currentBlockOffset = (int)super.tell();
			currentPosition = _header.blockLocationToPosition(currentBlock, currentBlockOffset);
			toGetPosition = currentPosition + n;
		}
		//make sure we don't skip past end of the object
		if (toGetPosition >= _header.length()) {
			toGetPosition = _header.length();
			_atEOF = true;
		}
			
		toGetBlockAndOffset = _header.positionToBlockLocation(toGetPosition);
		
		//make sure the position makes sense
		//is this a valid block?
		if (toGetBlockAndOffset[0] >= _header.blockCount()){
			//this is not a valid block number, subtract 1
			if (toGetBlockAndOffset[0] > 0) {
				toGetBlockAndOffset[0]--;
			}
			//now we have the last block if the position was too long
		}
		
		//is the offset > 0?
		if (toGetBlockAndOffset[1] < 0) {
			toGetBlockAndOffset[1] = 0;
		}
			
		//now we should get the block and check the offset
		// TODO: once first block is always set in a constructor this conditional can be removed
		if (_currentBlock == null)
			setFirstBlock(getBlock(toGetBlockAndOffset[0]));
		else
			setCurrentBlock(getBlock(toGetBlockAndOffset[0]));
		if (_currentBlock == null) {
			//we had an error getting the block
			throw new IOException("Error getting block "+toGetBlockAndOffset[0]+" in CCNInputStream.skip("+n+")");
		} else {
			//we have a valid block!
			//first make sure the offset is valid
			if (toGetBlockAndOffset[1] <= _currentBlock.contentLength()) {
				//this is good, our offset is somewhere in this block
			} else {
				//our offset is past the end of our block, reset to the end.
				toGetBlockAndOffset[1] = _currentBlock.contentLength();
			}
			_blockReadStream.skip(toGetBlockAndOffset[1]);
			return _header.blockLocationToPosition(toGetBlockAndOffset[0], toGetBlockAndOffset[1]) - currentPosition;
		}
	}
	
	@Override
	protected int blockCount() {
		if (!headerAvailable()) {
			return super.blockCount();
		}
		return _header.blockCount();
	}

	@Override
	public long seek(long position) throws IOException {
		Library.logger().info("Seeking stream to " + position + ": have header? " + ((_header == null) ? "no." : "yes."));
		if (!headerAvailable()) {
			int [] blockAndOffset = _header.positionToBlockLocation(position);
			Library.logger().info("seek:  position: " + position + " block: " + blockAndOffset[0] + " offset: " + blockAndOffset[1]);
			Library.logger().info("currently have block "+ currentBlockNumber());
			if (currentBlockNumber() == blockAndOffset[0]) {
				//already have the correct block
				if (super.tell() == blockAndOffset[1]){
					//already have the correct offset
				} else {
					// Reset and skip.
					if (_blockReadStream.markSupported()) {
						_blockReadStream.reset();
					} else {
						setCurrentBlock(_currentBlock);
					}
					_blockReadStream.skip(blockAndOffset[1]);
				}
				return position;
			}
			
			// TODO: once first block is always set in a constructor this conditional can be removed
			if (_currentBlock == null)
				setFirstBlock(getBlock(blockAndOffset[0]));
			else
				setCurrentBlock(getBlock(blockAndOffset[0]));
			super.skip(blockAndOffset[1]);
			long check = _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			Library.logger().info("current position: block "+blockAndOffset[0]+" _blockOffset "+super.tell()+" ("+check+")");

			if (_currentBlock != null) {
				_atEOF=false;
			}
			// Might be at end of stream, so different value than came in...
			//long check = _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			//Library.logger().info("return val check: "+check);
			
			//return _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			//skip(check);
			
			//Library.logger().info(" _blockOffset "+_blockOffset);
			return check;
		} else {
			return super.seek(position);
		}
	}

	@Override
	public long tell() {
		if (!headerAvailable()) {
			return _header.blockLocationToPosition(blockIndex(), (int)super.tell());
		} else {
			return super.tell();
		}
	}
	
	@Override
	public long length() {
		if (!headerAvailable()) {
			return _header.length();
		}
		return super.length();
	}
}
