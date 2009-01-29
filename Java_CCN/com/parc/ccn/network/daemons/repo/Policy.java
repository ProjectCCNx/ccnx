package com.parc.ccn.network.daemons.repo;

import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;

/**
 * 
 * @author rasmusse
 *
 */

public interface Policy {
	
	void update(InputStream stream) throws XMLStreamException;

	public ArrayList<ContentName> getNameSpace();
}
