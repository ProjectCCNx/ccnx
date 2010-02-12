/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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
import java.util.EnumSet;
import java.util.logging.Level;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
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
 * @see SegmentationProfile for description of CCN segmentation
 */
public abstract class CCNAbstractInputStream extends InputStream implements ContentVerifier {

	/**
	 * Flags:
	 * DONT_DEREFERENCE to prevent dereferencing in case we are attempting to read a link.
	 */
	
	protected CCNHandle _handle;
	
	/**
	 * The Link we dereferenced to get here, if any. This may contain
	 * a link dereferenced to get to it, and so on.
	 */
	protected LinkObject _dereferencedLink = null;
	
	public enum FlagTypes { DONT_DEREFERENCE };
	
	protected EnumSet<FlagTypes> _flags = EnumSet.noneOf(FlagTypes.class);

	/**
	 * The segment we are currently reading from.
	 */
	protected ContentObject _currentSegment = null;

	/**
	 *  information if the stream we are reading is marked GONE (see ContentType).
	 */
	protected ContentObject _goneSegment = null;

	/**
	 * Internal stream used for buffering reads. May include filters.
	 */
	protected InputStream _segmentReadStream = null; 

	/**
	 * The name prefix of the segmented stream we are reading, up to (but not including)
	 * a segment number.
	 */
	protected ContentName _baseName = null;

	/**
	 * The publisher we are looking for, either specified by querier on initial
	 * read, or read from previous blocks (for now, we assume that all segments in a
	 * stream are created by the same publisher).
	 */
	protected PublisherPublicKeyDigest _publisher = null; 

	/**
	 * The segment number to start with. If not specified, is SegmentationProfile#baseSegment().
	 */
	protected Long _startingSegmentNumber = null;

	/**
	 * The timeout to use for segment retrieval. 
	 */
	protected int _timeout = SystemConfiguration.getDefaultTimeout();

	/**
	 *  Encryption/decryption handler.
	 */
	protected Cipher _cipher;
	protected ContentKeys _keys;

	/**
	 * If this content uses Merkle Hash Trees or other bulk signatures to amortize
	 * signature cost, we can amortize verification cost as well by caching verification
	 * data as follows: store the currently-verified root signature, so we don't have to re-verify it;
	 * and the verified root hash. For each piece of incoming content, see if it aggregates
	 * to the same root, if so don't reverify signature. If not, assume it's part of
	 * a new tree and change the root.
	 */
	protected byte [] _verifiedRootSignature = null;
	protected byte [] _verifiedProxy = null;

	/**
	 * The key locator of the content publisher as we read it.
	 */
	protected KeyLocator _publisherKeyLocator; 

	protected boolean _atEOF = false;

	/**
	 * Used for mark(int) and reset().
	 */
	protected int _readlimit = 0;
	protected int _markOffset = 0;
	protected long _markBlock = 0;

	/**
	 * Set up an input stream to read segmented CCN content under a given name. 
	 * Note that this constructor does not currently retrieve any
	 * data; data is not retrieved until read() is called. This will change in the future, and
	 * this constructor will retrieve the first block.
	 * 
	 * @param baseName Name to read from. If contains a segment number, will start to read from that
	 *    segment.
	 * @param startingSegmentNumber Alternative specification of starting segment number. If
	 * 		unspecified, will be SegmentationProfile#baseSegment().
	 * @param publisher The key we require to have signed this content. If null, will accept any publisher
	 * 				(subject to higher-level verification).
	 * @param keys The keys to use to decrypt this content. Null if content unencrypted, or another
	 * 				process will be used to retrieve the keys.
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNAbstractInputStream(
			ContentName baseName, Long startingSegmentNumber,
			PublisherPublicKeyDigest publisher, 
			ContentKeys keys,
			EnumSet<FlagTypes> flags,
			CCNHandle handle) throws IOException {
		super();

		if (null == baseName) {
			throw new IllegalArgumentException("baseName cannot be null!");
		}
		_handle = handle; 
		if (null == _handle) {
			_handle = CCNHandle.getHandle();
		}
		_publisher = publisher;	

		if (null != keys) {
			keys.requireDefaultAlgorithm();
			_keys = keys;
		}
		
		if (null != flags) {
			_flags = flags;
		}

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

	/**
	 * Set up an input stream to read segmented CCN content starting with a given
	 * ContentObject that has already been retrieved.  
	 * @param startingSegment The first segment to read from. If this is not the
	 * 		first segment of the stream, reading will begin from this point.
	 * 		We assume that the signature on this segment was verified by our caller.
	 * @param keys The keys to use to decrypt this content. Null if content unencrypted, or another
	 * 				process will be used to retrieve the keys.
	 * @param any flags necessary for processing this stream; have to hand in in constructor in case
	 * 		first segment provided, so can apply to that segment
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException
	 */
	public CCNAbstractInputStream(ContentObject startingSegment,
			ContentKeys keys,
			EnumSet<FlagTypes> flags,
			CCNHandle handle) throws IOException  {
		super();
		_handle = handle; 
		if (null == _handle) {
			_handle = CCNHandle.getHandle();
		}

		if (null != keys) {
			keys.requireDefaultAlgorithm();
			_keys = keys;
		}

		if (null != flags) {
			_flags = flags;
		}

		_baseName = SegmentationProfile.segmentRoot(startingSegment.name());
		try {
			_startingSegmentNumber = SegmentationProfile.getSegmentNumber(startingSegment.name());
		} catch (NumberFormatException nfe) {
			throw new IOException("Stream starter segment name does not contain a valid segment number, so the stream does not know what content to start with.");
		}
		setFirstSegment(startingSegment);
	}

