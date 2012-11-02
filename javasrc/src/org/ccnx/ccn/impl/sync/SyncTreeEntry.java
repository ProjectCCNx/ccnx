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
	protected SyncNodeCache _snc = null;
	
	public SyncTreeEntry(byte[] hash, SyncNodeCache cache) {
		_hash = new byte[hash.length];
		System.arraycopy(hash, 0, _hash, 0, hash.length);
		_node = cache.getNode(hash);
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST) && _node != null)
			Log.finest(Log.FAC_SYNC, "Found existing node: {0}", Component.printURI(hash));
		_snc = cache;
	}
	
	public void setNode(SyncNodeComposite snc) {
		synchronized (this) {
			if (_node != null)
				return;
			_node = snc;
			_snc.putNode(snc);			
		}
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
		synchronized (this) {
			if (_node != null)
				return;
			_rawContent = content;
			_position = 0;
		}
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
		SyncNodeComposite node = _node;
		synchronized (this) {
			if (null != _node || null == _rawContent || null == decoder)
				return _node;
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
			node = _node;
			_snc.putNode(_node);
		}
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
			SyncNodeComposite.decodeLogging(_node);
		}
		return node;
	}
	
	public SyncNodeComposite getNode() {
		return getNode(null);
	}
	
	/**
	 * Routines for getting and cycling through the references
	 * @return
	 */
	public synchronized SyncNodeComposite.SyncNodeElement getCurrentElement() {
		if (null == _node)
			return null;
		return _node.getElement(_position);
	}
	
	public synchronized void incPos() {
		_position++;
	}
	
	public synchronized void setPos(int position) {
		_position = position;
	}
	
	public synchronized int getPos() {
			return _position;
	}
	
	public synchronized boolean lastPos() {
		if (_node == null)
			return false;	// Needed to prompt a getNode
		return (_position >= _node.getRefs().size());
	}
	
	public synchronized byte[] getHash() {
		return _hash;
	}
	
	public void setPending(boolean flag) {
		setFlag(flag, PENDING);
	}
	
	public synchronized boolean getPending() {
			return (_flags & PENDING) != 0;
	}
		
	public synchronized boolean isCovered() {
		return (_flags & COVERED) != 0;
	}
	
	public void setCovered(boolean flag) {
		setFlag(flag, COVERED);
	}
	
	public synchronized boolean equals(SyncTreeEntry ste) {
		return Arrays.equals(_hash, ste.getHash());
	}
	
	private void setFlag(boolean flag, long type) {
		synchronized (this) {
			if (flag)
				_flags |= type;
			else
				_flags &= ~type;
		}
	}
}
