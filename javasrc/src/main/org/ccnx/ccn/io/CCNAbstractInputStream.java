/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.logging.Level;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
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
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * This abstract class is the superclass of all classes representing an input stream of
 * bytes segmented and stored in CCN.
 *
 * @see SegmentationProfile for description of CCN segmentation
 */
public abstract class CCNAbstractInputStream extends InputStream implements CCNContentHandler {

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

	/**
	 * Definitions for flags:
	 * DONT_DEREFERENCE:		This is used to avoid automatic dereferencing when reading in a link itself
	 * BLOCKING:				Wait until we see the first segment of the file before we start using timeouts.
	 * 							Useful for waiting for an object which has not yet been created.
	 * BLOCK_AFTER_FIRST_SEGMENT:	Use timeout only for first segment, then wait indefinitely for subsequent
	 * 							ones. Useful for data that is being manufactured on the fly such as slow live feeds.
	 */
	public enum FlagTypes { DONT_DEREFERENCE, BLOCKING, BLOCK_AFTER_FIRST_SEGMENT };

	protected EnumSet<FlagTypes> _flags = EnumSet.noneOf(FlagTypes.class);

	/**
	 * The segment we are currently reading from.
	 */
	protected ContentObject _currentSegment = null;

	/**
	 *  first segment of the stream we are reading, which is the GONE segment (see ContentType) if content is deleted.
	 *  this cached first segment is used to supply certain information it contains, such as for computing digest only
	 *  when required
	 */
	private ContentObject _firstSegment = null;

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

	protected boolean _atEOF = false;

	/**
	 * Used for mark(int) and reset().
	 */
	protected int _readlimit = 0;
	protected int _markOffset = 0;
	protected long _markBlock = 0;

	protected ArrayList<ContentObject> inOrderSegments = new ArrayList<ContentObject>();
	protected ArrayList<ContentObject> outOfOrderSegments = new ArrayList<ContentObject>();

	protected long _nextPipelineSegment = -1;  //this is the segment number of the next segment needed
	protected long _lastRequestedPipelineSegment = -1;  //this is the segment number of the last interest we sent out
	protected long _lastInOrderSegment = -1;
	protected ContentName _basePipelineName = null;
	protected long _lastSegmentNumber = -1;
	protected ArrayList<Interest> _sentInterests = new ArrayList<Interest>();
	private long waitingSegment;
	private long _holes = 0;
	private long _totalReceived = 0;
	private long _pipelineStartTime;
	private final Object readerReadyObj = new Object();
	private long readerReadyVal = -1;

	private double avgResponseTime = -1;

	private final Object processingSegmentLock = new Object();
	private long processingSegment = -1;

