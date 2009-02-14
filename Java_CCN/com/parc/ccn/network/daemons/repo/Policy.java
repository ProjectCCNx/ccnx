package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;

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
	boolean update(InputStream stream) throws XMLStreamException, IOException;

	public ArrayList<ContentName> getNameSpace();
	
	public ContentObject getPolicyContent();
}