	/**
	 * Set the timeout that will be used for all content retrievals on this stream.
	 * Default is 5 seconds.
	 * @param timeout Milliseconds
	 */
	public void setTimeout(int timeout) {
		_timeout = timeout;
	}
	
	/**
	 * Add flags to this stream. Adds to existing flags.
	 */
	public void addFlags(EnumSet<FlagTypes> additionalFlags) {
		_flags.addAll(additionalFlags);
	}

	/**
	 * Add a flag to this stream. Adds to existing flags.
	 */
	public void addFlag(FlagTypes additionalFlag) {
		_flags.add(additionalFlag);
	}

	/**
	 * Set flags on this stream. Replaces existing flags.
	 */
	public void setFlags(EnumSet<FlagTypes> flags) {
		if (null == flags) {
			_flags.clear();
		} else {
			_flags = flags;
		}
	}
	
	/**
	 * Clear the flags on this stream.
	 */
	public void clearFlags() {
		_flags.clear();
	}
	
	/**
	 * Remove a flag from this stream.
	 */
	public void removeFlag(FlagTypes flag) {
		_flags.remove(flag);
	}
	
	/**
	 * Check whether this stream has a particular flag set.
	 */
	public boolean hasFlag(FlagTypes flag) {
		return _flags.contains(flag);
	}

	/**
	 * @return The name used to retrieve segments of this stream (not including the segment number).
	 */
	public ContentName getBaseName() {
		return _baseName;
	}

	/**
	 * @return The version of the stream being read, if its name is versioned.
	 */
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

	@Override
	public int read(byte[] buf, int offset, int len) throws IOException {

		if (null == buf)
			throw new NullPointerException("Buffer cannot be null!");

		return readInternal(buf, offset, len);
	}

	/**
	 * Actual mechanism used to trigger segment retrieval and perform content reads. 
	 * Subclasses define different schemes for retrieving content across segments.
	 * @param buf As in read(byte[], int, int).
	 * @param offset As in read(byte[], int, int).
	 * @param len As in read(byte[], int, int).
	 * @return As in read(byte[], int, int).
	 * @throws IOException if a segment cannot be retrieved, or there is an error in lower-level
	 * 		segment retrieval mechanisms. Uses subclasses of IOException to help provide
	 * 		more information. In particular, throws NoMatchingContentFoundException when
	 * 		no content found within the timeout given.
	 */
	protected abstract int readInternal(byte [] buf, int offset, int len) throws IOException;