	private final int processingDefer = 0;

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
		if (SegmentationProfile.isSegment(baseName)) {
			_startingSegmentNumber = SegmentationProfile.getSegmentNumber(baseName);
			_baseName = baseName.parent();
		} else {
			_startingSegmentNumber = SegmentationProfile.baseSegment();
		}
		if (startingSegmentNumber != null) {
			_startingSegmentNumber = startingSegmentNumber;
		}
		//TODO this base name does not include the version!!!!!!!!!
		Log.info(Log.FAC_IO, "CCNAbstractInputStream: {0} segment {1}", _baseName, _startingSegmentNumber);
		startPipeline();
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
		Log.info(Log.FAC_IO, "CCNAbstractInputStream: {0} segment {1}", _baseName, _startingSegmentNumber);
		startPipeline();
	}


	private void startPipeline() {
		synchronized (inOrderSegments) {
			Log.info(Log.FAC_PIPELINE, "PIPELINE: starting pipelining");

			_pipelineStartTime = System.currentTimeMillis();
			if (SystemConfiguration.PIPELINE_STATS)
				System.out.println("plot "+(System.currentTimeMillis() - _pipelineStartTime)+" inOrder: "+inOrderSegments.size() +" outOfOrder: "+outOfOrderSegments.size() + " interests: "+_sentInterests.size() +" holes: "+_holes + " received: "+_totalReceived+" ["+_baseName+"].1"+ "avgResponseTime "+avgResponseTime);

			long segmentToGet = -1;
			Interest interest = null;

			if(_basePipelineName == null) {
				_basePipelineName = _baseName;
			}

			Log.info(Log.FAC_PIPELINE, "PIPELINE: BaseName for pipeline: {0} base name: {1}", _basePipelineName, _baseName);

			if (_currentSegment!=null) {
				Log.info(Log.FAC_PIPELINE, "PIPELINE: we already have the first segment...  start from there: {0}", _currentSegment.name());
				//we already have the starting segment...

				//is the first segment the last one?
				if (SegmentationProfile.isLastSegment(_currentSegment)) {
					//this is the last segment...  don't pipeline
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we already have the last segment...  don't need to pipeline (returning)");
					return;
				} else {
					//this isn't the last segment, start up pipelining...  only ask for next segment to start
					Log.info(Log.FAC_PIPELINE, "PIPELINE: this isn't the last segment...  need to start up pipelining");
				}
			} else {
				Log.info(Log.FAC_PIPELINE, "PIPELINE: need to get the first segment: startingSegmentNumber={0}",_startingSegmentNumber);
			}

			segmentToGet = nextSegmentNumber();
			_nextPipelineSegment = segmentToGet;

			//check here if it is an instance of a versioned stream.  if so, and the basename doesn't have a version in it, do not send the interest

			if (this instanceof CCNVersionedInputStream && !VersioningProfile.hasTerminalVersion(_basePipelineName)) {
				Log.info(Log.FAC_PIPELINE, "this is a versioned stream without a terminal version, skip sending non-versioned interest");
			} else {
				Log.info(Log.FAC_PIPELINE, "this is not a versioned stream or it is a versioned stream without the version set in the base name, go ahead and get the first segment");

				interest = SegmentationProfile.segmentInterest(_basePipelineName, segmentToGet, _publisher);
				try {
					interest.userTime = System.currentTimeMillis();
					_handle.expressInterest(interest, this);
					_sentInterests.add(interest);
					_lastRequestedPipelineSegment = segmentToGet;
					Log.info(Log.FAC_PIPELINE, "PIPELINE: expressed interest for segment {0} in startPipeline(): {1}", segmentToGet, interest);
				} catch(IOException e) {
					//could not express interest for next segment...  logging the error
					Log.warning(Log.FAC_PIPELINE, "Failed to express interest for pipelining segments in CCNAbstractInputStream:  Interest = {0}", interest.name());
				}
			}
		}
	}

	private void receivePipelineContent(ContentObject co) {
		long returnedSegment = SegmentationProfile.getSegmentNumber(co.name());
		ArrayList<Interest> toRemove = new ArrayList<Interest>();

		//are we at the last segment?
		synchronized(inOrderSegments) {
			if (SegmentationProfile.isLastSegment(co)) {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we just got the last segment...");
				_lastSegmentNumber = returnedSegment;
				_lastRequestedPipelineSegment = returnedSegment;
			}
			//}
			long segNum;
			//synchronized (_sentInterests) {
			for(Interest i: _sentInterests) {
				segNum = SegmentationProfile.getSegmentNumber(i.name());
				if(segNum == returnedSegment || (_lastSegmentNumber > -1 && segNum > _lastSegmentNumber)) {
					if(Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
						Log.info(Log.FAC_PIPELINE, "PIPELINE: cancelling interest for segment "+SegmentationProfile.getSegmentNumber(i.name())+" Interest: "+i);
					}
					_handle.cancelInterest(i, this);
					toRemove.add(i);
				}
			}
			_sentInterests.removeAll(toRemove);
			toRemove.clear();
			//_lastRequestedPipelineSegment = returnedSegment;

		//no good reason to release the lock here...
		//}
		//synchronized(inOrderSegments) {
			if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
				Log.info(Log.FAC_PIPELINE, "PIPELINE: received pipeline segment: {0}", co.name());

			if (returnedSegment == _nextPipelineSegment) {
				_totalReceived++;
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we got the segment ({0}) we were expecting!", returnedSegment);
				if(waitingSegment!=-1)
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: someone is waiting for segment: {0}", waitingSegment);
				//this is the next segment in order
				inOrderSegments.add(co);
				_lastInOrderSegment = returnedSegment;
				//do we have any out of order segments to move over?
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
					Log.info(Log.FAC_PIPELINE, "PIPELINE: before checking ooos:" );
					printSegments();
				}
				if (outOfOrderSegments.size() > 0 ) {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: we have out of order segments to check");

					//this was a hole..  cancel its other interests

					while (outOfOrderSegments.size() > 0 ) {
						if(SegmentationProfile.getSegmentNumber(outOfOrderSegments.get(0).name()) == nextInOrderSegmentNeeded()) {
							_lastInOrderSegment = SegmentationProfile.getSegmentNumber(outOfOrderSegments.get(0).name());
							inOrderSegments.add(outOfOrderSegments.remove(0));
						} else {
							//the first one isn't what we wanted..
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
								Log.info(Log.FAC_PIPELINE, "PIPELINE: we have "+SegmentationProfile.getSegmentNumber(outOfOrderSegments.get(0).name())+" but need "+nextInOrderSegmentNeeded()+" breaking from loop, we don't have the one we need");
							}
							break;
						}
					}
				}
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
					Log.info(Log.FAC_PIPELINE, "PIPELINE: after checking ooos: ");
					printSegments();
				}

				//if we had out of order segments, we might still want to advance the pipeline...


			} else {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we got segment {0} an Out of Order segment...  we were expecting segment {1}", returnedSegment, _nextPipelineSegment);
				//this segment is out of order
				//make sure it wasn't a segment that we don't need any more...
				if (_nextPipelineSegment > returnedSegment || returnedSegment > _lastRequestedPipelineSegment) {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: this is an out of range segment...  drop");
					returnedSegment = -1;
				} else {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: this is a pipeline segment, add to outOfOrderSegment queue");
					_totalReceived++;
					_holes++;
					int i = 0;
					for (ContentObject c:outOfOrderSegments) {
						if(returnedSegment < SegmentationProfile.getSegmentNumber(c.name()))
							break;
						i++;
					}
					outOfOrderSegments.add(i, co);

					//now we have a hole to fill
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: we got a segment out of order, need to fill a hole at "+nextInOrderSegmentNeeded());
					attemptHoleFilling(_nextPipelineSegment);
				}
			}


			_nextPipelineSegment = nextInOrderSegmentNeeded();
			if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
				Log.info(Log.FAC_PIPELINE, "PIPELINE: the next segment needed is {0}", _nextPipelineSegment);
			synchronized(processingSegmentLock) {
				processingSegment = -1;
			}

			if(returnedSegment == waitingSegment) {
				inOrderSegments.notifyAll();
			}
		}
	}

	private void advancePipeline() {
		synchronized(inOrderSegments) {
			//first check if we have tokens to spend on interests...
			boolean doneAdvancing = false;

			//check outstanding interests
			if(Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
				String s = "have interests out for segments: [";
				for(Interest i: _sentInterests)
					s = s + " "+SegmentationProfile.getSegmentNumber(i.name());
				s = s + " ]";
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: "+s);
				for(Interest i: _sentInterests)
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: {0}", i.name());
			}

			Interest i = null;

			while (_sentInterests.size() + inOrderSegments.size() + outOfOrderSegments.size() + processingDefer < SystemConfiguration.PIPELINE_SIZE && !doneAdvancing) {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: _sentInterests.size() = {0} inOrderSegments.size() = {1} outOfOrderSegments.size()  = {2} processingDefer = {3} total = {4}", _sentInterests.size(), inOrderSegments.size(), outOfOrderSegments.size(), processingDefer, (_sentInterests.size() + inOrderSegments.size() + outOfOrderSegments.size() + processingDefer) );

				//we have tokens to use
				i = null;

				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: _lastSegmentNumber = {0}", _lastSegmentNumber);

				//if we haven't gotten a valid base segment, we do not want to advance the pipeline.
				if (_lastRequestedPipelineSegment == SegmentationProfile.baseSegment()) {
					Log.info(Log.FAC_PIPELINE, "PIPELINE: the last segment number is the base segment, need to make sure we have received the base segment before we press on");
					//the last thing we asked for was the base segment...  have we gotten it yet?
					if (_lastInOrderSegment == -1) {
						Log.info(Log.FAC_PIPELINE, "PIPELINE: _lastInOrderSegment == -1, we have not received the base segment, do not advance the pipeline");
						return;
					} else {
						Log.info(Log.FAC_PIPELINE, "PIPELINE: _lastInOrderSegment == {0}, we have received the base segment, we can advance the pipeline!", _lastInOrderSegment);
					}
				}

				if (_lastSegmentNumber == -1 || _lastRequestedPipelineSegment < _lastSegmentNumber) {
					//we don't have the last segment already...
					i = SegmentationProfile.segmentInterest(_basePipelineName, _lastRequestedPipelineSegment + 1, _publisher);
					//have we already expressed this interest?
					Log.info(Log.FAC_PIPELINE, "PIPELINE: in advancePipeline _lastRequestedPipelineSegment {0}", _lastRequestedPipelineSegment);
					try {
						i.userTime = System.currentTimeMillis();
						_handle.expressInterest(i, this);
						_sentInterests.add(i);
						_lastRequestedPipelineSegment++;
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: requested segment "+_lastRequestedPipelineSegment +" ("+(SystemConfiguration.PIPELINE_SIZE - _sentInterests.size())+" tokens)");
					} catch (IOException e) {
						// This could happen if the handle got closed underneath us - maybe that's OK?
						// For now will leave it as a warning
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.WARNING))
							Log.warning(Log.FAC_PIPELINE, "failed to express interest for CCNAbstractInputStream pipeline: {0}");
						break;
					}
				} else {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: setting doneAdvancing to true");
					doneAdvancing = true;
				}
			}
		}
	}

	private void attemptHoleFilling() {
		synchronized(inOrderSegments) {
			if(outOfOrderSegments.size() > 0) {
				long firstOOO = SegmentationProfile.getSegmentNumber(outOfOrderSegments.get(0).name());
				long holeCheck = _nextPipelineSegment;
				while (holeCheck < firstOOO) {
					attemptHoleFilling(holeCheck);
					holeCheck++;
				}
			}
		}

	}

	private void attemptHoleFilling(long hole) {
		//holes...  just ask for the next segment we are expecting if we haven't already

		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: checking for a hole at segment: {0}", hole);

		//first check the incoming segment to see if it is here already
		synchronized (processingSegmentLock) {
			if(processingSegment != -1 && hole == processingSegment) {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: the segment is being processed... not a hole.");
				return;
			}

		}

		Interest i = SegmentationProfile.segmentInterest(_basePipelineName, hole, _publisher);
		boolean notRequested = true;

		int index = -1;
		int index2 = -1;

		long elapsed1 = -1;
		long elapsed2 = -1;

		long newUserTime = -1;

		Interest expressed;
		try {
			synchronized (inOrderSegments) {
				// see if this interest is already there
				index = _sentInterests.indexOf(i);
				if (index > -1) {
					expressed = _sentInterests.get(index);
					elapsed1 = System.currentTimeMillis() - expressed.userTime;
					newUserTime = expressed.userTime;

					if(elapsed1 == -1) {
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: base segment is there, but the express time is -1, it must be getting processed");
						return;
					}

					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: base interest is already there, try adding excludes elapsed time = {0} interest: {1}", elapsed1, expressed);
					notRequested = false;
				} else {
					// base interest isn't there, the the exclude could be.
				}


				//for (int attempt = 1; attempt < SystemConfiguration.PIPELINE_SEGMENTATTEMPTS; attempt++) {
				int attempt = 1;
				Exclude ex = new Exclude();
				ex.add(new byte[][]{SegmentationProfile.getSegmentNumberNameComponent(hole+attempt)});

				i.exclude(ex);

				Interest toDelete = null;
				index2 = 0;
				long excludedSegment = -1;
				ExcludeComponent ec = null;
				long tempseg = -1;
				for(Interest expInt: _sentInterests) {
					tempseg = SegmentationProfile.getSegmentNumber(expInt.name());
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: checking if this interest {0} is for our hole at {1}", tempseg, hole);
					if (tempseg == hole) {
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: this is a match!  does it have excludes?");
						notRequested = false;
						//this is the interest we want to look at
						if(expInt.exclude()!=null) {
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: yep! it is a holefilling attempt");
							ec = (ExcludeComponent)expInt.exclude().value(0);

							excludedSegment = SegmentationProfile.getSegmentNumber(ec.getBytes());
							attempt = (int) (excludedSegment - hole);
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: this is attempt: {0}", attempt);

							if (attempt < SystemConfiguration.PIPELINE_SEGMENTATTEMPTS) {
								if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
									Log.info(Log.FAC_PIPELINE, "PIPELINE: we have more attempts that we can try... ");
								toDelete = expInt;
								ex = new Exclude();
								ex.add(new byte[][]{SegmentationProfile.getSegmentNumberNameComponent(hole+attempt+1)});
								i.exclude(ex);
								if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
									Log.info(Log.FAC_PIPELINE, "PIPELINE: going to express the next attempt: {0}", i);
								newUserTime = expInt.userTime;
							} else {
								if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
									Log.info(Log.FAC_PIPELINE, "PIPELINE: we have tried as many times as we can...  break here");
								return;
							}
						} else {
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: this isn't a holefilling attempt, must be the base interest");
						}
						break;
					} else {
						//if this is for a segment after ours, break
						if (tempseg > hole)
							break;
					}

					index2++;
				}


				if(toDelete!=null) {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: we can try again to fill the hole!");
					expressed = toDelete;
					if(expressed.userTime == -1) {
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: hole filling segment is there, but the express time is -1, it must be getting processed");
						return;
					} else {
						elapsed2 = System.currentTimeMillis() - expressed.userTime;
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: elapsed2 time {0}", elapsed2);
						if(elapsed2 > avgResponseTime * SystemConfiguration.PIPELINE_RTTFACTOR && avgResponseTime > -1) {
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: expressing the next interest! {0} old express time {1} new express time {2}", i, expressed.userTime, newUserTime);
							//i.userTime = System.currentTimeMillis();
							i.userTime = newUserTime;
							_handle.expressInterest(i, this);
							_sentInterests.add(index2, i);

							_handle.cancelInterest(toDelete, this);
							_sentInterests.remove(toDelete);

							adjustAvgResponseTimeForHole();

							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
								Log.info(Log.FAC_PIPELINE, "PIPELINE: expressed: {0} deleted: {1}", i, toDelete);

								Log.info(Log.FAC_PIPELINE, "PIPELINE: current expressed interests: ");
								for(Interest p: _sentInterests)
									Log.info(Log.FAC_PIPELINE, "PIPELINE: {0}", p);
							}
							return;
						} else {
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: need to give the earlier attempt a chance to work");
							return;
						}
					}

				} else {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: we don't have any holefilling attempts... for {0}", hole);
					if (index == -1) {
						// the base interest wasn't even there (neither was the hole filling one)
						i.exclude(null);
					}
				}

				if((elapsed1 > avgResponseTime * 2 && avgResponseTime > -1) || (avgResponseTime == -1 && elapsed1 > SystemConfiguration.INTEREST_REEXPRESSION_DEFAULT) || notRequested) {
					//long userTime = System.currentTimeMillis();
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
						if (notRequested)
							Log.info(Log.FAC_PIPELINE, "PIPELINE: we don't have any holefilling interests pending for {0}, need to express one.  might need to reset pipeline state", hole);
						if(i.exclude() == null)
							Log.info(Log.FAC_PIPELINE, "PIPELINE: adding the base interest or the first holefilling attempt!!! {0}", i);
						else {
							Log.info(Log.FAC_PIPELINE, "PIPELINE: adding the first holefilling attempt! {0}",  i);
							//need to use the previous user time for this expression
							//userTime = userTime - elapsed1;

						}
					}
					//i.userTime = System.currentTimeMillis();
					if (newUserTime == -1)
						newUserTime = System.currentTimeMillis();
					i.userTime = newUserTime;
					_handle.expressInterest(i, this);
					if (index != -1)
						_sentInterests.add(index, i);
					else
						_sentInterests.add(i);
					// remove the first instance after we express and insert the new
					// interest
					if (index != -1) {
						_handle.cancelInterest(_sentInterests.remove(index+1), this);
						adjustAvgResponseTimeForHole();
					}

					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: requested segment {0} to fill hole: {1} with Interest: {2}", hole, i.name(), i);
					return;
				} else {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: we need to wait longer to see if the original interest will return the segment: avgResponseTime: {0}", avgResponseTime);
				}
			}
			//}
		} catch (IOException e) {
			if (Log.isLoggable(Log.FAC_PIPELINE, Level.WARNING))
				Log.warning(Log.FAC_PIPELINE, "failed to express interest for CCNAbstractInputStream pipeline");
		}
	}

	private void adjustAvgResponseTimeForHole() {
		synchronized (processingSegmentLock) {
			Log.info(Log.FAC_PIPELINE, "PIPELINE: before adjusting avgResponseTime for hole. avgResponseTime = {0}", avgResponseTime);
			avgResponseTime = 0.9 * avgResponseTime + 0.1 * (SystemConfiguration.PIPELINE_RTTFACTOR * avgResponseTime);
			Log.info(Log.FAC_PIPELINE, "PIPELINE: after adjusting avgResponseTime for hole. avgResponseTime = {0}", avgResponseTime);
		}
	}

	private void printSegments() {
		String s = "inOrder: [";
		for(ContentObject c: inOrderSegments)
			s += " "+SegmentationProfile.getSegmentNumber(c.name());
		s += " ] outOrder: [";
		for(ContentObject c: outOfOrderSegments)
			s += " "+SegmentationProfile.getSegmentNumber(c.name());
		s += "]";
		Log.info(Log.FAC_PIPELINE, "PIPELINE: " + s);
	}

	private long nextInOrderSegmentNeeded() {
		synchronized(inOrderSegments) {
            if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
                //removing all access to _currentSegment from the delivery thread
            	//if (_currentSegment==null)
                //    Log.info(Log.FAC_PIPELINE, "PIPELINE: current segment: - lastInOrderSegment number {0} _startingSegmentNumber {1}", _lastInOrderSegment, _startingSegmentNumber);
                //else
                //    Log.info(Log.FAC_PIPELINE, "PIPELINE: current segment: "+SegmentationProfile.getSegmentNumber(_currentSegment.name()) + " lastInOrderSegment number "+_lastInOrderSegment	+ " _startingSegmentNumber "+_startingSegmentNumber);
            	Log.info(Log.FAC_PIPELINE, "PIPELINE: lastInOrderSegment number {0} _startingSegmentNumber {1}", _lastInOrderSegment, _startingSegmentNumber);
				if (outOfOrderSegments.size() > 0) {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
						Log.info(Log.FAC_PIPELINE, "PIPELINE: we have out of order segments...");
                        printSegments();
                    }
				}
			}

			if (_lastInOrderSegment != -1)
				return _lastInOrderSegment +1;
			else
				return _startingSegmentNumber;
		}
	}

	private boolean haveSegmentBuffered(long segmentNumber) {
		synchronized(inOrderSegments) {
			ContentObject co = null;
			for (int i = 0; i < inOrderSegments.size(); i++) {
				co = inOrderSegments.get(i);
				if (SegmentationProfile.getSegmentNumber(co.name()) == segmentNumber) {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: have segment {0} in iOS, return true.", segmentNumber);
					return true;
				}
			}

			for (int i = 0; i < outOfOrderSegments.size(); i++) {
				co = outOfOrderSegments.get(i);
				if (SegmentationProfile.getSegmentNumber(co.name()) == segmentNumber) {
					//this is the segment we wanted
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: segment {0} is in our oOOS queue, return true", segmentNumber);
					return true;

				} else {
					if(SegmentationProfile.getSegmentNumber(co.name()) > segmentNumber) {
						//we have a hole to fill...
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: our out of order segments are past the requested segment...  we have a hole - not attempting to fill it at this time");
						//attemptHoleFilling(segmentNumber);
						break;
					}
				}
			}
			return false;
		}
	}

	private ContentObject getPipelineSegment(long segmentNumber) throws IOException{
		synchronized(inOrderSegments) {
			ContentObject co = null;
			while (inOrderSegments.size() > 0) {
				co = inOrderSegments.remove(0);
				if (SegmentationProfile.getSegmentNumber(co.name()) == segmentNumber) {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: had segment {0} in iOS, setting current.", segmentNumber);
					_currentSegment = co;
					if (inOrderSegments.size() > 0 || segmentNumber == 1)
						advancePipeline();
					else
						advancePipeline();
					return co;
				}
			}

			while (outOfOrderSegments.size() > 0) {
				co = outOfOrderSegments.get(0);
				if (SegmentationProfile.getSegmentNumber(co.name()) == segmentNumber) {
					//this is the segment we wanted
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: segment {0} was in our oOOS queue", segmentNumber);
					outOfOrderSegments.remove(0);
					_currentSegment = co;
					return co;
				} else {
					if(SegmentationProfile.getSegmentNumber(co.name()) > segmentNumber) {
						//we have a hole to fill...
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: our out of order segments are past the requested segment...  we have a hole");
						break;
					} else {
						outOfOrderSegments.remove(0);
					}
				}
			}

			if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
				Log.info(Log.FAC_PIPELINE, "PIPELINE: we do not have the segment yet...  was it requested?");
				Log.info(Log.FAC_PIPELINE, "PIPELINE: need segment: {0} _lastRequestedPipelineSegment: {1}", segmentNumber, _lastRequestedPipelineSegment);

				String s = "current interests out for segments: [";
				for(Interest i: _sentInterests)
					s += " "+SegmentationProfile.getSegmentNumber(i.name());
				s += "]";
				Log.info(Log.FAC_PIPELINE, "PIPELINE: "+s);
			}

			//need to actually get the requested segment if it hasn't been asked for
			//this is needed for seek, skip, etc

			//if we haven't requested the segment...  should we ditch everything we have?  probably
			if (requestedSegment(segmentNumber)) {
				//we already requested it.  just wait for it to come in
				attemptHoleFilling(segmentNumber);
			} else {
				//we haven't requested it...  send request and ditch what we have
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)){

					Log.info(Log.FAC_PIPELINE, "PIPELINE: detected a pipeline jump!!!!");

					Log.info(Log.FAC_PIPELINE, "PIPELINE: pipeline jump adjustment conditions");
					printSegments();
					String s = "pre-pipeline jump interests: [";
					for(Interest i: _sentInterests)
						s += " "+SegmentationProfile.getSegmentNumber(i.name());
					s += "]";
					Log.info(Log.FAC_PIPELINE, "PIPELINE: "+s);
				}

				Interest interest = SegmentationProfile.segmentInterest(_basePipelineName, segmentNumber, _publisher);
				try {
					//probably could just clear out some pipeline state instead of clearing all of it...
					interest.userTime = System.currentTimeMillis();
					_handle.expressInterest(interest, this);
					ArrayList<Object> toRemove = new ArrayList<Object>();
					long maxExpress = segmentNumber + SystemConfiguration.PIPELINE_SIZE-1;
					long lastExpressed = segmentNumber;
					long segNum;
					for (Interest i: _sentInterests) {
						segNum = SegmentationProfile.getSegmentNumber(i.name());
						if (segNum > maxExpress) {
							_handle.cancelInterest(i, this);
							toRemove.add(i);
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: canceling interest: {0}", i);
						} else {
							lastExpressed = segNum;
						}
					}
					//cancelInterests();
					_sentInterests.removeAll(toRemove);
					_sentInterests.add(0, interest);
					//resetPipelineState();
					//_lastRequestedPipelineSegment = segmentNumber;
					_nextPipelineSegment = segmentNumber;

					//now check our received segments
					for (ContentObject segment : inOrderSegments) {
						segNum = SegmentationProfile.getSegmentNumber(segment.name());
						if (segNum > maxExpress) {
							toRemove.add(segment);
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: removing segment past buffer limit after pipeline jump in inOrderSegments: {0}", segment);
						} else if (segNum < segmentNumber) {
							toRemove.add(segment);
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: removing segment before requested segment pipeline jump in inOrderSegments: {0}", segment);
						} else {
							if (lastExpressed < segNum) {
								lastExpressed = segNum;
							}
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: pipeline jump - will move segment in inOrderSegments: {0} to outOfOrderSegments", segment);
						}
					}
					inOrderSegments.removeAll(toRemove);

					toRemove.clear();
					for (ContentObject segment : outOfOrderSegments) {
						segNum = SegmentationProfile.getSegmentNumber(segment.name());
						if (segNum > maxExpress) {
							toRemove.add(segment);
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: removing segment past buffer limit after pipeline jump in outOfOrderSegments: {0}", segment);
						} else if (segNum < segmentNumber) {
							toRemove.add(segment);
							if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
								Log.info(Log.FAC_PIPELINE, "PIPELINE: removing segment before requested segment pipeline jump in outOfOrderSegments: {0}", segment);
						} else if (lastExpressed < segNum) {
							lastExpressed = segNum;
						}
					}
					outOfOrderSegments.removeAll(toRemove);

					long segNum2;
					//now move segments from in order to out of order...
					if (outOfOrderSegments.size() == 0) {
						outOfOrderSegments.addAll(inOrderSegments);
						Log.info(Log.FAC_PIPELINE, "PIPELINE: pipeline jump - moving segments from inOrderSegments to outOfOrderSegments");
					} else {
						ContentObject segX;
						for (int x = 0 ; x < inOrderSegments.size(); x++) {
							segX = inOrderSegments.get(x);
							segNum = SegmentationProfile.getSegmentNumber(segX.name());
							for (int y = 0 ; y < inOrderSegments.size(); y++) {
								segNum2 = SegmentationProfile.getSegmentNumber((outOfOrderSegments.get(y)).name());
								if (segNum < segNum2) {
									Log.info(Log.FAC_PIPELINE, "PIPELINE: pipeline jump - moving segment {0} to position {1} in outOfOrderSegments", segNum, y);
									outOfOrderSegments.add(y, segX);
								}
							}
							Log.info(Log.FAC_PIPELINE, "PIPELINE: pipeline jump - check buffered segments");
							printSegments();
						}
					}
					inOrderSegments.clear();


					//resetting some of the state...
					_lastRequestedPipelineSegment = lastExpressed;
					_lastInOrderSegment = segmentNumber - 1;

					Log.info(Log.FAC_PIPELINE, "PIPELINE: pipeline jump - we have now reset the state...  now we can try asking for the next segments - advance pipeline with holefilling");
					advancePipeline();

					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
						Log.info(Log.FAC_PIPELINE, "PIPELINE: we hadn't asked for segment {0} asking now... {1}", segmentNumber, interest);
						Log.info(Log.FAC_PIPELINE, "PIPELINE: pipeline jump _lastRequestedPipelineSegment {0} _lastInOrderSegment {1}", _lastRequestedPipelineSegment, _lastInOrderSegment);
						Log.info(Log.FAC_PIPELINE, "PIPELINE: pipeline jump adjustment results");
						printSegments();
						String s = "post-pipeline jump interests: [";
						for(Interest i: _sentInterests)
							s += " "+SegmentationProfile.getSegmentNumber(i.name());
						s += "]";
						Log.info(Log.FAC_PIPELINE, "PIPELINE: "+s);
					}


				} catch (IOException e) {
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.WARNING))
						Log.warning(Log.FAC_PIPELINE, "failed to express interest for CCNAbstractInputStream pipeline: {0}", e.getMessage());
				}
			}

			//check outstanding interests
			if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO)) {
				String s = "have interests out for segments: [";
				for(Interest i: _sentInterests)
					s += " "+SegmentationProfile.getSegmentNumber(i.name());
				s += "]";
				Log.info(Log.FAC_PIPELINE, "PIPELINE: "+s);
			}
		}

		return null;
	}

	private void cancelInterests() {
		synchronized(inOrderSegments) {
			for (Interest i: _sentInterests) {
				_handle.cancelInterest(i, this);
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: canceling interest: {0}", i);
			}
			_sentInterests.clear();
		}
	}

	private void resetPipelineState() {
		synchronized(inOrderSegments) {
			inOrderSegments.clear();
			outOfOrderSegments.clear();
			_nextPipelineSegment = -1;
			_lastRequestedPipelineSegment = -1;
			_lastInOrderSegment = -1;
			_lastSegmentNumber = -1;
			_currentSegment = null;
		}
	}


	private boolean requestedSegment(long number) {
		synchronized(processingSegmentLock) {
			if (processingSegment!=-1 && processingSegment == number) {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: someone is processing it right now!");
				return true;
			}
		}
		synchronized(inOrderSegments) {
			for (Interest i: _sentInterests)
				if(SegmentationProfile.getSegmentNumber(i.name()) == number)
					return true;
			return false;
		}
	}

	private void setPipelineName(ContentName n) {
		//we need to set the base name for pipelining...  we might not have had the version (or the full name)
		_basePipelineName = n;
		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: setting _basePipelineName {0}", _basePipelineName);

		synchronized(inOrderSegments) {
			//need to remove interest for first segment of old name
			ArrayList<Interest> remove = new ArrayList<Interest>();
			for(Interest i: _sentInterests) {
				if(SegmentationProfile.segmentRoot(i.name()).equals(_basePipelineName)) {
					//the name matches, keep it
				} else {
					//name doesn't match...  remove it
					remove.add(i);
				}
			}
			for(Interest i: remove) {
				_handle.cancelInterest(i,this);
				_sentInterests.remove(i);
			}
		}

	}


	public boolean readerReadyCheck(long nextSegment) {
		synchronized(inOrderSegments) {
			//is there a reader ready?
			long rr;
			synchronized(readerReadyObj) {
				rr = readerReadyVal;
			}
			if(rr > -1) {
				//there is a reader waiting
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: there is a reader waiting, we should wait unless we have their segment");
				if (nextSegment == rr) {
					//this is the segment they want, we should just finish
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: we are working on their segment...  we should finish!");
					return false;
					//break;
				} else {
					if (haveSegmentBuffered(rr)) {
						//we have their segment
						//this isn't their segment, but the one they want is here. we should defer
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: we are deferring until they are done");
						return true;
					} else {
						//we don't have their segment, we should keep going
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: we don't have their segment, keep processing this one.");
						return false;
					}
				}
			}
			return false;
		}
	}


	public Interest handleContent(ContentObject result, Interest interest) {
		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: in handleContent for {0} at {1}", result.name(), System.currentTimeMillis());

		long starttime = System.currentTimeMillis();
		IncomingSegment is;

		synchronized(processingSegmentLock) {
			Log.info(Log.FAC_PIPELINE, "PIPELINE: before adjusting avgResponseTime after reception. avgResponseTime = {0} elapsed time {1}", avgResponseTime, (starttime - interest.userTime));
			if (avgResponseTime == -1) {
				avgResponseTime = starttime - interest.userTime;
			} else {
				//do not include hole filling responses, they will be extra fast
				//if (interest.exclude()==null)

				//TODO:  find true cause of this bug, temporary fix to get seek/skip fix merged
				long newResponseTime = starttime - interest.userTime;
				if (newResponseTime < 100 * avgResponseTime)
					avgResponseTime = 0.9 * avgResponseTime + 0.1 * newResponseTime;
				else {
					//do not include this response time for now.  Will be fixed in bug 100478
					Log.info(Log.FAC_PIPELINE, "PIPELINE: did not use response time for calculation...  would have been incorrect.  will be fixed in a new branch for 100478 {0}", newResponseTime);
				}
			}
			Log.info(Log.FAC_PIPELINE, "PIPELINE: after adjusting avgResponseTime after reception. avgResponseTime = {0}", avgResponseTime);

			interest.userTime = -1;

			if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
				Log.info(Log.FAC_PIPELINE, "PIPELINE: in handleContent after reading {0} avgResponseTime {1}", result.name(), avgResponseTime);
			is = new IncomingSegment(result, interest);

			processingSegment = SegmentationProfile.getSegmentNumber(is.content.name());
		}

		synchronized(inOrderSegments){

			//was this a content object we were looking for?
			//synchronized(inOrderSegments) {

			if (SystemConfiguration.PIPELINE_STATS)
				System.out.println("plot "+(System.currentTimeMillis() - _pipelineStartTime)+" inOrder: "+inOrderSegments.size() +" outOfOrder: "+outOfOrderSegments.size() + " interests: "+_sentInterests.size() +" holes: "+_holes + " received: "+_totalReceived+" ["+_baseName+"].2" + " avgResponseTime "+avgResponseTime);

			if (_sentInterests.remove(is.interest)) {
				//we had this interest outstanding...
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we were expecting this data! we had outstanding interests: {0}", is.interest);
			} else {
				//we must have canceled the interest...  drop content object
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we must have canceled the interest, dropping ContentObject(s).  old interest: {0}", is.interest);

				//does this match one of our other interests?
				Interest checkInterest;
				is.interest = null;
				for (int i = 0; i < _sentInterests.size(); i++) {
					checkInterest = _sentInterests.get(i);
					if (checkInterest.matches(is.content)) {
						//we found a match!
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: the incoming packet's interest is gone, but it matches another interest, using that");
						is.interest = checkInterest;
						break;
					}
				}
				if (is.interest == null) {
					is = null;
					synchronized(processingSegmentLock) {
						processingSegment = -1;
					}
				}
			}

			if (is != null) {
				// verify the content object
				if (_handle.defaultVerifier().verify(is.content)) {
					// this content verified
					receivePipelineContent(is.content);
				} else {
					// content didn't verify, don't hand it up...
					// TODO content that fails verification needs to be handled better.  need to express a new interest
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.WARNING))
						Log.warning(Log.FAC_PIPELINE, "Dropping content object due to failed verification: {0} Need to add interest re-expression with exclude", is.content.name());
					_sentInterests.remove(is.interest);
				}
			}

			advancePipeline();
		}//try holding lock more consistently to control how notify is done
		attemptHoleFilling();

		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: {0} done with handleContent after reading {1}", (System.currentTimeMillis() - starttime),  result.name());

		return null;
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

	/**
	 * Returns the digest of the first segment of this stream.
	 * Together with firstSegmentNumber() and getBaseName() this method may be used to
	 * identify the stream content unambiguously.
	 *
	 * @return The digest of the first segment of this stream
	 * @throws NoMatchingContentException if no content available
	 * @throws IOException on communication error
	 */
	public byte[] getFirstDigest() throws NoMatchingContentFoundException, IOException {
		if (null == _firstSegment) {
			ContentObject firstSegment = getFirstSegment();
			setFirstSegment(firstSegment); // sets _firstSegment, does link dereferencing
		}
		return _firstSegment.digest();
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
			if (Log.isLoggable(Log.FAC_IO, Level.INFO)) {
				Log.info(Log.FAC_IO, "CCNAbstractInputStream: dereferencing link {0} to {1}, resulting data {2}", theLink.getVersionedName(),
						theLink.link(), ((null == newSegment) ? "null" : newSegment.name()));
				Log.info(Log.FAC_SIGNING, "CCNAbstractInputStream: dereferencing link {0} to {1}, resulting data {2}", theLink.getVersionedName(), theLink.link(), ((null == newSegment) ? "null" : newSegment.name()));
			}

			if (newSegment == null) {
				// TODO -- catch error states. Do we throw exception or return null?
				// Set error states -- when do we find link cycle and set the error on the link?
				// Clear error state when update is successful.
				// Two cases -- link loop or data not found.
				if (_dereferencedLink.hasError()) {
					if (_dereferencedLink.getError() instanceof LinkCycleException) {
						// Leave the link set on the input stream, so that caller can explore errors.
						if (Log.isLoggable(Log.FAC_IO, Level.WARNING)) {
							Log.warning(Log.FAC_IO, "Hit link cycle on link {0} pointing to {1}, cannot dereference. See this.dereferencedLink() for more information!",
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

		_firstSegment = newSegment;

		if (newSegment.isType(ContentType.GONE)) {
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "setFirstSegment: got gone segment: {0}", newSegment.name());
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
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "FINDME: Setting current segment to null! Did a segment fail to verify?");
			return;
		}

		_currentSegment = newSegment;
		// Should we only set these on the first retrieval?
		// getSegment will ensure we get a requested publisher (if we have one) for the
		// first segment; once we have a publisher, it will ensure that future segments match it.
		_publisher = newSegment.signedInfo().getPublisherKeyID();

		if (deletionInformation() != newSegment) { // want pointer ==, not equals() here
			// if we're decrypting, then set it up now
			if (_keys != null) {
				// We only do automated lookup of keys on first segment. Otherwise
				// we assume we must have the keys or don't try to decrypt.
				try {
					// Reuse of current segment OK. Don't expect to have two separate readers
					// independently use this stream without state confusion anyway.

					// Assume getBaseName() returns name without segment information.
					// Log verification only on highest log level (won't execute on lower logging level).
					if (Log.isLoggable(Log.FAC_IO, Level.FINEST)) {
						if (!SegmentationProfile.segmentRoot(_currentSegment.name()).equals(getBaseName())) {
							Log.finest(Log.FAC_IO, "ASSERT: getBaseName()={0} does not match segmentless part of _currentSegment.name()={1}",
									getBaseName(),
									SegmentationProfile.segmentRoot(_currentSegment.name()));
						}
					}
					_cipher = _keys.getSegmentDecryptionCipher(getBaseName(), _publisher,
							SegmentationProfile.getSegmentNumber(_currentSegment.name()));
				} catch (InvalidKeyException e) {
					Log.warning(Log.FAC_IO, "InvalidKeyException: " + e.getMessage());
					throw new IOException("InvalidKeyException: " + e.getMessage());
				} catch (InvalidAlgorithmParameterException e) {
					Log.warning(Log.FAC_IO, "InvalidAlgorithmParameterException: " + e.getMessage());
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
					Log.warning(Log.FAC_IO, "IllegalBlockSizeException: " + e.getMessage());
					throw new IOException("IllegalBlockSizeException: " + e.getMessage());
				} catch (BadPaddingException e) {
					Log.warning(Log.FAC_IO, "BadPaddingException: " + e.getMessage());
					throw new IOException("BadPaddingException: " + e.getMessage());
				}
				if ((null == tailData) || (0 == tailData.length)) {
					if (null == bodyData)
						_segmentReadStream = new ByteArrayInputStream(new byte[0]);
					else 
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
					Log.warning(Log.FAC_IO, "Asked to read encrypted content, but not given a key to decrypt it. Decryption happening at higher level?");
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
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "Cannot rewind null segment.");
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
		long ttgl = System.currentTimeMillis();

		synchronized(readerReadyObj){
			readerReadyVal = number;
		}

		synchronized (inOrderSegments) {
			if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
				Log.info(Log.FAC_PIPELINE, "PIPELINE: time to get lock in getSegment {0} for segment {1}",(System.currentTimeMillis() - ttgl), number);
			// check if the base name was updated (in case we didn't have the version) for pipelining

			if (_baseName.equals(_basePipelineName)) {
				// we already have the base name...
				if (SystemConfiguration.PIPELINE_STATS)
					System.out.println("plot " + (System.currentTimeMillis() - _pipelineStartTime) + " inOrder: " + inOrderSegments.size() + " outOfOrder: " + outOfOrderSegments.size() + " interests: " + _sentInterests.size() + " holes: " + _holes + " received: " + _totalReceived + " [" + _baseName + "].3" +" avgResponseTime "+avgResponseTime);
			} else {
				// we don't have the base name... set for pipelining.
				Log.info(Log.FAC_PIPELINE, "PIPELINE: in getSegment and we detected a name change.  _baseName: {0} _basePipelineName: {1}", _baseName, _basePipelineName);
				setPipelineName(_baseName);
				startPipeline();
			}

			if (_currentSegment != null) {
				// what segment do we have right now? maybe we already have it
				Log.info(Log.FAC_PIPELINE, "PIPELINE: getSegment checking if currentSegment ({0}) is the one we want {1}", currentSegmentNumber(), number);
				if (currentSegmentNumber() == number) {
					// we already have this segment... just use it
					Log.info(Log.FAC_PIPELINE, "PIPELINE: currentSegment was the one we wanted, returning it!");
					return _currentSegment;
				}
			} else {
				Log.info(Log.FAC_PIPELINE, "PIPELINE: getSegment currentSegment was null");
			}

			ContentObject co = getPipelineSegment(number);
			if (co != null) {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we had segment {0} already!!", number);
				advancePipeline();
				synchronized(readerReadyObj) {
					//readerReady.notifyAll();
					readerReadyVal = -1;
					inOrderSegments.notifyAll();
				}
				return co;
			} else {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we don't have segment {0} pipelined... blocking", number);

				//we don't have the segment...  might just be that it isn't here yet, but this might be an out of order request
				if (number < _lastInOrderSegment)
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we do not have segment {0} and the last in order segment was {1}, we must have had a skip/seek", number, _lastInOrderSegment);
			}

			// the segment was not available... we need to wait until the
			// pipeline gets it in
			synchronized(inOrderSegments) {
				long start = 0;
				long sleep = 0;
				long sleepCheck = 0;
				Log.info(Log.FAC_PIPELINE, "PIPELINE: _timeout = {0}", _timeout);
				waitingSegment = number;
				while (sleep < _timeout || _timeout == SystemConfiguration.NO_TIMEOUT) {
					try{
						start = System.currentTimeMillis();

						if (_timeout == SystemConfiguration.NO_TIMEOUT)
							sleepCheck = SystemConfiguration.EXTRA_LONG_TIMEOUT;
						else
							sleepCheck = _timeout - sleep;
						if(avgResponseTime > 0 && avgResponseTime < SystemConfiguration.SHORT_TIMEOUT) {
							if(avgResponseTime > sleepCheck)
								inOrderSegments.wait(sleepCheck);
							else
								inOrderSegments.wait((long)avgResponseTime);
						}
						else {
							if(SystemConfiguration.SHORT_TIMEOUT > sleepCheck)
								inOrderSegments.wait(sleepCheck);
							else
								inOrderSegments.wait(SystemConfiguration.SHORT_TIMEOUT);
						}
					} catch(InterruptedException e1) {
						if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
							Log.info(Log.FAC_PIPELINE, "PIPELINE: awake: interrupted! {0}", sleep);
						//break;
					}
					sleep += System.currentTimeMillis() - start;
					if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
						Log.info(Log.FAC_PIPELINE, "PIPELINE: slept for {0} ms total", sleep);
					if(haveSegmentBuffered(number))
						break;
					else {
						attemptHoleFilling(number);
					}
				}

				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: awake: done sleeping {0}", sleep);

				waitingSegment = -1;
				co = getPipelineSegment(number);
				//}

				synchronized(readerReadyObj) {
					//readerReady.notifyAll();
					readerReadyVal = -1;
					inOrderSegments.notifyAll();
				}
			}

			if (co != null) {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we had segment {0} already!!", number);
				return co;
			} else {
				if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
					Log.info(Log.FAC_PIPELINE, "PIPELINE: we don't have segment {0} pipelined... what happened?", number);
			}

			if(Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
				Log.info(Log.FAC_PIPELINE, "PIPELINE: Cannot get segment " + number + " of file {0} expected segment: {1}.", _baseName, SegmentationProfile.segmentName(_baseName, number));

			throw new IOException("Cannot get segment " + number + " of file "+ _baseName + " expected segment: "+ SegmentationProfile.segmentName(_baseName, number));
		}
	}

	/**
	 * Checks whether we might have a next segment.
	 * @return Returns false if this content is marked as GONE (see ContentType), or if we have
	 * 		retrieved the segment marked as the last one, or, in a very rare case, if we're
	 * 		reading content that does not have segment markers.
	 */
	protected boolean hasNextSegment() throws IOException {

		// We're looking at content marked GONE
		if (isGone()) {
			if (Log.isLoggable(Log.FAC_IO, Level.FINER))
				Log.finer(Log.FAC_IO, "getNextSegment: We have a gone segment, no next segment. Gone segment: {0}", _firstSegment.name());
			return false;
		}

		if (null == _currentSegment) {
			if (Log.isLoggable(Log.FAC_IO, Level.SEVERE))
				Log.severe(Log.FAC_IO, "hasNextSegment() called when we have no current segment!");
			throw new IOException("hasNextSegment() called when we have no current segment!");
		}

		// Check to see if finalBlockID is the current segment. If so, there should
		// be no next segment. (If the writer makes a mistake and guesses the wrong
		// value for finalBlockID, they won't put that wrong value in the segment they're
		// guessing itself -- unless they want to try to extend a "closed" stream.
		// Normally by the time they write that segment, they either know they're done or not.
		if (null != _currentSegment.signedInfo().getFinalBlockID()) {
			if (Arrays.equals(_currentSegment.signedInfo().getFinalBlockID(), _currentSegment.name().lastComponent())) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINER)) {
					Log.finer(Log.FAC_IO, "getNextSegment: there is no next segment. We have segment: " +
							DataUtils.printHexBytes(_currentSegment.name().lastComponent()) + " which is marked as the final segment.");
				}
				return false;
			}
		}

		if (!SegmentationProfile.isSegment(_currentSegment.name())) {
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "Unsegmented content: {0}. No next segment.", _currentSegment.name());
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
			if (Log.isLoggable(Log.FAC_IO, Level.FINE))
				Log.fine(Log.FAC_IO, "getNextSegment: no current segment, getting first segment.");
			ContentObject firstSegment = getFirstSegment();
			setFirstSegment(firstSegment);
			return firstSegment;
		}
		if (Log.isLoggable(Log.FAC_IO, Level.FINE))
			Log.fine(Log.FAC_IO, "getNextSegment: getting segment after {0}", _currentSegment.name());
		// TODO: This should call setCurrentSegment, no?
		return getSegment(nextSegmentNumber());
	}

	/**
	 * Retrieves the first segment of the stream, based on specified startingSegmentNumber
	 * (see #CCNAbstractInputStream(ContentName, Long, PublisherPublicKeyDigest, ContentKeys, CCNHandle)).
	 * @return the first segment, if found.
	 * @throws IOException If can't get a valid starting segment number
	 */
	public ContentObject getFirstSegment() throws IOException {
		if (null != _firstSegment) {
			return _firstSegment;
		} else if (null != _startingSegmentNumber) {
			int oldTimeout = _timeout;
			if (hasFlag(FlagTypes.BLOCKING))
				setTimeout(SystemConfiguration.NO_TIMEOUT);
			ContentObject firstSegment = getSegment(_startingSegmentNumber);
			if (hasFlag(FlagTypes.BLOCK_AFTER_FIRST_SEGMENT))
				setTimeout(SystemConfiguration.NO_TIMEOUT);
			else
				setTimeout(oldTimeout);
			if (Log.isLoggable(Log.FAC_IO, Level.FINE)) {
				Log.fine(Log.FAC_IO, "getFirstSegment: segment number: " + _startingSegmentNumber + " got segment? " +
						((null == firstSegment) ? "no " : firstSegment.name()));
			}
			// Do not call setFirstSegment() here because that should only be done when
			// we are initializing since it does one-time processing including changing the
			// current segment.  Callers to this method may be simply needing the first segment
			// without changing current.
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
			if (Log.isLoggable(Log.FAC_IO, Level.FINER))
				Log.finer(Log.FAC_IO, "is {0} a first segment of {1}", segment.name(), desiredName);
			// In theory, the segment should be at most a versioning component different from desiredName.
			// In the case of complex segmented objects (e.g. a KeyDirectory), where there is a version,
			// then some name components, then a segment, desiredName should contain all of those other
			// name components -- you can't use the usual versioning mechanisms to pull first segment anyway.
			if (!desiredName.equals(SegmentationProfile.segmentRoot(segment.name()))) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINE))
					Log.fine(Log.FAC_IO, "Desired name :{0} is not a prefix of segment: {1}",desiredName, segment.name());
				return false;
			}
			if (null != _startingSegmentNumber) {
				return (_startingSegmentNumber.longValue() == SegmentationProfile.getSegmentNumber(segment.name()));
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
				if (Log.isLoggable(Log.FAC_IO, Level.WARNING)) {
					Log.warning(Log.FAC_IO, "Merging two link stacks -- {0} already has a dereferenced link from {1}. Behavior unpredictable.",
							dereferencedLink.getVersionedName(), dereferencedLink.getDereferencedLink().getVersionedName());
				}
			}
			dereferencedLink.pushDereferencedLink(_dereferencedLink);
		}
		setDereferencedLink(dereferencedLink);
	}

	/**
	 * Returns the first segment number for this stream.
	 * @return The index of the first segment of stream data.
	 */
	public long firstSegmentNumber() {
		return _startingSegmentNumber.longValue();
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
		if (null == _firstSegment) {
			ContentObject firstSegment = getFirstSegment();
			setFirstSegment(firstSegment); // sets _firstSegment, does link dereferencing,
			// throws NoMatchingContentFoundException if firstSegment is null.
			// this way all retry behavior is localized in the various versions of getFirstSegment.
			// Previously what would happen is getFirstSegment would be called by isGone, return null,
			// and we'd have a second chance to catch it on the call to update if things were slow. But
			// that means we would get a more general update on a gone object.
		}
		if (_firstSegment.isType(ContentType.GONE)) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Return the single segment of a stream marked as GONE.  This method
	 * should be called only after checking isGone() == true otherwise it
	 * may return the wrong result.
	 * @return the GONE segment or null if state unknown or stream is not marked GONE
	 */
	public ContentObject deletionInformation() {
		if (null != _firstSegment && _firstSegment.isType(ContentType.GONE))
			return _firstSegment;
		else
			return null;
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
	 * @throw IOException if unable to obtain content (NoMatchingContentFoundException)
	 */
	public KeyLocator publisherKeyLocator() throws IOException {
		if (null == _firstSegment) {
			ContentObject firstSegment = getFirstSegment();
			setFirstSegment(firstSegment);
		}
		return _firstSegment.signedInfo().getKeyLocator();
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
		//Log.finest(Log.FAC_IO, "Checking eof: there yet? " + _atEOF);
		return _atEOF;
	}

	@Override
	public void close() throws IOException {
		Log.info(Log.FAC_IO, "CCNAbstractInputStream: close {0}:  shutting down pipelining", _baseName);

		//now that we have pipelining, we need to cancel our interests and clean up

		//cancel our outstanding interests
		cancelInterests();
		resetPipelineState();
	}

	@Override
	public synchronized void mark(int readlimit) {
		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: in mark({0}) currentSegment {1}", readlimit, currentSegmentNumber());

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
		if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
			Log.finest(Log.FAC_IO, "mark: block: " + segmentNumber() + " offset: " + _markOffset);
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public synchronized void reset() throws IOException {
		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: in reset() currentSegment {0}", currentSegmentNumber());
		if (isGone())
			return;

		// TODO: when first block is read in constructor this check can be removed
		if (_currentSegment == null) {
			setFirstSegment(getFirstSegment());
			setCurrentSegment(getSegment(_markBlock));
		} else if (currentSegmentNumber() == _markBlock) {
			//already have the correct segment
			if (tell() == _markOffset){
				//already have the correct offset
			} else {
				// Reset and skip.
				if (_segmentReadStream.markSupported()) {
					_segmentReadStream.reset();
					if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
						Log.finest(Log.FAC_IO, "reset within block: block: " + segmentNumber() + " offset: " + _markOffset + " eof? " + _atEOF);
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
		if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
			Log.finest(Log.FAC_IO, "reset: block: " + segmentNumber() + " offset: " + _markOffset + " eof? " + _atEOF);
	}

	@Override
	public long skip(long n) throws IOException {

		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: in skip({0}) currentSegment {1}", n, currentSegmentNumber());

		if (isGone())
			return 0;
		if (Log.isLoggable(Log.FAC_IO, Level.FINER))
			Log.finer(Log.FAC_IO, "in skip("+n+")");

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

		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: in seek({0}) currentSegment {1}", position, currentSegmentNumber());

		if (isGone())
			return; // can't seek gone stream

		if (Log.isLoggable(Log.FAC_IO, Level.FINER)) {
			Log.finer(Log.FAC_IO, "Seeking stream to {0}", position);
		}

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
		if (Log.isLoggable(Log.FAC_PIPELINE, Level.INFO))
			Log.info(Log.FAC_PIPELINE, "PIPELINE: in tell() currentSegment {0}", currentSegmentNumber());
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

	static private class IncomingSegment {
		public ContentObject content;
		public Interest interest;

		private IncomingSegment(ContentObject co, Interest i) {
			content = co;
			interest = i;
		}
	}

}
