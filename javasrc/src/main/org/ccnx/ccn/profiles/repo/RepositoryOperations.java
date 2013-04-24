/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.repo;

import static org.ccnx.ccn.profiles.CommandMarker.*;

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;

/**
 * Define special meanings of data within received interests for the repository
 */
public class RepositoryOperations {
	
	public static boolean isStartWriteOperation(Interest interest) {
		return interest.name().contains(COMMAND_MARKER_REPO_START_WRITE);
	}

	public static boolean isNameEnumerationOperation(Interest interest) {
		return interest.name().contains(COMMAND_MARKER_BASIC_ENUMERATION);
	}

	public static boolean isCheckedWriteOperation(Interest interest) {
		return interest.name().contains(COMMAND_MARKER_REPO_CHECKED_START_WRITE);
	}
	
	public static boolean isBulkImportOperation(Interest interest) {
		int i = COMMAND_MARKER_REPO_ADD_FILE.findMarker(interest.name());
		return i >= 0;
	}
	
	public static int getCheckedWriteMarkerPos(Interest interest) {
		return interest.name().whereLast(COMMAND_MARKER_REPO_CHECKED_START_WRITE);
	}

	public static boolean verifyCheckedWrite(Interest interest) {
		return (getCheckedWriteMarkerPos(interest) + 3) < interest.name().count();
	}
	
	// Strip out the command marker and nonce so we get name with those components removed from middle
	public static ContentName getCheckedWriteTarget(Interest interest) {
		ContentName orig = interest.name();
		int pos = getCheckedWriteMarkerPos(interest);
		ContentName head = orig.subname(0, pos);
		ContentName tail = orig.subname(pos+2, orig.count());
		return head.append(tail);
	}
}
