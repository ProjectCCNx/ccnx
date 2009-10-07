package org.ccnx.ccn.impl;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * A simple server that takes a set of blocks and makes them available
 * to readers. Unlike standard flow controllers, this class doesn't care if
 * anyone ever reads its blocks -- it doesn't call waitForPutDrain. If no one
 * is interested, it simply persists until deleted/cleared. 
 * This version of flow server holds blocks indefinitely, regardless of how
 * many times they are read, until they are manually removed or cleared, or the 
 * server is deleted by canceling all its registered prefixes. It does not
 * signal an error if the blocks are never read.
 */
public class CCNPersistentFlowServer extends CCNFlowControl {

	boolean _persistent = true;
	
	public CCNPersistentFlowServer(ContentName name, Integer capacity, CCNHandle handle) throws IOException {
		super(name, handle);
		if (null != capacity)
			setCapacity(capacity);
	}

	public CCNPersistentFlowServer(String name, Integer capacity, CCNHandle handle)
			throws MalformedContentNameStringException, IOException {
		super(name, handle);
		if (null != capacity)
			setCapacity(capacity);
	}

	public CCNPersistentFlowServer(Integer capacity, CCNHandle handle) throws IOException {
		super(handle);
		if (null != capacity)
			setCapacity(capacity);
	}
	
	/**
	 * Do not remove objects from the buffered pool as they are written to the
	 * network; keep them around as an in-memory server. Objects can be removed
	 * manually with the remove() interface.
	 * 
	 * @param co ContentObject to remove from flow controller.
	 * @throws IOException may be thrown by overriding subclasses
 	 */
	@Override
	public void afterPutAction(ContentObject co) throws IOException {
		// do nothing
	}

	/**
	 * Do not force client to wait for content to drain -- if
	 * no one wants it, that's just fine.
	 */
	@Override
	public void afterClose() {
		// do nothing
	}
}
