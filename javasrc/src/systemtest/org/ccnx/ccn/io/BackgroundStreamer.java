/*
 * A CCNx library test.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.io;

import java.io.IOException;

import junit.framework.Assert;

public class BackgroundStreamer implements Runnable {
	CCNInputStream _stream = null;
	byte[] _expected = null;

	public BackgroundStreamer(CCNInputStream stream, byte[] expected, boolean useTimeout, long timeout) {
		_stream = stream;
		_expected = expected;
		if (useTimeout)
			_stream.setTimeout(timeout);
	}

	public void close() throws IOException {
		_stream.close();
	}

	public void run() {
		int i = 0;
		try {
			int val;
			do {
				val = _stream.read();
				if (val != -1) {
					Assert.assertTrue("Read too much data - length should be: " + _expected.length, i < _expected.length);
					Assert.assertEquals("Received incorrect data", _expected[i], (byte)val);
					i++;
				}
			} while (val != -1);
		} catch (IOException e) {
			Assert.fail("Input stream timed out or read failed: " + e.getMessage());
		}
		Assert.assertFalse("Received too little data - length should be: " + _expected.length + " and length was: " + i, i < _expected.length);	
	}
}

