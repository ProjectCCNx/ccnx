package com.parc.ccn.network.daemons.repo;

import java.util.ArrayList;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;

public class BitBucketRepository implements Repository {
	
	public boolean checkPolicyUpdate(ContentObject co)
			throws RepositoryException {
		// TODO Auto-generated method stub
		return false;
	}

	public ContentObject getContent(Interest interest)
			throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	public NameEnumerationResponse getNamesWithPrefix(Interest i) {
		// TODO Auto-generated method stub
		return null;
	}

	public byte[] getRepoInfo(ArrayList<ContentName> names) {
		try {
			return (new RepositoryInfo("Repository", "/parc.com/csl/ccn/Repos", "1.0")).encode();
		} catch (Exception e) {}
		return null;
	}

	public static String getUsage() {
		// TODO Auto-generated method stub
		return null;
	}

	public String[] initialize(String[] args, CCNLibrary library) throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	public NameEnumerationResponse saveContent(ContentObject content) throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	public void setPolicy(Policy policy) {
	}
	
	public ArrayList<ContentName> getNamespace() {
		ArrayList<ContentName> al = new ArrayList<ContentName>();
		try {
			al.add(ContentName.fromNative("/"));
		} catch (MalformedContentNameStringException e) {}
		return al;
	}

	public boolean diagnostic(String name) {
		// No supported diagnostics
		return false;
	}

	public void shutDown() {
		// TODO Auto-generated method stub
		
	}
}
