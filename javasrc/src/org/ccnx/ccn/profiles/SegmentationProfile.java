/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2010, 2012, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeAny;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * We speak in terms of segments, not fragments, as this profile
 * also encompasses packet-oriented data with sequenced segments rather
 * than block data divided into fragments.
 * Sequence/segment numbers occupy the final component of the CCN name
 * (again, not counting the digest component). For consecutive numbering,
 * the first byte of the sequence component is 0x00. The remaining bytes
 * hold the sequence number in big-endian unsigned binary, using the minimum number
 * of bytes. Thus sequence number 0 is encoded in just one byte, %00, and
 * sequence number 1 is %00%01. Note that this encoding is not quite
 * dense - %00%00 is unused, as are other components that start with
 * these two bytes.
 * For non-consecutive numbering (e.g, using byte offsets) the value
 * 0xFB may be used as a marker.
 *
 *
 */
public class SegmentationProfile implements CCNProfile {

	/**
	 * Is it fragmented, and what is its fragment number?
	 */
	public static final long BASE_SEGMENT = 0;
	public static final byte SEGMENT_MARKER = (byte)0x00;
	public static final byte NO_SEGMENT_POSTFIX = 0x00;
	public static final byte [] FIRST_SEGMENT_MARKER = new byte[]{SEGMENT_MARKER};
	public static final byte [] NO_SEGMENT_MARKER = new byte[]{SEGMENT_MARKER, NO_SEGMENT_POSTFIX};

	/**
	 * What does its fragment number mean?
	 */
	public enum SegmentNumberType {SEGMENT_FIXED_INCREMENT, SEGMENT_BYTE_COUNT}

	/**
	 * Default blocksize. This must be a multiple of the block size of standard
	 * encryption algorithms (generally, 128 bits = 16 bytes; conservatively
	 * 256 bits = 32 bytes, really conservatively, support 192 bits = 24 bytes;
	 * so 32 bytes with unused bytes in the 192-bit case (otherwise we'd have
	 * to use the LCM, 96 bytes, which is really inefficient).
	 */
	public static final int DEFAULT_BLOCKSIZE = 4096;
	public static final int DEFAULT_INCREMENT = 1;
	public static final int DEFAULT_SCALE = 1;

	/**
	 * The library will attempt to get the last segment, without knowing exactly what
	 * segment number this will be.  Additionally, it is possible for a streaming producer to
	 * continuously push a new segment and never have an end.  The MAX_LAST_SEGMENT_LOOP_ATTEMPT
	 * variable avoids blocking forever in this case.  The getLastSegment function will
	 * attempt x times (default is 10), before handing back the current content object, which is a
	 * best effort attempt at the last segment.
	 */
	public static final int MAX_LAST_SEGMENT_LOOP_ATTEMPTS = 10;

	/**
	 * Control whether fragments start at 0 or 1.
	 * @return
	 */
	public static final long baseSegment() { return BASE_SEGMENT; }

	public static boolean isUnsegmented(ContentName name) {
		return isNotSegmentMarker(name.lastComponent());
	}

	public static boolean isNotSegmentMarker(byte [] potentialSegmentID) {
		return ((null == potentialSegmentID) || (0 == potentialSegmentID.length) || (SEGMENT_MARKER != potentialSegmentID[0]) ||
				((potentialSegmentID.length > 1) && (NO_SEGMENT_POSTFIX == potentialSegmentID[1])));
	}

	public final static boolean isSegmentMarker(byte [] potentialSegmentID) {
		return (!isNotSegmentMarker(potentialSegmentID));
	}

	public static boolean isSegment(ContentName name) {
		if (null == name)
			return false;
		return (!isUnsegmented(name));
	}

	public static ContentName segmentRoot(ContentName name) {
		if (isUnsegmented(name))
			return name;
		return name.parent();
	}

	public static ContentName segmentName(ContentName name, long index) {
		// Need a minimum-bytes big-endian representation of i.
		ContentName baseName = name;
		if (isSegment(name)) {
			baseName = segmentRoot(name);
		}
		return new ContentName(baseName, getSegmentNumberNameComponent(index));
	}

	public static byte [] getSegmentNumberNameComponent(long segmentNumber) {

		byte [] segmentNumberNameComponent = null;
		if (baseSegment() == segmentNumber) {
			segmentNumberNameComponent = FIRST_SEGMENT_MARKER;
		} else {
			segmentNumberNameComponent = DataUtils.unsignedLongToByteArray(segmentNumber, SEGMENT_MARKER);
		}
		return segmentNumberNameComponent;
	}

