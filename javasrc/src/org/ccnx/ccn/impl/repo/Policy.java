/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 * Policy is the interface used by the RepositoryStore for interpreting and applying policy 
 * data to CCN repositories.
 */

public interface Policy {
	
	/**
	 * Update the policy
	 * 
	 * @param pxml	an XML decoded policy structure
	 * @param fromNet	true if request is from the network as opposed to reading a local file
	 * @throws XMLStreamException	if the policy data is incorrect or inconsistent
	 * @throws IOException			on stream read errors
	 */
	public void update(PolicyXML pxml, boolean fromNet) throws RepositoryException;
	
	/**
	 * Update the policy from a file
	 * This assumes that the update is not "from the network"
	 * 
	 * @param is stream for the file
	 */
	public void updateFromInputStream(InputStream is) throws RepositoryException;

	/**
	 * Gets the current namespace covered by this repository. Any name not included within
	 * this namespace will not be stored in this repository.
	 * 
	 * @return			array of ContentNames specifying the namespace
	 */
	public ArrayList<ContentName> getNamespace();
	
	/**
	 * 
	 */
	public void setNamespace(ArrayList<ContentName> nameSpace);
	
	/**
	 * Set the version of the policy protocol which is currently valid.  Depending on the
	 * implementation, any policy file containing a protocol with a different version ID may
	 * be rejected.
	 * 
	 * @param version the version to use
	 */
	public void setVersion(String version);
	
	/**
	 * The localName is used to identify an individual repository among several in an organization
	 * or other entity.
	 * 
	 * @param localName the name as a string in the form xxx/yyy/zzz
	 * @throws MalformedContentNameStringException	if the name is formatted incorrectly
	 */
	public void setLocalName(String localName) throws MalformedContentNameStringException;
	
	/**
	 * @return - the local name of this repository as a String in the form xxx/yyy/zzz
	 */
	public String getLocalName();
	
	/**
	 * The globalPrefix is used to identify a path to repositories within an organization
	 * or entity. Several local repositories could be contained with an organizations global
	 * repository namespace.
	 * 
	 * @param globalName the prefix as a string in the form xxx/yyy/zzz
	 * @throws MalformedContentNameStringException	if the name is formatted incorrectly
	 */
	public void setGlobalPrefix(String globalName) throws MalformedContentNameStringException;
	
	/**
	 * @return - the local name of this repository as a String in the form xxx/yyy/zzz
	 */
	public ContentName getGlobalPrefix();
	
	/**
	 * @return - get the associated policyXML
	 * @return
	 */
	public PolicyXML getPolicyXML();
	
	/**
	 * Set the associated policyXML
	 * @param pxml the PolicyXML
	 */
	public void setPolicyXML(PolicyXML pxml);
}
