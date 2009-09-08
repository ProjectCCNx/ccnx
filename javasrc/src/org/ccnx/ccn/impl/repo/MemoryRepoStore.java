package org.ccnx.ccn.impl.repo;

import java.io.File;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * MemoryRepoStore is a transient, in-memory store for applications that 
 * wish to provide a repo for their own content as long as they are running.
 * Given that it is transient, this implementation breaks the usual convention
 * that a repository provides persistent storage. 
 * @author jthornto
 *
 */
public class MemoryRepoStore extends RepositoryStoreBase implements RepositoryStore, ContentTree.ContentGetter {
	public final static String CURRENT_VERSION = "1.0";
	protected ContentTree _index;
	protected ContentName _namespace = null; // Prinmary/initial namespace

	// For in-memory repo, we just hold onto each ContentObject directly in the ref
	class MemRef extends ContentRef {
		ContentObject co;
		public MemRef(ContentObject content) {
			co = content;
		}
	}
	
	public MemoryRepoStore(ContentName namespace) {
		_namespace = namespace;
	}

	public String getVersion() {
		return CURRENT_VERSION;
	}
	
	public void initialize(CCNHandle handle, String repositoryRoot, File policyFile, String localName, String globalPrefix) throws RepositoryException {
		if (null != _index) {
			throw new RepositoryException("Attempt to re-initialize " + this.getClass().getName());
		}
		_index = new ContentTree();
		if (null != _namespace) {
			ArrayList<ContentName> ns = new ArrayList<ContentName>();
			ns.add(_namespace);
			_policy = new BasicPolicy(null, ns);
			_policy.setVersion(CURRENT_VERSION);
		} else {
			startInitPolicy(policyFile);
		}
		// We never have any persistent content so don't try to read policy from there with readPolicy()
		try {
			finishInitPolicy(localName, globalPrefix);
		} catch (MalformedContentNameStringException e) {
			throw new RepositoryException(e.getMessage());
		}
	}

	public ContentObject get(ContentRef ref) {
		if (null == ref) {
			return null;
		} else {
			// This is a call back based on what we put in ContentTree, so it must be
			// using our subtype of ContentRef
			MemRef mref = (MemRef)ref;
			return mref.co;
		}
	}
	
	public NameEnumerationResponse saveContent(ContentObject content) throws RepositoryException {
		// Note: we're trusting the app to store what it wants and not implementing any 
		// namespace restrictions here
		MemRef ref = new MemRef(content);
		NameEnumerationResponse ner = new NameEnumerationResponse();
		_index.insert(content, ref, System.currentTimeMillis(), this, ner);
		return ner;
	}
	
	public ContentObject getContent(Interest interest)
	throws RepositoryException {
		return _index.get(interest, this);
	}
		
	public NameEnumerationResponse getNamesWithPrefix(Interest i) {
		return _index.getNamesWithPrefix(i, this);
	}

    public void shutDown() {
    	// no-op
    }
    
}
