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

import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.protocol.ContentName;

/**
 * This is the very tiniest beginning of naming and scoping for things like
 *  /localhost, /thisroom, etc...
 */
public class ContextualNamesProfile implements CCNProfile {

	public static final String STRING_LOCALHOST = "localhost";

	public static final ContentName LOCALHOST_SCOPE =
		new ContentName(new byte [][]{CommandMarker.COMMAND_MARKER_SCOPE.addArgument(STRING_LOCALHOST)});
	
}