	public static long getSegmentNumber(byte [] segmentNumberNameComponent) {

		if (isSegmentMarker(segmentNumberNameComponent)) {
			// Will behave properly with everything but first fragment of fragmented content.
			if (segmentNumberNameComponent.length == 1)
				return 0;

			return DataUtils.byteArrayToUnsignedLong(segmentNumberNameComponent, 1);
		}
		// If this isn't formatted as one of our segment numbers, suspect it might
		// be a sequence (e.g. a packet stream), and attempt to read the last name
		// component as a number.

		return DataUtils.byteArrayToUnsignedLong(segmentNumberNameComponent);
	}

	/**
	 * Extract the segment information from this name. Try to return any valid
	 * number encoded in the last name component, be it either a raw big-endian
	 * encoding of a number, or more likely, a properly-formatted segment
	 * number with segment marker.
 	 * @throws NumberFormatException if neither number type found in last name component
	 */
	public static long getSegmentNumber(ContentName name) {
		return getSegmentNumber(name.lastComponent());
	}

	/**
	 * Just confirms that last name component is a segment, and that its segment number is baseSegment() (0).
	 * @param name
	 * @return
	 */
	public static boolean isFirstSegment(ContentName name) {
		if (!isSegment(name))
			return false;
		return (getSegmentNumber(name) == BASE_SEGMENT);
	}

