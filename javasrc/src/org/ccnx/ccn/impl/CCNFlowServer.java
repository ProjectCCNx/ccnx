package org.ccnx.ccn.impl;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

public class CCNFlowServer extends CCNFlowControl {

	public CCNFlowServer(ContentName name, Integer capacity, CCNHandle handle) throws IOException {
		super(name, handle);
		if (null != capacity)
			setCapacity(capacity);
	}

	public CCNFlowServer(String name, Integer capacity, CCNHandle handle)
			throws MalformedContentNameStringException, IOException {
		super(name, handle);
		if (null != capacity)
			setCapacity(capacity);
	}

	public CCNFlowServer(Integer capacity, CCNHandle handle) throws IOException {
		super(handle);
		if (null != capacity)
			setCapacity(capacity);
	}
	
	/**
	 * Do not remove objects from the buffered pool as they are written to the
	 * network; keep them around as an in-memory server. Objects can be removed
	 * manually with the remove() interface.
	 */
	public void afterPutAction() {
		// do nothing
	}

	/**
	 * Do not force client to wait for content to drain -- if
	 * no one wants it, that's just fine.
	 */
	public void afterClose() {
		// do nothing
	}
}