	/**
	 * Called to set the first segment when opening a stream. This does initialization
	 * and setup particular to the first segment of a stream. Subclasses should not override
	 * unless they really know what they are doing. Calls #setCurrentSegment(ContentObject)
	 * for the first segment. If the content is encrypted, and keys are not provided
	 * for this stream, they are looked up according to the namespace. Note that this
	 * assumes that all segments of a given piece of content are either encrypted or not.
	 * @param newSegment Must not be null
	 * @throws IOException If newSegment is null or decryption keys set up incorrectly
	 */
	protected void setFirstSegment(ContentObject newSegment) throws IOException {
		if (null == newSegment) {
			throw new NoMatchingContentFoundException("Cannot find first segment of " + getBaseName());
		}
		
		LinkObject theLink = null;
		
		while (newSegment.isType(ContentType.LINK) && (!hasFlag(FlagTypes.DONT_DEREFERENCE))) {
			// Automated dereferencing. Want to make a link object to read in this link, then
			// dereference it to get the segment we really want. We then fix up the _baseName,
			// and continue like nothing ever happened. 
			theLink = new LinkObject(newSegment, _handle);
			pushDereferencedLink(theLink); // set _dereferencedLink to point to the new link, pushing
					// old ones down the stack if necessary
			
			// dereference will check for link cycles
			newSegment = _dereferencedLink.dereference(_timeout);
			if (Log.isLoggable(Level.INFO))
				Log.info("CCNAbstractInputStream: dereferencing link {0} to {1}, resulting data {2}", theLink.getVersionedName(),
						theLink.link(), ((null == newSegment) ? "null" : newSegment.name()));
			if (newSegment == null) {
				// TODO -- catch error states. Do we throw exception or return null?
				// Set error states -- when do we find link cycle and set the error on the link?
				// Clear error state when update is successful.
				// Two cases -- link loop or data not found.
				if (_dereferencedLink.hasError()) {
					if (_dereferencedLink.getError() instanceof LinkCycleException) {
						// Leave the link set on the input stream, so that caller can explore errors.
						if (Log.isLoggable(Level.WARNING)) {
							Log.warning("Hit link cycle on link {0} pointing to {1}, cannot dereference. See this.dereferencedLink() for more information!",
								_dereferencedLink.getVersionedName(), _dereferencedLink.link().targetName());
						}
					}
					// Might also cover NoMatchingContentFoundException here...for now, just return null
					// so can call it more than once.
					throw _dereferencedLink.getError();
				} else {
					throw new NoMatchingContentFoundException("Cannot find first segment of " + getBaseName() + ", which is a link pointing to " + _dereferencedLink.link().targetName());					
				}
			}
			_baseName = SegmentationProfile.segmentRoot(newSegment.name());
			// go around again, 
		}
		
		if (newSegment.isType(ContentType.GONE)) {
			_goneSegment = newSegment;
			if (Log.isLoggable(Level.INFO))
				Log.info("getFirstSegment: got gone segment: {0}", _goneSegment.name());
		} else if (newSegment.isType(ContentType.ENCR) && (null == _keys)) {
			// The block is encrypted and we don't have keys
			// Get the content name without the segment parent
			ContentName contentName = SegmentationProfile.segmentRoot(newSegment.name());
			// Attempt to retrieve the keys for this namespace
			_keys = AccessControlManager.keysForInput(contentName, newSegment.signedInfo().getPublisherKeyID(), _handle);
			if (_keys == null) throw new AccessDeniedException("Cannot find keys to decrypt content.");
		}
		setCurrentSegment(newSegment);
	}

