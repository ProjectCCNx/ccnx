/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.profiles.context;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * This is the very tiniest beginning of naming and scoping for things like
 *  /localhost, /thisroom, etc...
 */
public class ContextualNamesProfile implements CCNProfile {

	public static final ContentName LOCALHOST;
	
	static {
		ContentName tmpLH;
		try {
			tmpLH = ContentName.fromURI("ccnx:/localhost");
		} catch (MalformedContentNameStringException e) {
			tmpLH = null;
			Log.warning("Serious configuration error: cannot parse built-in name for localhost!");
		}	
		LOCALHOST = tmpLH;
	}
}