	/**
	 * Retrieves a specific segment, following the above naming conventions.
	 * If necessary (not currently), will issue repeated requests until it gets a segment
	 * that matches requirements and verifies, or it times out. If it
	 * can't find anything, should return null.
	 * TODO Eventually cope if verification fails (exclude, warn and retry).
	 * @param desiredContent
	 * @param segmentNumber If null, gets baseSegment().
	 * @param timeout
	 * @param verifier Cannot be null.
	 * @param handle
	 * @return the segment it got, or null if nothing matching could be found in the
	 * 	allotted time
	 * @throws IOException only on error
	 */
	public static ContentObject getSegment(ContentName desiredContent, Long desiredSegmentNumber,
											PublisherPublicKeyDigest publisher, long timeout,
											ContentVerifier verifier, CCNHandle handle) throws IOException {

	    // Block name requested should be interpreted literally, not taken
	    // relative to baseSegment().
		if (null == desiredSegmentNumber) {
			desiredSegmentNumber = baseSegment();
		}

		ContentName segmentName = segmentName(desiredContent, desiredSegmentNumber);
		Interest interest = null;
		boolean keepTrying = true;
		
		long remainingTime = timeout;
		long elapsedTime = 0;
		long startTime = System.currentTimeMillis();
		
		// TODO use better exclude filters to ensure we're only getting segments.
		if (Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info("getSegment: getting segment {0}", segmentName);
		interest = Interest.lower(segmentName, 1, publisher);
		
		while(keepTrying) {

			ContentObject segment = handle.get(interest, remainingTime);

			if (null == segment) {
				if (Log.isLoggable(Log.FAC_IO, Level.INFO))
					Log.info(Log.FAC_IO, "Cannot get segment {0} of file {1} expected segment: {2}.", desiredSegmentNumber, desiredContent,  segmentName);
				return null; // used to throw IOException, which was wrong. Do we want to be more aggressive?
			} else {
				if (Log.isLoggable(Log.FAC_IO, Level.INFO))
					Log.info(Log.FAC_IO, "getsegment: retrieved segment {0}.", segment.name());
			}

			if (null == verifier) {
				verifier = ContentObject.SimpleVerifier.getDefaultVerifier();
			}
			// So for the segment, we assume we have a potential document.
			if (!verifier.verify(segment)) {
				// TODO eventually try to go and look for another option
				if (Log.isLoggable(Log.FAC_IO, Level.INFO))
					Log.info(Log.FAC_IO, "Retrieved segment {0}, but it didn't verify.", segment.name());

				//failed verification...  do we have time to try again?
				elapsedTime = System.currentTimeMillis() - startTime;
				if (elapsedTime < timeout || timeout == SystemConfiguration.NO_TIMEOUT) {
					//we need to try again...  add this digest to the exclude
					if (interest.exclude() == null) {
						//add exclude
						interest.exclude(new Exclude());
					}
					interest.exclude().add(new byte[][] {segment.digest()});
					remainingTime = timeout - elapsedTime;
				} else {
					if (Log.isLoggable(Log.FAC_IO, Level.INFO))
						Log.info(Log.FAC_IO, "Segment did not verify, out of time, will return null");
					return null;
				}
				if (Log.isLoggable(Log.FAC_IO, Level.INFO))
					Log.info(Log.FAC_IO, "Segment did not verify, excluding the digest ({0}) and trying again, remainingTime = {1}", segment.fullName(), remainingTime);
			} else {
				return segment;
			}
		}
		return null;
	}


	/**
	 * Creates an Interest for a specified segment number.  If the supplied name already
	 * ends with a segment number, the interest will have the supplied segment in the name
	 * instead.
	 *
	 * @param name ContentName for the desired ContentObject
	 * @param segmentNumber segment number to append to the name, if null, uses the baseSegment number
	 * @param publisher can be null
	 *
	 * @return interest
	 **/

	public static Interest segmentInterest(ContentName name, Long segmentNumber, PublisherPublicKeyDigest publisher){
		ContentName interestName = null;
		//make sure the desired segment number is specified
		if (null == segmentNumber) {
			segmentNumber = baseSegment();
		}

		//check if the name already has a segment in the last spot
		if (isSegment(name)) {
			//this already has a segment, trim it off
			interestName = segmentRoot(name);
		} else {
			interestName = name;
		}

		interestName = segmentName(interestName, segmentNumber);
		if (Log.isLoggable(Log.FAC_IO, Level.FINE))
			Log.fine(Log.FAC_IO, "segmentInterest: creating interest for {0} from ContentName {1} and segmentNumber {2}", interestName, name, segmentNumber);
		Interest interest = Interest.lower(interestName, 1, publisher);
		return interest;
	}

	/**
	 * Creates an Interest that allows for only a segment number and an ephemeral digest below the
	 * given prefix.
	 * @param prefix
	 * @param publisher
	 * @return
	 */
	public static Interest anySegmentInterest(ContentName prefix, PublisherPublicKeyDigest publisher) {
		Interest theInterest = Interest.lower(prefix, 2, publisher);
		// theInterest.exclude(acceptSegments())
		return theInterest;
	}

	/**
	 * Creates an Interest for the first segment.
	 *
	 * @param name ContentName for the desired ContentObject
	 * @param publisher can be null
	 *
	 * @return interest
	 **/

	public static Interest firstSegmentInterest(ContentName name, PublisherPublicKeyDigest publisher){
		return segmentInterest(name, baseSegment(), publisher);
	}

	public static Interest nextSegmentInterest(ContentName name, PublisherPublicKeyDigest publisher) {
		if (Log.isLoggable(Log.FAC_IO, Level.FINER))
			Log.finer(Log.FAC_IO, "nextSegmentInterest: creating interest for next segment from ContentName {0}", name);
		//TODO need to make sure we only get segments back and not other things like versions
		Interest interest = Interest.next(name, null, null, 2, 2, publisher);
		return interest;
	}


	/**
	 * Creates an Interest to find the right-most child from the given segment number
	 * or the base segment if one is not supplied.  This attempts to find the last segment for
	 * the given content name.  Due to caching, this does not guarantee the interest will find the
	 * last segment, higher layer code must verify that the ContentObject returned for this interest
	 * is the last one.
	 *
	 * TODO: depends on acceptSegments being fully implemented.  right now mainly depends on the number of component restriction.
	 *
	 * @param name ContentName for the prefix of the Interest
	 * @param segmentNumber create an interest for the last segment number after this number
	 * @param publisher can be null
	 * @return interest
	 */

	public static Interest lastSegmentInterest(ContentName name, Long segmentNumber, PublisherPublicKeyDigest publisher){
		Interest interest = null;
		ContentName interestName = null;

		//see if a segment number was supplied
		if (segmentNumber == null) {
			segmentNumber = baseSegment();
		}

		//check if the name has a segment number
		if (isSegment(name)) {
			//this already has a segment
			//is this segment before or after the segmentNumber
			if (segmentNumber < getSegmentNumber(name)) {
				//the segment number in the name is higher...  use this
				interestName = name;
			} else {
				//the segment number provided is bigger...  use that
				interestName = segmentName(name, segmentNumber);
			}
		} else {
			interestName = segmentName(name, segmentNumber);
		}

		if (Log.isLoggable(Log.FAC_IO, Level.FINER))
			Log.finer(Log.FAC_IO, "lastSegmentInterest: creating interest for {0} from ContentName {1} and segmentNumber {2}", interestName, name, segmentNumber);
		//TODO need to make sure we only get segments back and not other things like versions
		interest = Interest.last(interestName, acceptSegments(getSegmentNumberNameComponent(segmentNumber)), null, 2, 2, publisher);
		return interest;
	}


	/**
	 * Creates an Interest to find the right-most child from the given segment number
	 * or the base segment if one is not supplied.  This Interest will attempt to find the last segment for
	 * the given content name.  Due to caching, this does not guarantee the interest will find the
	 * last segment, higher layer code must verify that the ContentObject returned for this interest
	 * is the last one.
	 *
	 * @param name ContentName for the prefix of the Interest
	 * @param publisher can be null
	 * @return interest
	 */

	public static Interest lastSegmentInterest(ContentName name, PublisherPublicKeyDigest publisher){
		return lastSegmentInterest(name, baseSegment(), publisher);
	}

	/**
	 * This method returns the last segment for the name provided.  If there are no segments for the supplied name,
	 * the method will return null.  It is possible for producers to continuously make new segments.  In this case,
	 * the function returns last content object it finds (and verifies) after some number of attempts (the default
	 * value is 10 for now).  The function can be called with a starting segment, the method will attempt to
	 * find the last segment after the provided segment number.  This method assumes either the last component of
	 * the supplied name is a segment or the next component of the name will be a segment.  It does not attempt to
	 * resolve the remainder of the prefix (for example, it will not attempt to distinguish which version to use).
	 *
	 * If the method is called with a segment in the name and as an additional parameter, the method will use the higher
	 * number to locate the last segment.  Again, if no segment is found, the method will return null.
	 *
	 * Calling functions should explicitly check if the returned segment is the best-effort at the last segment, or
	 * the marked last segment (if it matters to the caller).
	 *
	 * @param name
	 * @param publisher
	 * @param timeout
	 * @param verifier
	 * @param handle
	 * @return
	 * @throws IOException
	 */

	public static ContentObject getLastSegment(ContentName name, PublisherPublicKeyDigest publisher, long timeout, ContentVerifier verifier, CCNHandle handle) throws IOException{
		ContentName segmentName = null;
		ContentObject co = null;
		Interest getLastInterest = null;

		int attempts = MAX_LAST_SEGMENT_LOOP_ATTEMPTS;

		//want to start with a name with a segment number in it
		if(isSegment(name)){
			//the name already has a segment...  could this be the segment we want?
			segmentName = name;
			getLastInterest = lastSegmentInterest(segmentName, publisher);
		} else {
			//this doesn't have a segment already
			//the last segment could be the first one...
			segmentName = segmentName(name, baseSegment());
			getLastInterest = firstSegmentInterest(segmentName, publisher);
		}


		while (true) {
			attempts--;
			co = handle.get(getLastInterest, timeout);
			if (co == null) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINER))
					Log.finer(Log.FAC_IO, "Null returned from getLastSegment for name: {0}",name);
				return null;
			} else {
				if (Log.isLoggable(Log.FAC_IO, Level.FINER))
					Log.finer(Log.FAC_IO, "returned contentObject: {0}",co.fullName());
			}