	/**
	 * Set up current segment for reading, including preparation for decryption if necessary.
	 * Called after getSegment/getFirstSegment/getNextSegment, which take care of verifying
	 * the segment for us. Assumes newSegment has been verified.
	 * @throws IOException If decryption keys set up incorrectly
	 */
	protected void setCurrentSegment(ContentObject newSegment) throws IOException {
		_currentSegment = null;
		_segmentReadStream = null;
		if (null == newSegment) {
			if (Log.isLoggable(Level.INFO))
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
			// if we're decrypting, then set it up now
			if (_keys != null) {
				// We only do automated lookup of keys on first segment. Otherwise
				// we assume we must have the keys or don't try to decrypt.
				try {
					// Reuse of current segment OK. Don't expect to have two separate readers
					// independently use this stream without state confusion anyway.
					
					// Assume getBaseName() returns name without segment information.
					// Log verification only on highest log level (won't execute on lower logging level).
					if ( Log.isLoggable(Level.FINEST ))
						Log.finest("Assert check: does getBaseName() match segmentless part of _currentSegment.name()? {0}",
							   (SegmentationProfile.segmentRoot(_currentSegment.name()).equals(getBaseName())));
					
					_cipher = _keys.getSegmentDecryptionCipher(getBaseName(), _publisher,
							SegmentationProfile.getSegmentNumber(_currentSegment.name()));
				} catch (InvalidKeyException e) {
					Log.warning("InvalidKeyException: " + e.getMessage());
					throw new IOException("InvalidKeyException: " + e.getMessage());
				} catch (InvalidAlgorithmParameterException e) {
					Log.warning("InvalidAlgorithmParameterException: " + e.getMessage());
					throw new IOException("InvalidAlgorithmParameterException: " + e.getMessage());
				}

				// Let's optimize random access to this buffer (e.g. as used by the decoders) by
				// decrypting a whole ContentObject at a time. It's not a huge security risk,
				// and right now we can't rewind the buffers so if we do try to decode out of
				// an encrypted block we constantly restart from the beginning and redecrypt
				// the content. 
				// Previously we used our own UnbufferedCipherInputStream class directly as
				// our _segmentReadStream for encrypted data, as Java's CipherInputStreams
				// assume block-oriented boundaries for decryption, and buffer incorrectly as a result.
				// If we want to go back to incremental decryption, putting a small cache into that
				// class to optimize going backwards would help.

				// Unless we use a compressing cipher, the maximum data length for decrypted data
				//  is _currentSegment.content().length. But we might as well make something
				// general that will handle all cases. There may be a more efficient way to
				// do this; want to minimize copies. 
				byte [] bodyData = _cipher.update(_currentSegment.content());
				byte[] tailData;
				try {
					tailData = _cipher.doFinal();
				} catch (IllegalBlockSizeException e) {
					Log.warning("IllegalBlockSizeException: " + e.getMessage());
					throw new IOException("IllegalBlockSizeException: " + e.getMessage());
				} catch (BadPaddingException e) {
					Log.warning("BadPaddingException: " + e.getMessage());
					throw new IOException("BadPaddingException: " + e.getMessage());
				}
				if ((null == tailData) || (0 == tailData.length)) {
					_segmentReadStream = new ByteArrayInputStream(bodyData);
				} 
				else if ((null == bodyData) || (0 == bodyData.length)) {
					_segmentReadStream = new ByteArrayInputStream(tailData);						
				}
				else {
					byte [] allData = new byte[bodyData.length + tailData.length];
					// Still avoid 1.6 array ops
					System.arraycopy(bodyData, 0, allData, 0, bodyData.length);
					System.arraycopy(tailData, 0, allData, bodyData.length, tailData.length);
					_segmentReadStream = new ByteArrayInputStream(allData);
				}
			} else {
				if (_currentSegment.signedInfo().getType().equals(ContentType.ENCR)) {
					// We only do automated lookup of keys on first segment.
					Log.warning("Asked to read encrypted content, but not given a key to decrypt it. Decryption happening at higher level?");
				}
				_segmentReadStream = new ByteArrayInputStream(_currentSegment.content());
			}
		}
	}

	/**
	 * Rewinds read buffers for current segment to beginning of the segment.
	 * @throws IOException
	 */
	protected void rewindSegment() throws IOException {
		if (null == _currentSegment) {
			if (Log.isLoggable(Level.INFO))
				Log.info("Cannot rewind null segment.");
		}
		if (null == _segmentReadStream) {
			setCurrentSegment(_currentSegment);
		}
		_segmentReadStream.reset(); // will reset to 0 if mark not called
	}

