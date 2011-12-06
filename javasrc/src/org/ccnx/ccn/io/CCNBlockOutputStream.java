/*
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

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNSegmenter;
import org.ccnx.ccn.impl.security.crypto.CCNBlockSigner;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.SegmentationProfile.SegmentNumberType;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;


/**
 * This class acts as a packet-oriented stream of data. It might be
 * better implemented as a subclass of DatagramSocket. Given a base name
 * and signing information, it writes content blocks under that base name,
 * where each block is named according to the base name concatenated with a
 * sequence number identifying the specific block. 
 * 
 * Each call to write writes one or more individual ContentObjects. The
 * maximum size is given by parameters of the segmenter used; if buffers
 * are larger than that size they are output as multiple fragments.
 * 
 * It does offer flexible content name increment options. The creator
 * can specify an initial block id (default is 0), and an increment (default 1)
 * for fixed-width blocks, or blocks can be identified by byte offset
 * in the running stream, or by another integer metric (e.g. time offset),
 * by supplying a multiplier to convert the byte offset into a metric value.
 * Finally, writers can specify the block identifier with a write.
 * Currently, however, the corresponding reader org.ccnx.ccn.io.CCNBlockInputStream expects
 * sequential segment numbering (and constraints based on the low-level CCN
 * Interest specification may make this difficult to overcome).
 */
public class CCNBlockOutputStream extends CCNAbstractOutputStream {

	public CCNBlockOutputStream(ContentName baseName, SignedInfo.ContentType type) throws IOException {
		this(baseName, type, null, null);
	}
		
	public CCNBlockOutputStream(ContentName baseName, SignedInfo.ContentType type,
								PublisherPublicKeyDigest publisher,
								CCNHandle handle)
								throws IOException {
		this(baseName, type, null, publisher, null, new CCNFlowControl((null == handle) ? CCNHandle.getHandle() : handle));
	}

	public CCNBlockOutputStream(ContentName baseName, SignedInfo.ContentType type,
			KeyLocator locator, PublisherPublicKeyDigest publisher,
			ContentKeys keys, CCNFlowControl flowControl)
			throws IOException {
		super((SegmentationProfile.isSegment(baseName) ? SegmentationProfile.segmentRoot(baseName) : baseName),
			  locator, publisher, type, keys, new CCNSegmenter(flowControl, new CCNBlockSigner()));
		startWrite(); // set up flow controller to write
	}

	public void useByteCountSequenceNumbers() {
		getSegmenter().setSequenceType(SegmentNumberType.SEGMENT_BYTE_COUNT);
		getSegmenter().setByteScale(1);
	}

	public void useFixedIncrementSequenceNumbers(int increment) {
		getSegmenter().setSequenceType(SegmentNumberType.SEGMENT_FIXED_INCREMENT);
		getSegmenter().setBlockIncrement(increment);
	}

	public void useScaledByteCountSequenceNumbers(int scale) {
		getSegmenter().setSequenceType(SegmentNumberType.SEGMENT_BYTE_COUNT);
		getSegmenter().setByteScale(scale);
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		try {
			getSegmenter().put(_baseName, b, off, len, false, getType(), null, null, null, _keys);
		} catch (InvalidKeyException e) {
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		} catch (InvalidAlgorithmParameterException e) {
			throw new IOException("Cannot encrypt content -- bad algorithm parameter!: " + e.getMessage());
		} 
	}

}
