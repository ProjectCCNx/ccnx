package com.parc.ccn.library.profiles;

import com.parc.ccn.data.ContentName;

public class MetadataProfile implements CCNProfile {

	public static final byte [] METADATA_MARKER = ContentName.componentParseNative(MARKER + "meta" + MARKER);
	
	public static ContentName metadataName(ContentName baseName) {
		return new ContentName(baseName, METADATA_MARKER);
	}
}
