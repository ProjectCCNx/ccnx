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
	public static final int BASE_SEGMENT = 0;
	public static final byte SEGMENT_MARKER = (byte)0xF8;
	public static final byte NO_SEGMENT_POSTFIX = 0x00;
	public static final byte [] FIRST_SEGMENT_MARKER = new byte[]{SEGMENT_MARKER};
	public static final byte [] NO_SEGMENT_MARKER = new byte[]{SEGMENT_MARKER, NO_SEGMENT_POSTFIX};

	/**
	 * What does its fragment number mean?
	 */
	public enum SegmentNumberType {SEGMENT_FIXED_INCREMENT, SEGMENT_BYTE_COUNT}
	public static final int DEFAULT_BLOCKSIZE = 4096;
	public static final int DEFAULT_INCREMENT = 1;
	public static final int DEFAULT_SCALE = 1;

	/**
	 * Control whether fragments start at 0 or 1.
	 * @return
	 */
	public static final int baseSegment() { return BASE_SEGMENT; }
	
	public static boolean isUnsegmented(ContentName name) {
		byte [] fm = name.lastComponent();
		return ((null == fm) || (0 == fm.length) || (SEGMENT_MARKER != fm[0]) || 
					((fm.length > 1) && (NO_SEGMENT_POSTFIX == fm[1])));
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
		byte [] fcomp = null;
		if (0 == index) {
			fcomp = FIRST_SEGMENT_MARKER;
		} else {
			byte [] iarr = BigInteger.valueOf(index).toByteArray();
			fcomp = new byte[iarr.length + 1];
			fcomp[0] = SEGMENT_MARKER;
			System.arraycopy(iarr, 0, fcomp, 0, iarr.length);
		}
		return new ContentName(baseName, fcomp);
	}

	/**
	 * Extract the fragment information from this name.
	 */
	public static long getSegmentNumber(ContentName name) {
		if (isSegment(name)) {
			byte [] fcomp = name.lastComponent();
			// Will behave properly with everything but first fragment of fragmented content.
			if (fcomp.length == 1)
				return 0;
			return Long.valueOf(ContentName.componentPrintURI(fcomp, 1, fcomp.length-1));
		}
		return -1; // unexpected, but not invalid
	}
}
