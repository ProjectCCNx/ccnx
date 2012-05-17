package org.ccnx.ccn;

import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.protocol.ContentName;

public interface CCNSyncHandler {

	public void handleContentName(ConfigSlice syncSlice, ContentName syncedContent);
	
}
