/**
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

package org.ccnx.ccn.profiles.metadata;

import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.protocol.ContentName;

public class MetadataProfile implements CCNProfile {

	public static final byte [] METADATA_MARKER = ContentName.componentParseNative(MARKER + "meta" + MARKER);
	
	public static ContentName metadataName(ContentName baseName) {
		return new ContentName(baseName, METADATA_MARKER);
	}
}
