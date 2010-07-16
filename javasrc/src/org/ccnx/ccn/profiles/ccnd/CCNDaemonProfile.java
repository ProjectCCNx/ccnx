/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2009, 2010 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

public class CCNDaemonProfile {
	
	public static final ContentName ping;
	
	static {
		ContentName tmpPing;
		try {
			tmpPing = ContentName.fromURI("ccnx:/ccnx/ping/");
		} catch (MalformedContentNameStringException e) {
			tmpPing = null;
		}
		ping = tmpPing;
	}
}
