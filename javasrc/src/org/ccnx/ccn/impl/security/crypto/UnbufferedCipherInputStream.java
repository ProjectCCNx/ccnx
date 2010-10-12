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

package org.ccnx.ccn.impl.security.crypto;

import java.io.IOException;
import java.io.InputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;

/**
 * Hides block-related boundaries in buffering.
 * 
 * Java's javax.crypto.CipherInputStream exposes the buffering done to accommodate a block
 * Cipher, and hence reads/skip/available only return the data available in the current
 * Cipher block, rather than the perhaps larger amount of data expected from the underlying
 * stream. This class wraps javax.crypto.CipherInputStream to provide more natural semantics, and
 * avoids having repeated code to handle incomplete reads/etc.
 */
public class UnbufferedCipherInputStream extends CipherInputStream {

	protected int blockSize;

	public UnbufferedCipherInputStream(InputStream in, Cipher c) {
		super(in, c);
		blockSize = c.getBlockSize();
	}

	@Override
	public int read(byte [] data, int off, int len) throws IOException {
		int read = 0;
		int res;
		
		while (len > 0) {
			res = super.read(data, off, len);
			if (res <= 0)
				return read>0?read:res;
			off += res;
			len -= res;
			read += res;
		}
		return read;
	}

	@Override
	public int available() throws IOException {
		return super.available() + in.available();
	}

	@Override
	public long skip(long bytes) throws IOException {
		long skipped = 0;
		long res;

		while (bytes > 0) {
			res = super.skip(bytes);
			bytes -= res;
			skipped += res;
			if (bytes > 0) {
				int c = super.read();
				if (c < 0)
					return skipped>0?skipped:c;
				bytes--;
				skipped++;
			}
		}
		return skipped;
	}
}