			//now we should have a content object after the segment in the name we started with, but is it the last one?
			if (isSegment(co.name())) {
				//double check that we have a segmented name
				//we have a later segment, but is it the last one?
				//check the final segment marker or if we are on our last attempt
				if (isLastSegment(co) || attempts == 0) {
					//this is the last segment (or last attempt)...  check if it verifies.
					if (verifier.verify(co)) {
						return co;
					} else {
						//this did not verify...  need to determine how to handle this
						if (Log.isLoggable(Log.FAC_IO, Level.WARNING))
							Log.warning(Log.FAC_IO, "VERIFICATION FAILURE: " + co.name() + ", need to find better way to decide what to do next.");
					}
				} else {
					//this was not the last segment..  use the co.name() to try again.
					
					segmentName = co.name().cut(getLastInterest.name().count());
					getLastInterest = lastSegmentInterest(segmentName, getSegmentNumber(co.name()), publisher);

					if (Log.isLoggable(Log.FAC_IO, Level.FINE))
						Log.fine(Log.FAC_IO, "an object was returned...  but not the last segment, next Interest: {0}",getLastInterest);
				}
			} else {
				if (Log.isLoggable(Log.FAC_IO, Level.WARNING))
					Log.warning(Log.FAC_IO, "SegmentationProfile.getLastSegment: had a content object returned that did not have a segment in the last component Interest = {0} ContentObject = {1}", segmentName, co.name());
				return null;
			}
		}
	}

	public static boolean isLastSegment(ContentObject co) {
		if (isSegment(co.name())) {
			//we have a segment to check...
			if(!co.signedInfo().emptyFinalBlockID()) {
				//the final block id is set
				if(getSegmentNumber(co.name()) == getSegmentNumber(co.signedInfo().getFinalBlockID()))
					return true;
			}
		}
		return false;
	}


	/**
	 * Builds an Exclude filter that excludes components that are not segments in the next component.
	 * @param startingSegmentComponent The latest segment component we know about. Can be null or
	 * 			the SegmentationProfile.baseSegment() component to indicate that we want to start
	 * 			from 0 (we don't have a known segment to start from). This exclude filter will
	 * 			find segments *after* the segment represented in startingSegmentComponent.
	 * @return An exclude filter.
	 *
	 * TODO needs to be fully implemented
	 */
	public static Exclude acceptSegments(byte [] startingSegmentComponent) {
		byte [] start = null;
		// initially exclude name components just before the first segment, whether that is the
		// 0th segment or the segment passed in
		if ((null == startingSegmentComponent) || (SegmentationProfile.getSegmentNumber(startingSegmentComponent) == baseSegment())) {
			start = SegmentationProfile.FIRST_SEGMENT_MARKER;
		} else {
			start = startingSegmentComponent;
		}

		ArrayList<Exclude.Element> ees = new ArrayList<Exclude.Element>();
		ees.add(new ExcludeAny());
		ees.add(new ExcludeComponent(start));
		//ees.add(new ExcludeComponent(new byte [] { SEGMENT_MARKER+1} ));
		//ees.add(new ExcludeAny());


		return new Exclude(ees);
	}

	/**
	 * Function to get the next segment after segment in the supplied name (or the first segment if no segment is given).
	 *
	 */
	public static ContentObject getNextSegment(ContentName name, PublisherPublicKeyDigest publisher, long timeout, ContentVerifier verifier, CCNHandle handle) throws IOException {
		ContentName segmentName = null;
		ContentObject co = null;
		Interest getNextInterest = null;
		long segmentNumber = -1;

		//want to start with a name with a segment number in it
		if(isSegment(name)){
			//the name already has a segment...  could this be the segment we want?
			segmentName = name;
			segmentNumber = getSegmentNumber(name);
			getNextInterest = segmentInterest(segmentName, segmentNumber+1, publisher);
			//getNextInterest = nextSegmentInterest(segmentName, publisher);
		} else {
			//this doesn't have a segment already
			//the last segment could be the first one...
			segmentName = segmentName(name, baseSegment());
			getNextInterest = firstSegmentInterest(segmentName, publisher);
		}

		co = handle.get(getNextInterest, timeout);
		if (co == null) {
			if (Log.isLoggable(Log.FAC_IO, Level.FINER))
				Log.finer(Log.FAC_IO, "Null returned from getNextSegment for name: {0}",name);
			return null;
		} else {
			if (Log.isLoggable(Log.FAC_IO, Level.FINER))
				Log.finer(Log.FAC_IO, "returned contentObject: {0}",co.fullName());
		}

		//now we should have a content object after the segment in the name we started with, but is it the last one?
		if (isSegment(co.name())) {
			//double check that we have a segmented name
			//we have a later segment, but is it the next one?
			//check the final segment marker or if we are on our last attempt
			if (segmentNumber+1 == getSegmentNumber(co.name())) {
				//this is the next segment (or last attempt)...  check if it verifies.
				if (verifier.verify(co)) {
					return co;
				} else {
					//this did not verify...  need to determine how to handle this
					Log.warning(Log.FAC_IO, "VERIFICATION FAILURE: " + co.name() + ", need to find better way to decide what to do next.");
				}
			}
		}

		//we didn't get back the segment number we were expecting...  needs to be more robust
		return null;
	}

}
