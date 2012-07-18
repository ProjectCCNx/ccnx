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
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.SyncNodeComposite;

/**
 * IMPORTANT NOTE: For now we rely on external synchronization for access to internal values of this class
 */
public class SyncRootTree {
	protected byte[] _hash;
	protected SyncNodeComposite _nextNode = null;
	protected byte[] _rawContent = null;
	protected XMLDecoder _decoder;
	protected boolean _pending = false;
	
	public SyncRootTree(byte[] hash, XMLDecoder decoder) {
		_hash = new byte[hash.length];
		_decoder = decoder;
		System.arraycopy(hash, 0, _hash, 0, hash.length);
	}
	
	public void setRawContent(byte[] content) {
		_rawContent = content;
	}
	
	public SyncNodeComposite getNextNode() {
		if (null == _nextNode && null != _rawContent) {
			_nextNode = new SyncNodeComposite();
			try {
				_nextNode.decode(_rawContent, _decoder);
			} catch (ContentDecodingException e) {
				e.printStackTrace();
				_nextNode = null;
				_rawContent = null;
				return null;
			}
			_rawContent = null;
		}
		return _nextNode;
	}
	
	public byte[] getHash() {
		return _hash;
	}
	
	public void setPending(boolean flag) {
		_pending = flag;
	}
	
	public boolean getPending() {
		return _pending;
	}
}