	/**
	 * Retrieves a specific segment of this stream, indicated by segment number.
	 * Three navigation options: get first (leftmost) segment, get next segment,
	 * or get a specific segment.
	 * Have to assume that everyone is using our segment number encoding. Probably
	 * easier to ask raw streams to use that encoding (e.g. for packet numbers)
	 * than to flag streams as to whether they are using integers or segments.
	 * @param number Segment number to retrieve. See SegmentationProfile for numbering.
	 * 		If we already have this segment as #currentSegmentNumber(), will just
	 * 		return the current segment, and will not re-retrieve it from the network.
	 * @throws IOException If no matching content found (actually throws NoMatchingContentFoundException)
	 *  	or if there is an error at lower layers.
	 **/
	protected ContentObject getSegment(long number) throws IOException {

		if (_currentSegment != null) {
			// what segment do we have right now?  maybe we already have it
			if (currentSegmentNumber() == number){
				// we already have this segment... just use it
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
	 * Checks whether we might have a next segment.
	 * @return Returns false if this content is marked as GONE (see ContentType), or if we have
	 * 		retrieved the segment marked as the last one, or, in a very rare case, if we're
	 * 		reading content that does not have segment markers.
	 */
	protected boolean hasNextSegment() throws IOException {

		// We're looking at content marked GONE
		if (null != _goneSegment) {
			if (Log.isLoggable(Level.INFO))
				Log.info("getNextSegment: We have a gone segment, no next segment. Gone segment: {0}", _goneSegment.name());
			return false;
		}
		
		if (null == _currentSegment) {
			if (Log.isLoggable(Level.SEVERE))
				Log.severe("hasNextSegment() called when we have no current segment!");
			throw new IOException("hasNextSegment() called when we have no current segment!");
		}

		// Check to see if finalBlockID is the current segment. If so, there should
		// be no next segment. (If the writer makes a mistake and guesses the wrong
		// value for finalBlockID, they won't put that wrong value in the segment they're
		// guessing itself -- unless they want to try to extend a "closed" stream.
		// Normally by the time they write that segment, they either know they're done or not.
		if (null != _currentSegment.signedInfo().getFinalBlockID()) {
			if (Arrays.equals(_currentSegment.signedInfo().getFinalBlockID(), _currentSegment.name().lastComponent())) {
				if (Log.isLoggable(Level.INFO)) {
					Log.info("getNextSegment: there is no next segment. We have segment: " + 
						DataUtils.printHexBytes(_currentSegment.name().lastComponent()) + " which is marked as the final segment.");
				}
				return false;
			}
		}
		
		if (!SegmentationProfile.isSegment(_currentSegment.name())) {
			if (Log.isLoggable(Level.INFO))
				Log.info("Unsegmented content: {0}. No next segment.", _currentSegment.name());
			return false;
		}
		return true;
	}

	/**
	 * Retrieve the next segment of the stream. Convenience method, uses #getSegment(long).
	 * @return the next segment, if found.
	 * @throws IOException
	 */
	protected ContentObject getNextSegment() throws IOException {
		if (null == _currentSegment) {
			if (Log.isLoggable(Level.INFO))
				Log.info("getNextSegment: no current segment, getting first segment.");
			return getFirstSegment();
		}
		if (Log.isLoggable(Level.INFO))
			Log.info("getNextSegment: getting segment after {0}", _currentSegment.name());
		return getSegment(nextSegmentNumber());
	}

	/**
	 * Retrieves the first segment of the stream, based on specified startingSegmentNumber 
	 * (see #CCNAbstractInputStream(ContentName, Long, PublisherPublicKeyDigest, ContentKeys, CCNHandle)).
	 * Convenience method, uses #getSegment(long).
	 * @return the first segment, if found.
	 * @throws IOException If can't get a valid starting segment number
	 */
	protected ContentObject getFirstSegment() throws IOException {
		if (null != _startingSegmentNumber) {
			ContentObject firstSegment = getSegment(_startingSegmentNumber);
			if (Log.isLoggable(Level.INFO)) {
				Log.info("getFirstSegment: segment number: " + _startingSegmentNumber + " got segment? " + 
					((null == firstSegment) ? "no " : firstSegment.name()));
			}
			return firstSegment;
		} else {
			throw new IOException("Stream does not have a valid starting segment number.");
		}
	}

	/**
	 * Method to determine whether a retrieved block is the first segment of this stream (as
	 * specified by startingSegmentNumber, (see #CCNAbstractInputStream(ContentName, Long, PublisherPublicKeyDigest, ContentKeys, CCNHandle)).
	 * Overridden by subclasses to implement narrower constraints on names. Once first
	 * segment is retrieved, further segments can be identified just by segment-naming
	 * conventions (see SegmentationProfile).
	 * 
	 * @param desiredName The expected name prefix for the stream. 
	 * 	For CCNAbstractInputStream, assume that desiredName contains the name up to but not including
	 * 	segmentation information.
	 * @param segment The potential first segment.
	 * @return True if it is the first segment, false otherwise.
	 */
	protected boolean isFirstSegment(ContentName desiredName, ContentObject segment) {
		if ((null != segment) && (SegmentationProfile.isSegment(segment.name()))) {
			if (Log.isLoggable(Level.INFO))
				Log.info("is {0} a first segment of {1}", segment.name(), desiredName);
			// In theory, the segment should be at most a versioning component different from desiredName.
			// In the case of complex segmented objects (e.g. a KeyDirectory), where there is a version,
			// then some name components, then a segment, desiredName should contain all of those other
			// name components -- you can't use the usual versioning mechanisms to pull first segment anyway.
			if (!desiredName.equals(SegmentationProfile.segmentRoot(segment.name()))) {
				if (Log.isLoggable(Level.INFO))
					Log.info("Desired name :{0} is not a prefix of segment: {1}",desiredName, segment.name());
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
	 * If we traversed a link to get this object, make it available.
	 */
	public synchronized LinkObject getDereferencedLink() { return _dereferencedLink; }
	
	/**
	 * Use only if you know what you are doing.
	 */
	protected synchronized void setDereferencedLink(LinkObject dereferencedLink) { _dereferencedLink = dereferencedLink; }
	
	/**
	 * Add a LinkObject to the stack we had to dereference to get here.
	 */
	protected synchronized void pushDereferencedLink(LinkObject dereferencedLink) {
		if (null == dereferencedLink) {
			return;
		}
		if (null != _dereferencedLink) {
			if (null != dereferencedLink.getDereferencedLink()) {
				if (Log.isLoggable(Level.WARNING)) {
					Log.warning("Merging two link stacks -- {0} already has a dereferenced link from {1}. Behavior unpredictable.",
							dereferencedLink.getVersionedName(), dereferencedLink.getDereferencedLink().getVersionedName());
				}
			}
			dereferencedLink.pushDereferencedLink(_dereferencedLink);
		}
		setDereferencedLink(dereferencedLink);
	}

	/**
	 * Verifies the signature on a segment using cached bulk signature data (from Merkle Hash Trees)
	 * if it is available.
	 * TODO -- check to see if it matches desired publisher.
	 * @param segment the segment whose signature to verify in the context of this stream.
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
				return segment.verify(_handle.keyManager());
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
					if (Log.isLoggable(Level.INFO)) {
						Log.info("Verification failure: " + segment.name() + " timestamp: " + segment.signedInfo().getTimestamp() + " content length: " + segment.contentLength() + 
							" proxy: " + DataUtils.printBytes(proxy) +
							" expected proxy: " + DataUtils.printBytes(_verifiedProxy));
					}
	 				return false;
				}
			} else {
				// Verifying a new segment. See if the signature verifies, otherwise store the signature
				// and proxy.
				if (!ContentObject.verify(proxy, segment.signature().signature(), segment.signedInfo(), segment.signature().digestAlgorithm(), _handle.keyManager())) {
					Log.warning("Found segment: " + segment.name().toString() + " whose signature fails to verify; segment length: " + segment.contentLength() + ".");
					return false;
				} else {
					// Remember current verifiers
					_verifiedRootSignature = segment.signature().signature();
					_verifiedProxy = proxy;
				}
			}
			if (Log.isLoggable(Level.INFO))
				Log.info("Got segment: {0}, verified.", segment.name());
		} catch (Exception e) {
			Log.warning("Got an " + e.getClass().getName() + " exception attempting to verify segment: " + segment.name().toString() + ", treat as failure to verify.");
			Log.warningStackTrace(e);
			return false;
		}
		return true;
	}

	/**
	 * Returns the segment number for the next segment.
	 * Default segmentation generates sequentially-numbered stream
	 * segments but this method may be overridden in subclasses to 
	 * perform re-assembly on streams that have been segmented differently.
	 * @return The index of the next segment of stream data.
	 */
	public long nextSegmentNumber() {
		if (null == _currentSegment) {
			return _startingSegmentNumber.longValue();
		} else {
			return segmentNumber() + 1;
		}
	}

	/**
	 * @return Returns the segment number of the current segment if we have one, otherwise
	 * the expected startingSegmentNumber.
	 */
	public long segmentNumber() {
		if (null == _currentSegment) {
			return _startingSegmentNumber;
		} else {
			// This needs to work on streaming content that is not traditional fragments.
			// The segmentation profile tries to do that, though it is seeming like the
			// new segment representation means we will have to assume that representation
			// even for stream content.
			return SegmentationProfile.getSegmentNumber(_currentSegment.name());
		}
	}

	/**
	 * @return Returns the segment number of the current segment if we have one, otherwise -1.
	 */
	protected long currentSegmentNumber() {
		if (null == _currentSegment) {
			return -1; // make sure we don't match inappropriately
		}
		return segmentNumber();
	}

	/**
	 * Checks to see whether this content has been marked as GONE (deleted). Will retrieve the first
	 * segment if we do not already have it in order to make this determination.
	 * @return true if stream is GONE.
	 * @throws NoMatchingContentFound exception if no first segment found
	 * @throws IOException if there is other difficulty retrieving the first segment.
	 */
	public boolean isGone() throws NoMatchingContentFoundException, IOException {

		// TODO: once first segment is always read in constructor this code will change
		if (null == _currentSegment) {
			ContentObject firstSegment = getFirstSegment();
			setFirstSegment(firstSegment); // sets _goneSegment, does link dereferencing,
					// throws NoMatchingContentFoundException if firstSegment is null.
			// this way all retry behavior is localized in the various versions of getFirstSegment.
			// Previously what would happen is getFirstSegment would be called by isGone, return null,
			// and we'd have a second chance to catch it on the call to update if things were slow. But
			// that means we would get a more general update on a gone object.  
		}
		// We might have set first segment in constructor, in which case we will also have set _goneSegment
		if (null != _goneSegment) {
			return true;
		}
		return false;
	}

	/**
	 * 
	 * @return Return the single segment of a stream marked as GONE.
	 */
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
	 * @return the publisher of the data in the stream (either as requested, or once we have
	 * data, as observed).
	 */
	public PublisherPublicKeyDigest publisher() {
		return _publisher;
	}

	/**
	 * @return the key locator for this stream's publisher.
	 */
	public KeyLocator publisherKeyLocator() {
		return _publisherKeyLocator;		
	}

	/**
	 * @return the name of the current segment held by this string, or "null". Used for debugging.
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

	/**
	 * @return Whether this stream believes it is at eof (has read past the end of the 
	 *   last segment of the stream).
	 */
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
		
		// Shouldn't have a problem if we are GONE, and don't want to
		// deal with exceptions raised by a call to isGone.
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
		if (Log.isLoggable(Level.FINEST))
			Log.finest("mark: block: " + segmentNumber() + " offset: " + _markOffset);
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public synchronized void reset() throws IOException {
		
		if (isGone())
			return;
		
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
					if (Log.isLoggable(Level.FINEST))
						Log.finest("reset within block: block: " + segmentNumber() + " offset: " + _markOffset + " eof? " + _atEOF);
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
		if (Log.isLoggable(Level.FINEST))
			Log.finest("reset: block: " + segmentNumber() + " offset: " + _markOffset + " eof? " + _atEOF);
	}

	@Override
	public long skip(long n) throws IOException {
		
		if (isGone())
			return 0;
		if (Log.isLoggable(Level.INFO))
			Log.info("in skip("+n+")");

		if (n < 0) {
			return 0;
		}

		return readInternal(null, 0, (int)n);
	}

	/**
	 * @return Currently returns 0. Can be optionally overridden by subclasses.
	 * @throws IOException
	 */
	protected int segmentCount() throws IOException {
		return 0;
	}

	/**
	 * Seek a stream to a specific byte offset from the start. Tries to avoid retrieving
	 * extra segments.
	 * @param position
	 * @throws IOException
	 */
	public void seek(long position) throws IOException {
		if (isGone())
			return; // can't seek gone stream
		Log.info("Seeking stream to {0}", position);
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

	/**
	 * @return Returns position in byte offset. For CCNAbstractInputStream, provide an inadequate
	 *   base implementation that returns the offset into the current segment (not the stream as
	 *   a whole).
	 * @throws IOException
	 */
	public long tell() throws IOException {
		if (isGone())
			return 0;
		return _currentSegment.contentLength() - _segmentReadStream.available();
	}

	/**
	 * @return Total length of the stream, if known, otherwise -1.
	 * @throws IOException
	 */
	public long length() throws IOException {
		return -1;
	}
}