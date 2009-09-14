/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.security.crypto.UnbufferedCipherInputStream;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * This abstract class is the superclass of all classes representing an input stream of
 * bytes segmented and stored in CCN. 
 * 
 * @see CCNSegmenter for description of CCN segmentation
 * @author smetters
 */
public abstract class CCNAbstractInputStream extends InputStream implements ContentVerifier {

	protected static final int MAX_TIMEOUT = 5000;

	protected CCNHandle _handle;

	protected ContentObject _currentSegment = null;
	protected ContentObject _goneSegment = null;
	protected InputStream _segmentReadStream = null; // includes filters, etc.
	
	/**
	 * This is the name we are querying against, prior to each
	 * fragment/segment number.
	 */
	protected ContentName _baseName = null;
	protected PublisherPublicKeyDigest _publisher = null; // the publisher we are looking for
	protected Long _startingSegmentNumber = null;
	protected int _timeout = MAX_TIMEOUT;
	
	/**
	 *  Encryption/decryption handler
	 */
	protected Cipher _cipher;
	protected ContentKeys _keys;
	
	/**
	 * If this content uses Merkle Hash Trees or other structures to amortize
	 * signature cost, we can amortize verification cost as well by caching verification
	 * data. Store the currently-verified root signature, so we don't have to re-verify;
	 * and the verified root hash. For each piece of incoming content, see if it aggregates
	 * to the same root, if so don't reverify signature. If not, assume it's part of
	 * a new tree and change the root.
	 */
	protected byte [] _verifiedRootSignature = null;
	protected byte [] _verifiedProxy = null;
	
	protected KeyLocator _publisherKeyLocator; // the key locator of the content publisher as we read it.

	protected boolean _atEOF = false;

	protected int _readlimit = 0;

	protected int _markOffset = 0;

	protected long _markBlock = 0;

	/**
	 * @param baseName should not include a segment component.
	 * @param startingSegmentNumber
	 * @param publisher
	 * @param handle
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNAbstractInputStream(
			ContentName baseName, Long startingSegmentNumber,
			PublisherPublicKeyDigest publisher, CCNHandle handle) 
					throws XMLStreamException, IOException {
		super();
		
		if (null == baseName) {
			throw new IllegalArgumentException("baseName cannot be null!");
		}
		_handle = handle; 
		if (null == _handle) {
			_handle = CCNHandle.getHandle();
		}
		_publisher = publisher;	
		
		// So, we assume the name we get in is up to but not including the sequence
		// numbers, whatever they happen to be. If a starting segment is given, we
		// open from there, otherwise we open from the leftmost number available.
		// We assume by the time you've called this, you have a specific version or
		// whatever you want to open -- this doesn't crawl versions.  If you don't
		// offer a starting segment index, but instead offer the name of a specific
		// segment, this will use that segment as the starting segment. 
		_baseName = baseName;
		if (startingSegmentNumber != null) {
			_startingSegmentNumber = startingSegmentNumber;
		} else {
			if (SegmentationProfile.isSegment(baseName)) {
				_startingSegmentNumber = SegmentationProfile.getSegmentNumber(baseName);
				baseName = _baseName.parent();
			} else {
				_startingSegmentNumber = SegmentationProfile.baseSegment();
			}
		}
	}
	
	public CCNAbstractInputStream(
			ContentName baseName, Long startingSegmentNumber,
			PublisherPublicKeyDigest publisher,
			ContentKeys keys, CCNHandle handle) 
					throws XMLStreamException, IOException {
		
		this(baseName, startingSegmentNumber, publisher, handle);
		
		if (null != keys) {
			keys.requireDefaultAlgorithm();
			_keys = keys;
		}
	}
	
	/**
	 * Assumes starterSegment has been verified by caller.
	 * @param firstSegment
	 * @param handle
	 * @throws IOException
	 */
	public CCNAbstractInputStream(ContentObject firstSegment, 			
			CCNHandle handle) throws IOException  {
		super();
		_handle = handle; 
		if (null == _handle) {
			_handle = CCNHandle.getHandle();
		}
		setFirstSegment(firstSegment);
		_baseName = SegmentationProfile.segmentRoot(firstSegment.name());
		try {
			_startingSegmentNumber = SegmentationProfile.getSegmentNumber(firstSegment.name());
		} catch (NumberFormatException nfe) {
			throw new IOException("Stream starter segment name does not contain a valid segment number, so the stream does not know what content to start with.");
		}
	}

