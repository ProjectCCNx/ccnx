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

import org.ccnx.ccn.ThreadAssertionRunner;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.io.CCNAbstractInputStream.FlagTypes;
import org.ccnx.ccn.protocol.ContentName;

public class CCNInputStreamTestCommon {
	
	public static void blockAfterFirstSegmentTest(ContentName testName, CCNInputStream stream, CCNOutputStream ostream) throws Error, Exception {
		byte[] bytes = new byte[800];
		for (int i = 0; i < bytes.length; i++)
			bytes[i] = (byte)i;
		stream.addFlag(FlagTypes.BLOCK_AFTER_FIRST_SEGMENT);
		BackgroundStreamer bas = new BackgroundStreamer(stream, bytes, false, 0);
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new Thread(bas));
		tar.start();
		ostream.setBlockSize(100);
		ostream.setTimeout(SystemConfiguration.NO_TIMEOUT);
		ostream.write(bytes, 0, 400);
		ostream.flush();
		Thread.sleep(SystemConfiguration.getDefaultTimeout() * 2);
		ostream.write(bytes, 400, 400);
		ostream.close();
		tar.join(SystemConfiguration.EXTRA_LONG_TIMEOUT * 2);
		bas.close();
	}
}
