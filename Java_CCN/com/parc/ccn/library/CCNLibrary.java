package com.parc.ccn.library;

import com.parc.ccn.data.CCNBase;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;

public interface CCNLibrary extends CCNBase {

	public void put(ContentName name, byte [] contents);
	
	public void newVersion(ContentName name, byte [] contents);
	public void newVersion(ContentName name, int version, byte [] contents);
	
	public void link(ContentName src, ContentName dest);
	
	public void addCollection(ContentName name, ContentName [] contents);
	public void addCollection(ContentName name, ContentObject [] contents);
	
	public void addToCollection(ContentName name, ContentName [] additionalContents);
	public void addToCollection(ContentName name, ContentObject [] additionalContents);
	public void removeFromCollection(ContentName name, ContentName [] additionalContents);
	public void removeFromCollection(ContentName name, ContentObject [] additionalContents);
}
