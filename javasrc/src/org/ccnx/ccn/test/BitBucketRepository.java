package org.ccnx.ccn.test;

import java.io.File;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.repo.Policy;
import org.ccnx.ccn.impl.repo.RepositoryStore;
import org.ccnx.ccn.impl.repo.RepositoryException;
import org.ccnx.ccn.impl.repo.RepositoryInfo;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class BitBucketRepository implements RepositoryStore {
	
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
			return (new RepositoryInfo("1.0", "/parc.com/csl/ccn/Repos", "Repository")).encode();
		} catch (Exception e) {}
		return null;
	}

	public static String getUsage() {
		// TODO Auto-generated method stub
		return null;
	}

	public void initialize(CCNHandle handle, String repositoryRoot, File policyFile, String localName, String globalPrefix) throws RepositoryException {
		// TODO Auto-generated method stub
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

	public Policy getPolicy() {
		// TODO Auto-generated method stub
		return null;
	}
}
