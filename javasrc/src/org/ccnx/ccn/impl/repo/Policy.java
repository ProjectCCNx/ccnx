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

package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 * 
 * @author rasmusse
 *
 */

public interface Policy {
	
	/**
	 * Update the policy
	 * 
	 * @param stream
	 * @return - false if update is not for us.
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	boolean update(InputStream stream, boolean fromNet) throws XMLStreamException, IOException;

	public ArrayList<ContentName> getNameSpace();
	
	public ContentObject getPolicyContent();
	
	public void setVersion(String version);
	
	public void setLocalName(String localName) throws MalformedContentNameStringException;
	
	public String getLocalName();
	
	public void setGlobalPrefix(String globalName) throws MalformedContentNameStringException;
	
	public ContentName getGlobalPrefix();
}
