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

package org.ccnx.ccn.impl.sync;

import java.util.Arrays;
import java.util.logging.Level;

import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.protocol.Component;

/**
 * Entry used for navigating trees of hashes
 * 
 * IMPORTANT NOTE: For now we rely on external synchronization for access to internal values of this class
 */
public class SyncTreeEntry {
	// Flags values
	protected final static long PENDING = 1; 	// Indicates a request for the node has been sent but not yet
												// answered
	protected final static long COVERED = 2;	// Indicates that all names covered by these references have been seen

	protected long _flags;
	protected byte[] _hash = null;				// The hash associated with the node associated with this entry
	protected SyncNodeComposite _node = null;
	protected byte[] _rawContent = null;
	protected int _position = 0;
	
	public SyncTreeEntry(byte[] hash) {
		_hash = new byte[hash.length];
		System.arraycopy(hash, 0, _hash, 0, hash.length);
	}
	
	public void setNode(SyncNodeComposite snc) {
		_node = snc;
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
			SyncNodeComposite.decodeLogging(_node);
		}
	}
	
	/**
	 * This is used in the case where we have content for a SyncNodeComposite but
	 * don't have its hash until we decode it.
	 * 
	 * @param content
	 */
	public void setRawContent(byte[] content) {
		_node = null;
		_rawContent = content;
		_position = 0;
	}
	
	/**
	 * Decodes the hash if not yet done. Note that this should not be called from a
	 * handler (unless we already know the node is decoded) because decoding is long and 
	 * expensive and could stall the netmanager thread. We allow a separate decoder because
	 * nodes typically contain more elements than are usually allowed by default by the
	 * standard decoder. We can speed things up by preallocating more space.
	 * 
	 * @param decoder
	 * @return
	 */
	public SyncNodeComposite getNode(XMLDecoder decoder) {
		if (null == _node && null != _rawContent) {
			_node = new SyncNodeComposite();
			try {
				_node.decode(_rawContent, decoder);
			} catch (ContentDecodingException e) {
				Log.warning("Couldn't decode node {0} due to: {1}", (_hash == null ? "(unknown)"
						: Component.printURI(_hash)), e.getMessage());
				_node = null;
				_rawContent = null;
				return null;
			}
			_rawContent = null;
			if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
				SyncNodeComposite.decodeLogging(_node);
			}
		}
		return _node;
	}
	
	/**
	 * Routines for getting and cycling through the references
	 * @return
	 */
	public SyncNodeComposite.SyncNodeElement getCurrentElement() {
		if (null == _node)
			return null;
		return _node.getElement(_position);
	}
	
	public void incPos() {
		_position++;
	}
	
	public void setPos(int position) {
		_position = position;
	}
	
	public int getPos() {
		return _position;
	}
	
	public boolean lastPos() {
		if (_node == null)
			return false;	// Needed to prompt a getNode
		return (_position >= _node.getRefs().size());
	}
	
	public byte[] getHash() {
		return _hash;
	}
	
	public void setPending(boolean flag) {
		setFlag(flag, PENDING);
	}
	
	public boolean getPending() {
		return (_flags & PENDING) != 0;
	}
		
	public boolean isCovered() {
		return (_flags & COVERED) != 0;
	}
	
	public void setCovered(boolean flag) {
		setFlag(flag, COVERED);
	}
	
	public boolean equals(SyncTreeEntry ste) {
		return Arrays.equals(_hash, ste.getHash());
	}
	
	private void setFlag(boolean flag, long type) {
		if (flag)
			_flags |= type;
		else
			_flags &= ~type;
	}
}
