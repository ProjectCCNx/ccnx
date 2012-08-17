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

import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.protocol.Component;

/**
 * IMPORTANT NOTE: For now we rely on external synchronization for access to internal values of this class
 */
public class SyncTreeEntry {
	// Flags values
	protected final static long PENDING = 1;
	protected final static long CURRENT = 2;
	protected final static long COVERED = 4;

	protected long _flags;
	protected byte[] _hash;
	protected SyncNodeComposite _nodeX = null;
	protected byte[] _rawContent = null;
	protected int _position = 0;
	
	public SyncTreeEntry(byte[] hash) {
		_hash = new byte[hash.length];
		System.arraycopy(hash, 0, _hash, 0, hash.length);
	}
	
	public void setNode(SyncNodeComposite snc) {
		_nodeX = snc;
	}
	
	public void setRawContent(byte[] content) {
		_nodeX = null;
		_rawContent = content;
		_position = 0;
	}
	
	public SyncNodeComposite getNodeX(XMLDecoder decoder) {
		if (null == _nodeX && null != _rawContent) {
			_nodeX = new SyncNodeComposite();
			try {
				_nodeX.decode(_rawContent, decoder);
			} catch (ContentDecodingException e) {
				e.printStackTrace();
				_nodeX = null;
				_rawContent = null;
				return null;
			}
			_rawContent = null;
Log.info("decode node for {0} depth = {1} refs = {2}, position = {3}", Component.printURI(_nodeX._longhash), _nodeX._treeDepth, _nodeX.getRefs().size(), _position);
		}
		return _nodeX;
	}
	
	public SyncNodeComposite.SyncNodeElement getCurrentElement() {
		if (null == _nodeX)
			return null;
		return _nodeX.getElement(_position);
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
		if (_nodeX == null)
			return false;	// Needed to prompt a getNode
		return (_position >= _nodeX.getRefs().size());
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
	
	public boolean getCurrent() {
		return (_flags & CURRENT) != 0;
	}
	
	public void setCurrent(boolean flag) {
		setFlag(flag, CURRENT);
	}
	
	public boolean isCovered() {
		return (_flags & COVERED) != 0;
	}
	
	public void setCovered(boolean flag) {
		setFlag(flag, COVERED);
	}
	
	private void setFlag(boolean flag, long type) {
		if (flag)
			_flags |= type;
		else
			_flags &= ~type;
	}
}
