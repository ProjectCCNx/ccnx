/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.io.content;

import static org.ccnx.ccn.impl.encoding.CCNProtocolDTags.SyncVersion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;

import org.ccnx.ccn.CCNSync;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;

/**
 * A SyncNodeComposite object holds the necessary data for a sync tree node
 */    
public class SyncNodeComposite extends GenericXMLEncodable implements XMLEncodable, Cloneable {
	public enum SyncNodeType {HASH, LEAF, COMPONENT, BINARY};
	
	public static class SyncNodeElement {
		public SyncNodeType _type = SyncNodeType.LEAF;
		public ContentName _name;
		public byte[] _data;
		
		public SyncNodeElement() {}
		
		public SyncNodeElement(ContentName name) {
			_name = name;
		}
		
		public SyncNodeType getType() {
			return _type;
		}
		
		public ContentName getName() {
			return _name;
		}
		
		public byte[] getData() {
			return _data;
		}
		
		public void decode(XMLDecoder decoder) throws ContentDecodingException {
			if (decoder.peekStartElement(CCNProtocolDTags.Name)) {
				_name = new ContentName();
				_name.decode(decoder);
			} else if (decoder.peekStartElement(CCNProtocolDTags.SyncContentHash)) {
				_data = decoder.readBinaryElement(CCNProtocolDTags.SyncContentHash);
				_type = SyncNodeType.HASH;
			} else if (decoder.peekStartElement(CCNProtocolDTags.Component)) {
				_data = decoder.readBinaryElement(CCNProtocolDTags.Component);
				_type = SyncNodeType.COMPONENT;
			} else if (decoder.peekStartElement(CCNProtocolDTags.BinaryValue)) {
				_data = decoder.readBinaryElement(CCNProtocolDTags.BinaryValue);
				_type = SyncNodeType.BINARY;
			} else
				throw new ContentDecodingException("Unexpected element in SyncNodeElements");
		}
	}
	
	public int _version;
	public ArrayList<SyncNodeElement> _refs = new ArrayList<SyncNodeElement>();
	public byte[] _longhash;
	public SyncNodeElement _minName;
	public SyncNodeElement _maxName;
	public int _kind;
	public int _leafCount;
	public int _treeDepth;
	public int _byteCount;
	
	public SyncNodeComposite() {}
	
	public SyncNodeComposite(ArrayList<SyncNodeElement> refs) {
		_refs = refs;
		if (_refs.size() > 0) {	// error if not?
			_minName = _refs.get(0);
			_maxName = refs.get(_refs.size() - 1);
		}
		computeLeafHash();
		_treeDepth = 1;
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
			Log.finest(Log.FAC_SYNC, "Creating new node:");
			decodeLogging(this);
		}
	}
	
	public ArrayList<SyncNodeElement> getRefs() {
		return _refs;
	}
	
	public SyncNodeElement getElement(int position) {
		if (position >= _refs.size())
			return null;
		return _refs.get(position);
	}

	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		_version = decoder.readIntegerElement(SyncVersion);
		if (_version != Sync.SYNC_VERSION)
			throw new ContentDecodingException("Sync version mismatch: " + _version);
		if (decoder.peekStartElement(CCNProtocolDTags.SyncNodeElements)) {
			decoder.readStartElement(CCNProtocolDTags.SyncNodeElements);
			while (true) {
				try {
					SyncNodeElement ref = new SyncNodeElement();
					ref.decode(decoder);
					_refs.add(ref);
				} catch (ContentDecodingException cde) {
					break;
				}
			}
			decoder.readEndElement();
		}
		if (decoder.peekStartElement(CCNProtocolDTags.SyncContentHash)) {
			decoder.readStartElement(CCNProtocolDTags.SyncContentHash);
			_longhash = decoder.readBlob();
		}
		
		_minName = new SyncNodeElement();
		_minName.decode(decoder);
		_maxName = new SyncNodeElement();
		_maxName.decode(decoder);
		_kind = decoder.readIntegerElement(CCNProtocolDTags.SyncNodeKind);
		_leafCount = decoder.readIntegerElement(CCNProtocolDTags.SyncLeafCount);
		_treeDepth = decoder.readIntegerElement(CCNProtocolDTags.SyncTreeDepth);
		_byteCount = decoder.readIntegerElement(CCNProtocolDTags.SyncByteCount);
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		// We shouldn't need to encode these - will fill in later if we do...
	}

	public long getElementLabel() {
		return CCNProtocolDTags.SyncNode;
	}

	public boolean validate() {
		return _version == Sync.SYNC_VERSION;
	}
	
	public SyncNodeElement getMinName() {
		return _minName;
	}
	
	public SyncNodeElement getMaxName() {
		return _maxName;
	}
	
	public byte[] getHash() {
		return _longhash;
	}
	
	public static void decodeLogging(SyncNodeComposite node) {
		Log.finest(Log.FAC_SYNC, "decode node for {0} depth = {1} refs = {2}", Component.printURI(node._longhash), 
				node._treeDepth, node.getRefs().size());
		Log.finest(Log.FAC_SYNC, "min is {0}, max is {1}, expanded min is {2}, expanded max is {3}", 
				SegmentationProfile.getSegmentNumber(node._minName.getName().parent()), 
				SegmentationProfile.getSegmentNumber(node._maxName.getName().parent()),
				node._minName.getName(), node._maxName.getName());
	}
	
	/**
	 * The C code handles different sized hashes and digests. Since I currently
	 * believe that digests are always 32 bytes, I'm not worrying about that for now...
	 */
	private void computeLeafHash() {
		_longhash = new byte[CCNSync.SYNC_HASH_LENGTH];
		Arrays.fill(_longhash, (byte)0);
		for (SyncNodeElement sne : _refs) {
			ContentName name = sne.getName();
			int xs = CCNSync.SYNC_HASH_LENGTH;
			byte[] nc = name.lastComponent();
			if (null != nc && nc.length >= CCNSync.SYNC_HASH_LENGTH) { // Should always be true
				accumHash(xs, nc);
			}
		}
	}
	
	private void accumHash(int xs, byte[] toAdd) {
		int c = 0;
		while (xs > 0) {
			xs--;
			int val = c;
			val = val + _longhash[xs] + toAdd[xs];
			c = (val >> 8) & 255;
			_longhash[xs] = (byte)(val & 255);
		}
	}
}