	public CCNAbstractInputStream(ContentObject firstSegment, 			
			ContentKeys keys,
			CCNHandle handle) throws IOException {

		this(firstSegment, handle);
		
		keys.requireDefaultAlgorithm();
		_keys = keys;
	}

	public void setTimeout(int timeout) {
		_timeout = timeout;
	}
	
	public ContentName getBaseName() {
		return _baseName;
	}
	
	public CCNTime getVersion() {
		if (null == _baseName) 
			return null;
		return VersioningProfile.getTerminalVersionAsTimestampIfVersioned(_baseName);
	}

	@Override
	public int read() throws IOException {
		byte [] b = new byte[1];
		if (read(b, 0, 1) < 0) {
			return -1;
		}
		return (0x000000FF & b[0]);
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	/**
	 * Reads a packet/segment into the buffer. If the buffer is shorter than
	 * the packet's length, reads out of the current segment for now.
	 * Aim is really to do packet-sized reads. Probably ought to be a DatagramSocket subclass.
	 * @param buf the buffer into which to write.
	 * @param offset the offset into buf at which to write data
	 * @param len the number of bytes to write
	 * @return -1 if at EOF, or number of bytes read
	 * @throws IOException 
	 */
	@Override
	public int read(byte[] buf, int offset, int len) throws IOException {

		if (null == buf)
			throw new NullPointerException("Buffer cannot be null!");
		
		return readInternal(buf, offset, len);
	}
	
	protected abstract int readInternal(byte [] buf, int offset, int len) throws IOException;

	/**
	 * Called to set the first segment when opening a stream.
	 * @param newSegment Must not be null
	 * @throws IOException
	 */
	protected void setFirstSegment(ContentObject newSegment) throws IOException {
		if (null == newSegment) {
			throw new NoMatchingContentFoundException("Cannot find first segment of " + getBaseName());
		}
		if (newSegment.signedInfo().getType().equals(ContentType.GONE)) {
			_goneSegment = newSegment;
			Log.info("getFirstSegment: got gone segment: " + _goneSegment.name());
		}
		setCurrentSegment(newSegment);
	}

	/**
	 * Set up current segment for reading, including prep for decryption if necessary.
	 * Called after getSegment/getFirstSegment/getNextSegment, which take care of verifying
	 * the segment for us. So we assume newSegment is valid.
	 * @throws IOException 
	 */
	protected void setCurrentSegment(ContentObject newSegment) throws IOException {
		_currentSegment = null;
		_segmentReadStream = null;
		if (null == newSegment) {
			Log.info("FINDME: Setting current segment to null! Did a segment fail to verify?");
			return;
		}
		
		_currentSegment = newSegment;
		// Should we only set these on the first retrieval?
		// getSegment will ensure we get a requested publisher (if we have one) for the
		// first segment; once we have a publisher, it will ensure that future segments match it.
		_publisher = newSegment.signedInfo().getPublisherKeyID();
		_publisherKeyLocator = newSegment.signedInfo().getKeyLocator();
		
		if (_goneSegment != newSegment) { // want pointer ==, not equals() here
			_segmentReadStream = new ByteArrayInputStream(_currentSegment.content());

			// if we're decrypting, then set it up now
			if (_keys != null) {
				try {
					// Reuse of current segment OK. Don't expect to have two separate readers
					// independently use this stream without state confusion anyway.
					_cipher = _keys.getSegmentDecryptionCipher(
							SegmentationProfile.getSegmentNumber(_currentSegment.name()));
				} catch (InvalidKeyException e) {
					Log.warning("InvalidKeyException: " + e.getMessage());
					throw new IOException("InvalidKeyException: " + e.getMessage());
				} catch (InvalidAlgorithmParameterException e) {
					Log.warning("InvalidAlgorithmParameterException: " + e.getMessage());
					throw new IOException("InvalidAlgorithmParameterException: " + e.getMessage());
				}
				_segmentReadStream = new UnbufferedCipherInputStream(_segmentReadStream, _cipher);
			} else {
				if (_currentSegment.signedInfo().getType().equals(ContentType.ENCR)) {
					Log.warning("Asked to read encrypted content, but not given a key to decrypt it. Decryption happening at higher level?");
				}
			}
		}
	}
	
	protected void rewindSegment() throws IOException {
		if (null == _currentSegment) {
			Log.info("Cannot reqind null segment.");
		}
		if (null == _segmentReadStream) {
			setCurrentSegment(_currentSegment);
		}
		_segmentReadStream.reset(); // will reset to 0 if mark not caled
	}

	/**
	 * Three navigation options: get first (leftmost) segment, get next segment,
	 * or get a specific segment.
	 * Have to assume that everyone is using our segment number encoding. Probably
	 * easier to ask raw streams to use that encoding (e.g. for packet numbers)
	 * than to flag streams as to whether they are using integers or segments.
	 **/
	protected ContentObject getSegment(long number) throws IOException {

 		if (_currentSegment != null) {
			//what segment do we have right now?  maybe we already have it
			if (currentSegmentNumber() == number){
				//we already have this segment...
				return _currentSegment;
			}
		}
 		// If no publisher specified a priori, _publisher will be null and we will get whoever is
 		// available that verifies for first segment. If _publisher specified a priori, or once we have
 		// retrieved a segment and set _publisher to the publisher of that segment, we will continue to
 		// retrieve segments by the same publisher.
		return SegmentationProfile.getSegment(_baseName, number, _publisher, _timeout, this, _handle);
	}
	
	/**
	 * Checks whether we might have a next segment -- we're not past the EOF marker,
	 * or GONE.
	 * @return
	 * @throws IOException
	 */
	protected boolean hasNextSegment() throws IOException {
		
		// We're looking at content marked GONE
		if (null != _goneSegment) {
			Log.info("getNextSegment: We have a gone segment, no next segment. Gone segment: " + _goneSegment.name());
			return false;
		}
		
		// Check to see if finalBlockID is the current segment. If so, there should
		// be no next segment. (If the writer makes a mistake and guesses the wrong
		// value for finalBlockID, they won't put that wrong value in the segment they're
		// guessing itself -- unless they want to try to extend a "closed" stream.
		// Normally by the time they write that segment, they either know they're done or not.
		if (null != _currentSegment.signedInfo().getFinalBlockID()) {
			if (Arrays.equals(_currentSegment.signedInfo().getFinalBlockID(), _currentSegment.name().lastComponent())) {
				Log.info("getNextSegment: there is no next segment. We have segment: " + 
						DataUtils.printHexBytes(_currentSegment.name().lastComponent()) + " which is marked as the final segment.");
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Will try for the next segment.
	 * @return
	 * @throws IOException
	 */
	protected ContentObject getNextSegment() throws IOException {
		if (null == _currentSegment) {
			Log.info("getNextSegment: no current segment, getting first segment.");
			return getFirstSegment();
		}
		
		Log.info("getNextSegment: getting segment after " + _currentSegment.name());
		return getSegment(nextSegmentNumber());
	}
	
	protected ContentObject getFirstSegment() throws IOException {
		if (null != _startingSegmentNumber) {
			ContentObject firstSegment = getSegment(_startingSegmentNumber);
			Log.info("getFirstSegment: segment number: " + _startingSegmentNumber + " got segment? " + 
					((null == firstSegment) ? "no " : firstSegment.name()));
			return firstSegment;
		} else {
			throw new IOException("Stream does not have a valid starting segment number.");
		}
	}
	
	/**
	 * For CCNAbstractInputStream, assume that desiredName contains the name up to segmentation information.
	 * @param desiredName
	 * @param segment
	 * @return
	 */
	protected boolean isFirstSegment(ContentName desiredName, ContentObject segment) {
		if ((null != segment) && (SegmentationProfile.isSegment(segment.name()))) {
			Log.info("is " + segment.name() + " a first segment of " + desiredName);
			// In theory, the segment should be at most a versioning component different from desiredName.
			// In the case of complex segmented objects (e.g. a KeyDirectory), where there is a version,
			// then some name components, then a segment, desiredName should contain all of those other
			// name components -- you can't use the usual versioning mechanisms to pull first segment anyway.
			if (!desiredName.equals(SegmentationProfile.segmentRoot(segment.name()))) {
				Log.info("Desired name :" + desiredName + " is not a prefix of segment: " + segment.name());
				return false;
			}
			if (null != _startingSegmentNumber) {
				return (_startingSegmentNumber.equals(SegmentationProfile.getSegmentNumber(segment.name())));
			} else {
				return SegmentationProfile.isFirstSegment(segment.name());
			}
		}
		return false;
	}

	/**
	 * TODO -- check to see if it matches desired publisher.
	 */
	public boolean verify(ContentObject segment) {

		// First we verify. 
		// Low-level verify just checks that signer actually signed.
		// High-level verify checks trust.
		try {

			// We could have several options here. This segment could be simply signed.
			// or this could be part of a Merkle Hash Tree. If the latter, we could
			// already have its signing information.
			if (null == segment.signature().witness()) {
				return segment.verify(null);
			}

			// Compare to see whether this segment matches the root signature we previously verified, if
			// not, verify and store the current signature.
			// We need to compute the proxy regardless.
			byte [] proxy = segment.computeProxy();

			// OK, if we have an existing verified signature, and it matches this segment's
			// signature, the proxy ought to match as well.
			if ((null != _verifiedRootSignature) && (Arrays.equals(_verifiedRootSignature, segment.signature().signature()))) {
				if ((null == proxy) || (null == _verifiedProxy) || (!Arrays.equals(_verifiedProxy, proxy))) {
					Log.warning("Found segment: " + segment.name() + " whose digest fails to verify; segment length: " + segment.contentLength());
					Log.info("Verification failure: " + segment.name() + " timestamp: " + segment.signedInfo().getTimestamp() + " content length: " + segment.contentLength() + 
							" content digest: " + DataUtils.printBytes(segment.contentDigest()) + " proxy: " + 
							DataUtils.printBytes(proxy) + " expected proxy: " + DataUtils.printBytes(_verifiedProxy));
	 				return false;
				}
			} else {
				// Verifying a new segment. See if the signature verifies, otherwise store the signature
				// and proxy.
				if (!ContentObject.verify(proxy, segment.signature().signature(), segment.signedInfo(), segment.signature().digestAlgorithm(), null)) {
					Log.warning("Found segment: " + segment.name().toString() + " whose signature fails to verify; segment length: " + segment.contentLength() + ".");
					return false;
				} else {
					// Remember current verifiers
					_verifiedRootSignature = segment.signature().signature();
					_verifiedProxy = proxy;
				}
			} 
			Log.info("Got segment: " + segment.name().toString() + ", verified.");
		} catch (Exception e) {
			Log.warning("Got an " + e.getClass().getName() + " exception attempting to verify segment: " + segment.name().toString() + ", treat as failure to verify.");
			Log.warningStackTrace(e);
			return false;
		}
		return true;
	}

	public long segmentNumber() {
		if (null == _currentSegment) {
			return SegmentationProfile.baseSegment();
		} else {
			// This needs to work on streaming content that is not traditional fragments.
			// The segmentation profile tries to do that, though it is seeming like the
			// new segment representation means we will have to assume that representation
			// even for stream content.
			return SegmentationProfile.getSegmentNumber(_currentSegment.name());
		}
	}
	
	/**
	 * Return the index of the next segment of stream data.
	 * Default segmentation generates sequentially-numbered stream
	 * segments but this method may be overridden in subclasses to 
	 * perform re-assembly on streams that have been segemented differently.
	 * @return
	 */
	public long nextSegmentNumber() {
		if (null == _currentSegment) {
			return _startingSegmentNumber.longValue();
		} else {
			return segmentNumber() + 1;
		}
	}
	
	protected long currentSegmentNumber(){
		if (null == _currentSegment) {
			return -1; // make sure we don't match inappropriately
		}
		return segmentNumber();
	}
	
	/**
	 * Is the stream GONE? I.E. Is there a single empty data segment, of type GONE where
	 * the first segment should be? This convention is used to represent a stream that has been
	 * deleted.
	 * @return
	 * @throws IOException
	 */
	public boolean isGone() throws IOException {

		// TODO: once first segment is always read in constructor this code will change
		if (null == _currentSegment) {
			ContentObject firstSegment = getFirstSegment();
			if (null != firstSegment) {
				setFirstSegment(firstSegment); // sets _goneSegment
			} else {
				// don't know anything
				return false;
			}
		}
		// We might have set first segment in constructor, in which case we will also have set _goneSegment
		if (null != _goneSegment) {
			return true;
		}
		return false;
	}
	
	public ContentObject deletionInformation() {
		return _goneSegment;
	}
	
	/**
	 * Callers may need to access information about this stream's publisher.
	 * We eventually should (TODO) ensure that all the segments we're reading
	 * match in publisher information, and cache the verified publisher info.
	 * (In particular once we're doing trust calculations, to ensure we do them
	 * only once per stream.)
	 * But we do verify each segment, so start by pulling what's in the current segment.
	 * @return
	 */
	public PublisherPublicKeyDigest publisher() {
		return _publisher;
	}
	
	public KeyLocator publisherKeyLocator() {
		return _publisherKeyLocator;		
	}
	
	/**
	 * For debugging
	 */
	public String currentSegmentName() {
		return ((null == _currentSegment) ? "null" : _currentSegment.name().toString());
	}

	@Override
	public int available() throws IOException {
		if (null == _segmentReadStream)
			return 0;
		return _segmentReadStream.available();
	}

	public boolean eof() { 
		//Log.info("Checking eof: there yet? " + _atEOF);
		return _atEOF; 
	}

	@Override
	public void close() throws IOException {
		// don't have to do anything.
	}

	@Override
	public synchronized void mark(int readlimit) {
		_readlimit = readlimit;
		_markBlock = segmentNumber();
		if (null == _segmentReadStream) {
			_markOffset = 0;
		} else {
			try {
				_markOffset = _currentSegment.contentLength() - _segmentReadStream.available();
				if (_segmentReadStream.markSupported()) {
					_segmentReadStream.mark(readlimit);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		Log.finer("mark: block: " + segmentNumber() + " offset: " + _markOffset);
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public synchronized void reset() throws IOException {
		// TODO: when first block is read in constructor this check can be removed
		if (_currentSegment == null) {
			setFirstSegment(getSegment(_markBlock));
		} else if (currentSegmentNumber() == _markBlock) {
				//already have the correct segment
				if (tell() == _markOffset){
					//already have the correct offset
				} else {
					// Reset and skip.
					if (_segmentReadStream.markSupported()) {
						_segmentReadStream.reset();
						Log.finer("reset within block: block: " + segmentNumber() + " offset: " + _markOffset + " eof? " + _atEOF);
						return;
					} else {
						setCurrentSegment(_currentSegment);
					}
				}
		} else {
			// getSegment doesn't pull segment if we already have the right one
			setCurrentSegment(getSegment(_markBlock));
		}
		_segmentReadStream.skip(_markOffset);
		_atEOF = false;
		Log.finer("reset: block: " + segmentNumber() + " offset: " + _markOffset + " eof? " + _atEOF);
	}

	@Override
	public long skip(long n) throws IOException {
		
		Log.info("in skip("+n+")");
		
		if (n < 0) {
			return 0;
		}
		
		return readInternal(null, 0, (int)n);
	}

	protected int segmentCount() throws IOException {
		return 0;
	}

	public void seek(long position) throws IOException {
		Log.info("Seeking stream to " + position);
		// TODO: when first block is read in constructor this check can be removed
		if ((_currentSegment == null) || (!SegmentationProfile.isFirstSegment(_currentSegment.name()))) {
			setFirstSegment(getFirstSegment());
			skip(position);
		} else if (position > tell()) {
			// we are on the first segment already, just move forward
			skip(position - tell());
		} else {
			// we are on the first segment already, just rewind back to the beginning
			rewindSegment();
			skip(position);
		}
	}

	public long tell() throws IOException {
		return _currentSegment.contentLength() - _segmentReadStream.available();
	}

	public long length() throws IOException {
		return -1;
	}

	public ContentName baseName() { return _baseName; }
	
}