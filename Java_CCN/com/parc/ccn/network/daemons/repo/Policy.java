package com.parc.ccn.network.daemons.repo;

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
	
	public void setGlobalPrefix(String globalName) throws MalformedContentNameStringException;
}
