/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.ccnd;

import java.io.IOException;
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

public class CCNDCacheManager implements CCNContentHandler {
	int stale;
	public void clearCache(ContentName prefix, CCNHandle handle, long timeout) throws IOException {
		Interest interest = new Interest(prefix);
		interest.answerOriginKind(Interest.DEFAULT_ANSWER_ORIGIN_KIND + Interest.MARK_STALE);
		interest.scope(0);
		long startTime = System.currentTimeMillis();
		long endTime = startTime + timeout;
		boolean noTimeout = timeout == SystemConfiguration.NO_TIMEOUT;
		int prevStale;
		stale = prevStale = 0;
		handle.expressInterest(interest, this);
		while (noTimeout || System.currentTimeMillis() < endTime) {
			synchronized (this) {
				try {
					this.wait(SystemConfiguration.MEDIUM_TIMEOUT);
				} catch (InterruptedException e) {}
				if (prevStale == stale) {
					handle.cancelInterest(interest, this);
					if (Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER))
						Log.finer(Log.FAC_NETMANAGER, "ClearCache finished after {0} ms, marked {1} stale.",
								System.currentTimeMillis() - startTime, stale);
					return;
				}
				prevStale = stale;
			}
		}
		handle.cancelInterest(interest, this);
		Log.warning(Log.FAC_NETMANAGER, "ClearCache timed out before completion, marked {0} stale.", stale);
		throw new IOException("ClearCache timed out before completion");
	}

	public Interest handleContent(ContentObject data, Interest interest) {
		synchronized (this) {
			stale++;
		}
		if (Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER))
			Log.finer(Log.FAC_NETMANAGER, "Set {0} stale", data.name());
		return interest;
	}
}
