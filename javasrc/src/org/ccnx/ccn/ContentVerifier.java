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

package org.ccnx.ccn;

import org.ccnx.ccn.protocol.ContentObject;

/**
 * A callback interface to allow low-level mechanisms to ask someone else to verify content
 * on their behalf.
 */
public interface ContentVerifier {
	
	/**
	 * Verify this content object, potentially in the context of other data held by the verifier.
	 * This may be a simple signature verification, which might take advantage of cached data,
	 * or a more complex trust calculation determining the acceptability of this content for
	 * a particular use.
	 * @param content the object whose signature we should verify.
	 * @return
	 */
	public boolean verify(ContentObject content);

}
