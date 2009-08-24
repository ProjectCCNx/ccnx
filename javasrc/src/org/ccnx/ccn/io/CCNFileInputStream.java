package org.ccnx.ccn.io;

import java.io.IOException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.HeaderData;
import org.ccnx.ccn.io.content.HeaderData.HeaderObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


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
			PublisherPublicKeyDigest publisher, CCNHandle library)
			throws XMLStreamException, IOException {
		super(name, startingBlockIndex, publisher, library);
	}

	public CCNFileInputStream(ContentName name, Long startingBlockIndex,
			PublisherPublicKeyDigest publisher, ContentKeys keys, CCNHandle library)
			throws XMLStreamException, IOException {
		super(name, startingBlockIndex, publisher, keys, library);
	}

	public CCNFileInputStream(ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws XMLStreamException, IOException {
		this(name, null, publisher, library);
	}

	public CCNFileInputStream(ContentName name) throws XMLStreamException,
			IOException {
		super(name);
	}

	public CCNFileInputStream(ContentName name, CCNHandle library)
			throws XMLStreamException, IOException {
		super(name, library);
	}

	public CCNFileInputStream(ContentName name, long startingBlockIndex)
			throws XMLStreamException, IOException {
		this(name, startingBlockIndex, null, null);
	}

	public CCNFileInputStream(ContentObject starterBlock, CCNHandle library)
			throws XMLStreamException, IOException {
		super(starterBlock, library);
	}

	protected boolean headerRequested() {
		return (null != _header);
	}
	
	public boolean hasHeader() {
		return (headerRequested() && _header.available());
	}
	
	public HeaderData header() {
		if (null == _header)
			return null;
		return _header.header();
	}
	
	protected void requestHeader(ContentName baseName, PublisherPublicKeyDigest publisher) throws IOException, XMLStreamException {
		if (headerRequested())
			return; // done already
		// DKS TODO match header interest to new header name
		/*
		Interest headerInterest = new Interest(SegmentationProfile.headerName(baseName), publisher);
		headerInterest.maxSuffixComponents(1);
		Library.info("retrieveHeader: base name " + baseName);
		Library.info("retrieveHeader: header name " + SegmentationProfile.headerName(baseName));
		_library.expressInterest(headerInterest, this);
		*/
		// Ask for the header, but update it in the background, as it may not be there yet.
		_header = new HeaderObject(SegmentationProfile.headerName(baseName), null, publisher, null, _library);
		Log.info("Retrieving header : " + _header.getBaseName() + " in background.");
		_header.updateInBackground();
	}

	public Interest handleContent(ArrayList<ContentObject> results,
								  Interest interest) {
		Log.warning("Unexpected: shouldn't be in handleContent, object should handle this.");
		if (null != _header) {
			// Already have header so should not have reached here
			// and do not need to renew interest
			return null;
		}
		ArrayList<byte[]> excludeList = new ArrayList<byte[]>();
		for (ContentObject co : results) {
			Log.info("CCNInputStream: retrieved possible header: " + co.name() + " type: " + co.signedInfo().getTypeName());
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
				Log.warning("Found header: " + headerObject.name().toString() + " that fails to verify.");
				return false;
			} else {
				// DKS TODO -- use HeaderObject to read
				Log.info("Got header object in handleContent, loading into _header. Name: " + headerObject.name());
				_header.update(headerObject);
				Log.fine("Found header specifies " + _header.segmentCount() + " blocks");
				return true; // done
			}
		} catch (Exception e) {
			Log.warning("Got an " + e.getClass().getName() + " exception attempting to verify or decode header: " + headerObject.name().toString() + ", treat as failure to verify.");
			Log.warningStackTrace(e);
			return false; // try again
		}
	}

	protected ContentObject getFirstSegment() throws IOException {
		// Give up efficiency where we try to detect auto-caught header, and just
		// use superclass method to really get us a first content block, then
		// go after the header. Later on we can worry about re-adding the optimization.
		ContentObject result = super.getFirstSegment();
		if (null == result) {
			throw new IOException("Cannot retrieve first block of " + _baseName + "!");
		}
		// Have to wait to request the header till we know what version we're looking for.
		if (!headerRequested()) {
			try {
				requestHeader(_baseName, result.signedInfo().getPublisherKeyID());
			} catch (XMLStreamException e) {
				Log.fine("XMLStreamException in processing header: " + e.getMessage());
				// TODO -- throw nested exception in 1.6
				throw new IOException("Exception in processing header: " + e);
			}
		}
		return result;
	}

	@Override
	public long skip(long n) throws IOException {
		
		Log.info("in skip("+n+")");
		
		if (n < 0) {
			return 0;
		}
		
		if (!hasHeader()){
			return super.skip(n);
		}
		
		int[] toGetBlockAndOffset = null;
		long toGetPosition = 0;
		
		long currentBlock = -1;
		int currentBlockOffset = 0;
		long currentPosition = 0;
		
		if (_currentSegment == null) {
			//we do not have a block already
			//skip position is n
			currentPosition = 0;
			toGetPosition = n;
		} else {
		    //we already have a block...  need to handle some tricky cases
			currentBlock = segmentNumber();
			currentBlockOffset = (int)super.tell();
			currentPosition = _header.segmentLocationToPosition(currentBlock, currentBlockOffset);
			toGetPosition = currentPosition + n;
		}
		//make sure we don't skip past end of the object
		if (toGetPosition >= _header.length()) {
			toGetPosition = _header.length();
			_atEOF = true;
		}
			
		toGetBlockAndOffset = _header.positionToSegmentLocation(toGetPosition);
		
		//make sure the position makes sense
		//is this a valid block?
		if (toGetBlockAndOffset[0] >= _header.segmentCount()){
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
		if (_currentSegment == null)
			setFirstSegment(getSegment(toGetBlockAndOffset[0]));
		else
			setCurrentSegment(getSegment(toGetBlockAndOffset[0]));
		if (_currentSegment == null) {
			//we had an error getting the block
			throw new IOException("Error getting block "+toGetBlockAndOffset[0]+" in CCNInputStream.skip("+n+")");
		} else {
			//we have a valid block!
			//first make sure the offset is valid
			if (toGetBlockAndOffset[1] <= _currentSegment.contentLength()) {
				//this is good, our offset is somewhere in this block
			} else {
				//our offset is past the end of our block, reset to the end.
				toGetBlockAndOffset[1] = _currentSegment.contentLength();
			}
			_segmentReadStream.skip(toGetBlockAndOffset[1]);
			return _header.segmentLocationToPosition(toGetBlockAndOffset[0], toGetBlockAndOffset[1]) - currentPosition;
		}
	}
	
	@Override
	protected int segmentCount() {
		if (hasHeader()) {
            return _header.segmentCount();
		}
		return super.segmentCount();
	}

	@Override
	public long seek(long position) throws IOException {
		Log.info("Seeking stream to " + position + ": have header? " + hasHeader());
		if (hasHeader()) {
			int [] blockAndOffset = _header.positionToSegmentLocation(position);
			Log.info("seek:  position: " + position + " block: " + blockAndOffset[0] + " offset: " + blockAndOffset[1]);
			Log.info("currently have block "+ currentSegmentNumber());
			if (currentSegmentNumber() == blockAndOffset[0]) {
				//already have the correct block
				if (super.tell() == blockAndOffset[1]){
					//already have the correct offset
				} else {
					// Reset and skip.
					if (_segmentReadStream.markSupported()) {
						_segmentReadStream.reset();
					} else {
						setCurrentSegment(_currentSegment);
					}
					_segmentReadStream.skip(blockAndOffset[1]);
				}
				return position;
			}
			
			// TODO: once first block is always set in a constructor this conditional can be removed
			if (_currentSegment == null)
				setFirstSegment(getSegment(blockAndOffset[0]));
			else
				setCurrentSegment(getSegment(blockAndOffset[0]));
			super.skip(blockAndOffset[1]);
			long check = _header.segmentLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			Log.info("current position: block "+blockAndOffset[0]+" _blockOffset "+super.tell()+" ("+check+")");

			if (_currentSegment != null) {
				_atEOF=false;
			}
			// Might be at end of stream, so different value than came in...
			//long check = _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			//Library.info("return val check: "+check);
			
			//return _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			//skip(check);
			
			//Library.info(" _blockOffset "+_blockOffset);
			return check;
		} else {
			return super.seek(position);
		}
	}

	@Override
	public long tell() {
		if (hasHeader()) {
			return _header.segmentLocationToPosition(segmentNumber(), (int)super.tell());
		} else {
			return super.tell();
		}
	}
	
	@Override
	public long length() {
		if (hasHeader()) {
			return _header.length();
		}
		return super.length();
	}
}
