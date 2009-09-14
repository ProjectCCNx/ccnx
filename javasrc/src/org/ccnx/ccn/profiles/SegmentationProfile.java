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

package org.ccnx.ccn.profiles;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * We speak in terms of segments, not fragments, as this profile
 * also encompasses packet-oriented data with sequenced segments rather
 * than block data divided into fragments.
 * Sequence/segment numbers occupy the final component of the CCN name 
 * (again, not counting the digest component). For consecutive numbering, 
 * the first byte of the sequence component is 0xF8. The remaining bytes 
 * hold the sequence number in big-endian unsigned binary, using the minimum number 
 * of bytes. Thus sequence number 0 is encoded in just one byte, %F8, and 
 * sequence number 1 is %F8%01. Note that this encoding is not quite 
 * dense - %F8%00 is unused, as are other components that start with 
 * these two bytes. 
 * For non-consecutive numbering (e.g, using byte offsets) the value 
 * 0xFB may be used as a marker.
 * 
 * DKS -- add-on to this proposal: use fragment markers on all content,
 * content with only one fragment gets the marker 0xF800, and the last
 * fragment of a given piece of content (when this is known) has
 * a prefix of 0xF800 instead of just 0xF8.
 * @author smetters
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

	public static final byte [] HEADER_NAME = ContentName.componentParseNative(".header"); 

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
	
	public static boolean isSegmentMarker(byte [] potentialSegmentID) {
		return (!isNotSegmentMarker(potentialSegmentID));
	}

	public static boolean isSegment(ContentName name) {
		return (!isUnsegmented(name));
	}

	public static ContentName segmentRoot(ContentName name) {
		if (isUnsegmented(name))
			return name;
		return new ContentName(name.count()-1, name.components());
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
			byte [] iarr = BigInteger.valueOf(segmentNumber).toByteArray();
			segmentNumberNameComponent = new byte[iarr.length + ((0 == iarr[0]) ? 0 : 1)];
			segmentNumberNameComponent[0] = SEGMENT_MARKER;
			int offset = ((0 == iarr[0]) ? 1 : 0);
			System.arraycopy(iarr, offset, segmentNumberNameComponent, 1, iarr.length-offset);
		}
		return segmentNumberNameComponent;
	}
	
	public static long getSegmentNumber(byte [] segmentNumberNameComponent) {
		
		if (isSegmentMarker(segmentNumberNameComponent)) {
			// Will behave properly with everything but first fragment of fragmented content.
			if (segmentNumberNameComponent.length == 1)
				return 0;
			byte [] ftemp = new byte[segmentNumberNameComponent.length-1];
			System.arraycopy(segmentNumberNameComponent, 1, ftemp, 0, segmentNumberNameComponent.length-1);
			segmentNumberNameComponent = ftemp;
		} 
		// If this isn't formatted as one of our segment numbers, suspect it might
		// be a sequence (e.g. a packet stream), and attempt to read the last name
		// component as a number.
		BigInteger value = new BigInteger(1, segmentNumberNameComponent);
		return value.longValue();
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
	 * Check to see if we have (a block of) the header.
	 * @param baseName The name of the object whose header we are looking for (including version, but
	 * 			not including segmentation information).
	 * @param headerName The name of the object we think might be a header block (can include
	 * 			segmentation).
	 * @return
	 */
	public static boolean isHeader(ContentName baseName, ContentName headerName) {
		if (!baseName.isPrefixOf(headerName)) {
			return false;
		}
		if (isSegment(headerName)) {
			headerName = segmentRoot(headerName);
		}
		// Should end with metadata/header
		if (baseName.count() != (headerName.count() - 2))
			return false;
		
		if (!Arrays.equals(headerName.lastComponent(), HEADER_NAME))
			return false;
		
		if (!Arrays.equals(headerName.component(headerName.count()-2), MetadataProfile.METADATA_MARKER))
			return false;
		
		return true;
	}

	/**
	 * Move header from <content>/<version> as its name to
	 * <content>/<version>/_metadata_marker_/HEADER/<version>
	 * where the second version is imposed by the use of versioning
	 * network objects (i.e. this function should return up through HEADER above)
	 * Header name generation may want to move to a MetadataProfile.
	 * 
	 * @param name
	 * @return
	 */
	public static ContentName headerName(ContentName name) {
		// Want to make sure we don't add a header name
		// to a fragment. Go back up to the fragment root.
		// Currently no header name added.
		if (isSegment(name)) {
			name = segmentRoot(name);
		}
		return new ContentName(name, MetadataProfile.METADATA_MARKER, HEADER_NAME);
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
	 * that matches requirements and verifies, or it times out.
	 * TODO Eventually cope if verification fails (exclude, warn and retry).
	 * @param desiredContent
	 * @param segmentNumber If null, gets baseSegment().
	 * @param timeout
	 * @param verifier Cannot be null.
	 * @param library
	 * @return
	 * @throws IOException
	 */
	public static ContentObject getSegment(ContentName desiredContent, Long desiredSegmentNumber, PublisherPublicKeyDigest publisher, long timeout, ContentVerifier verifier, CCNHandle library) throws IOException {
		
	    // Block name requested should be interpreted literally, not taken
	    // relative to baseSegment().
		if (null == desiredSegmentNumber) {
			desiredSegmentNumber = baseSegment();
		}
		
		ContentName segmentName = segmentName(desiredContent, desiredSegmentNumber);
	
		// TODO use better exclude filters to ensure we're only getting segments.
		Log.info("getSegment: getting segment {0}", segmentName);
		ContentObject segment = CCNReader.getLower(library, segmentName, 1, publisher, timeout);
	
		if (null == segment) {
			Log.info("Cannot get segment " + desiredSegmentNumber + " of file {0} expected segment: {1}.", desiredContent,  segmentName);
			throw new IOException("Cannot get segment " + desiredSegmentNumber + " of file " + desiredContent + " expected segment: " + segmentName);
		} else {
			Log.info("getsegment: retrieved segment {0}.", segment.name());
		}
		
		// So for the segment, we assume we have a potential document.
		if (!verifier.verify(segment)) {
			// TODO eventually try to go and look for another option
			Log.info("Retrieved segment {0}, but it didn't verify.", segment.name());
			return null;
		}
		return segment;
	}
}
