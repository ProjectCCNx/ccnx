/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
import org.ccnx.ccn.protocol.Interest;

/**
 * A handler used to receive callbacks when data arrives matching one of our
 * asynchronously-expressed Interests (expressed with CCNBase#expressInterest(Interest, CCNInterestHandler)).
 * Once the handler is called with matching data, the Interest is canceled. As a convenience,
 * the handler can return a new Interest, which will be expressed on its behalf, using
 * it as the callback listener when data is returned in response. This new Interest can be
 * the same as the previous Interest, derived from it, or completely unrelated. Since data
 * consumes Interest, there can only be a single response for one Interest expression.
 * 
 * @see CCNBase
 */
public interface CCNContentHandler {
	
	/**
	 * Callback called when we get new results for our query.
	 * @param data the ContentObject that matched our Interest
	 * @param interest Interest that was satisfied
	 * @return new Interest to be expressed
	 */
    public Interest handleContent(ContentObject data, Interest interest);
    
}
