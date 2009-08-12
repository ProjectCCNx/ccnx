package com.parc.ccn.library.profiles;

import java.math.BigInteger;

import com.parc.ccn.data.ContentName;

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
	public static final byte SEGMENT_MARKER = (byte)0xF8;
	public static final byte NO_SEGMENT_POSTFIX = 0x00;
	public static final byte [] FIRST_SEGMENT_MARKER = new byte[]{SEGMENT_MARKER};
	public static final byte [] NO_SEGMENT_MARKER = new byte[]{SEGMENT_MARKER, NO_SEGMENT_POSTFIX};

	public static final String HEADER_NAME = ".header"; // DKS currently not used; see below.

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
		return new ContentName(baseName, getSegmentID(index));
	}
	
	public static byte [] getSegmentID(long segmentNumber) {
		
		byte [] segmentID = null;
		if (baseSegment() == segmentNumber) {
			segmentID = FIRST_SEGMENT_MARKER;
		} else {
			byte [] iarr = BigInteger.valueOf(segmentNumber).toByteArray();
			segmentID = new byte[iarr.length + ((0 == iarr[0]) ? 0 : 1)];
			segmentID[0] = SEGMENT_MARKER;
			int offset = ((0 == iarr[0]) ? 1 : 0);
			System.arraycopy(iarr, offset, segmentID, 1, iarr.length-offset);
		}
		return segmentID;
	}
	
	public static long getSegmentNumber(byte [] segmentID) {
		
		if (isSegmentMarker(segmentID)) {
			// Will behave properly with everything but first fragment of fragmented content.
			if (segmentID.length == 1)
				return 0;
			byte [] ftemp = new byte[segmentID.length-1];
			System.arraycopy(segmentID, 1, ftemp, 0, segmentID.length-1);
			segmentID = ftemp;
		} 
		// If this isn't formatted as one of our segment numbers, suspect it might
		// be a sequence (e.g. a packet stream), and attempt to read the last name
		// component as a number.
		BigInteger value = new BigInteger(1, segmentID);
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
		// TODO update to new header naming
		if (!baseName.isPrefixOf(headerName)) {
			return false;
		}
		if (isSegment(headerName)) {
			headerName = segmentRoot(headerName);
		}
		return (baseName.count() == headerName.count());
	}

	/**
	 * Might want to make headerName not prefix of  rest of
	 * name, but instead different subleaf. For example,
	 * the header name of v.6 of name <name>
	 * was originally <name>/_v_/6; could be 
	 * <name>/_v_/6/.header or <name>/_v_/6/_m_/.header;
	 * the full uniqueified names would be:
	 * <name>/_v_/6/<sha256> or <name>/_v_/6/.header/<sha256>
	 * or <name>/_v_/6/_m_/.header/<sha256>.
	 * The first version has the problem that the
	 * header name (without the unknown uniqueifier)
	 * is the prefix of the block names; so we must use the
	 * scheduler or other cleverness to get the header ahead of the blocks.
	 * The second version of this makes it impossible to easily
	 * write a reader that gets both single-block content and
	 * fragmented content (and we don't want to turn the former
	 * into always two-block content).
	 * So having tried the second route, we're moving back to the former.
	 * 
	 * DKS TODO move header from <content>/<version> as its name to
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
			// return new ContentName(fragmentRoot(name), HEADER_NAME);
			return segmentRoot(name);
		}
		// return new ContentName(name, HEADER_NAME);
		return name;
	}

	public static boolean isFirstSegment(ContentName name) {
		if (!isSegment(name))
			return false;
		return (getSegmentNumber(name) == BASE_SEGMENT);
	}
}
