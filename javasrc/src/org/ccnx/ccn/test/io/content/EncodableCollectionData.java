/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.io.content;

import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.EncodableObject;

/**
 * Helper class testing low-level (non-CCN) encodable object functionality.
 */
public class EncodableCollectionData extends EncodableObject<Collection> {

	public EncodableCollectionData() {
		super(Collection.class, true);
	}
	
	public EncodableCollectionData(Collection collectionData) {
		super(Collection.class, true, collectionData);
	}
	
	public Collection collection() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
}
