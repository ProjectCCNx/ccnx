package com.parc.ccn.security.access;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.library.CCNLibrary;

public class ACL extends CollectionData {
	
	public static final String LABEL_READER = "r";
	public static final String LABEL_WRITER = "rw";
	public static final String LABEL_MANAGER = "rw+";
	public static final String [] ROLE_LABELS = {LABEL_READER, LABEL_WRITER, LABEL_MANAGER};
	
	// maintain a set of index structures
	protected LinkedList<LinkReference> _readers = new LinkedList<LinkReference>();
	protected LinkedList<LinkReference> _writers = new LinkedList<LinkReference>();
	protected LinkedList<LinkReference> _managers = new LinkedList<LinkReference>();

	public static class ACLObject extends CCNEncodableObject<ACL> {

		public ACLObject(ContentName name, ACL data, CCNLibrary library) throws ConfigurationException, IOException {
			super(ACL.class, name, data, library);
		}
		
		public ACLObject(ContentName name, PublisherPublicKeyDigest publisher,
				CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
			super(ACL.class, name, publisher, library);
		}
		
		/**
		 * Read constructor -- opens existing object.
		 * @param type
		 * @param name
		 * @param library
		 * @throws XMLStreamException
		 * @throws IOException
		 * @throws ClassNotFoundException 
		 */
		public ACLObject(ContentName name, 
				CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
			super(ACL.class, name, (PublisherPublicKeyDigest)null, library);
		}
		
		public ACLObject(ContentObject firstBlock,
				CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
			super(ACL.class, firstBlock, library);
		}
		
		public ACL acl() { return data(); }
	}

	public ACL() {
		super();
	}

	public ACL(ArrayList<LinkReference> contents) {
		super(contents);
		if (!validate()) {
			throw new IllegalArgumentException("Invalid contents for ACL.");
		}
	}
		
	public boolean validLabel(LinkReference lr) {
		return LABEL_MANAGER.contains(lr.targetLabel());
	}

	@Override
	public boolean validate() {
		if (!super.validate())
			return false;
		for (LinkReference lr : contents()) {
			if ((null == lr.targetLabel()) || (!validLabel(lr))) {
				return false;
			}
		}
		return true;
	}
	
	public void addReader(LinkReference reader) {
		addLabeledLink(reader, LABEL_READER);
	}
	
	public void addWriter(LinkReference writer) {
		addLabeledLink(writer, LABEL_WRITER);
	}
	
	public void addManager(LinkReference manager) {
		addLabeledLink(manager, LABEL_MANAGER);
	}
	
	/**
	 * If we are performing a set of simultaneous modifications to an ACL, we need to
	 * handle them in parallel, in order to resolve interdependencies and derive the
	 * minimal necessary changes to the underlying cryptographic data.
	 * @param addReaders
	 * @param removeReaders
	 * @param addWriters
	 * @param removeWriters
	 * @param addManagers
	 * @param removeManagers
	 * @return We return a LinkedList<LinkReference> of the principals newly granted read
	 *   access on this ACL. If no individuals are granted read access, we return a 0-length
	 *   LinkedList. If any individuals are completely removed, requiring the caller to generate
	 *   a new node key or otherwise update cryptographic data, we return null.
	 *   (We could return the removed principals, but it's a little weird -- some people are
	 *   removed from a role and added to others. For now, we just return the thing we need
	 *   for our current implementation, which is whether anyone lost read access entirely.)
	 */
	public LinkedList<LinkReference> 
		update(ArrayList<LinkReference> addReadersIn, ArrayList<LinkReference> removeReadersIn,
					   ArrayList<LinkReference> addWritersIn, ArrayList<LinkReference> removeWritersIn,
					   ArrayList<LinkReference> addManagersIn, ArrayList<LinkReference> removeManagersIn) {
		
		// Need copies we can modifiy, caller might want these back.
		LinkedList<LinkReference> addReaders = new LinkedList<LinkReference>(addReadersIn);
		LinkedList<LinkReference> removeReaders = new LinkedList<LinkReference>(removeReadersIn);
		LinkedList<LinkReference> addWriters = new LinkedList<LinkReference>(addWritersIn);
		LinkedList<LinkReference> removeWriters = new LinkedList<LinkReference>(removeWritersIn);
		LinkedList<LinkReference> addManagers = new LinkedList<LinkReference>(addManagersIn);
		LinkedList<LinkReference> removeManagers = new LinkedList<LinkReference>(removeManagersIn);
		
		// Add then remove, so that removes override adds. Want to come up in the end with:
		// a) do we need a new node key
		// b) if not, who are the net new readers (requiring new node key blocks)?
		
		LinkedList<LinkReference> newReaders = new LinkedList<LinkReference>();
		boolean newNodeKeyRequired = false;
		boolean potentialNewReader = false;
		if (null != addManagers) {
			for (LinkReference manager : addManagers) {
				potentialNewReader = !_managers.contains(manager);
				// if it's already a manager, do nothing
				// Tricky -- if it's in the writers or readers list it will have a different label.
				// test if it's already a writer or a reader
				// if so, it's not a new reader
				// and remove it from those other roles
				// and add it to manager
				// otherwise, add to new readers list
			}
		}
		if (null != addWriters) {
			for (LinkReference writer : addWriters) {
				potentialNewReader = !_writers.contains(writer);
				// if it's already a writer, do nothing
				// Tricky -- if it's in the writers or readers list it will have a different label.
				// test if it's already a manager or a reader
				// if so, it's not a new reader
				// if it's already a manager, check if it's on the manager to be removed list
				// if it is, remove it as a manager and add it as a writer
				// if not, just skip
				// if it's already a reader, remove it as a reader
				// otherwise, add to new readers list
			}
		}
		if (null != addReaders) {
			for (LinkReference reader : addReaders) {
				potentialNewReader = !_readers.contains(reader);
				// if it is already a reader, do nothing
				// Tricky -- if it's in the writers or manager list it will have a different label.
				// test if it's already a writer or a manager
				// if so, it's not a new reader
				// if it's already a manager or writer, check if it's on the manager or writer to be removed list
				// if it is, remove it as a manager or writer and add it as a reader
				// if not, just skip -- already better than a reader
				// otherwise, add to new readers list
			}
		}
		// We've already handled the cases of changed access, rather than revoking
		// all read rights. Changed access (retained read access) will show up as
		// requests to remove with no matching data.
		// DKS TODO deal with different labels than incoming
		if (null != removeReaders) {
			for (LinkReference reader : removeReaders) {
				if (_readers.contains(reader)) {
					remove(reader);
					newNodeKeyRequired = true;
				}
			}
		}
		if (null != removeWriters) {
			for (LinkReference writer : removeWriters) {
				if (_writers.contains(writer)) {
					remove(writer);
					newNodeKeyRequired = true;
				}
			}
		}
		if (null != removeManagers) {
			for (LinkReference manager : removeManagers) {
				if (_managers.contains(manager)) {
					remove(manager);
					newNodeKeyRequired = true;
				}
			}
		}

		// If we need a new node key, we don't care who the new readers are.
		// If we don't need a new node key, we do. 
		// Want a dual return -- new node key required, 
		// no new node key required but new readers and here they are,
		// and the really unusual case -- no new node key required and no new readers (no change).
		if (newNodeKeyRequired) {
			return null;
		}
		return newReaders;
	}
	
	public boolean canRead(ContentName principal) {
		// deal with potentially different labels
		// TODO instead of has reader, want to check whether a principal can read (or w or manage which imply read)
		return _readers.contains(link);
	}
	
	protected void addLabeledLink(LinkReference link, String desiredLabel) {
		// assume that the reference has link's name and authentication information,
		// but possibly not the right label. Also assume that the link object might
		// be used in multiple places, and we can't modify it.
		if (((null == desiredLabel) && (null != link.targetLabel()))
			|| (!desiredLabel.equals(link.targetLabel()))) {
			link = new LinkReference(link.targetName(), desiredLabel, link.targetAuthenticator());
		}
		add(link);
	}

	@Override
	public void add(LinkReference link) {
		if (validLabel(link)) {
			super.add(link);
			index(link);
		}
		throw new IllegalArgumentException("Invalid label: " + link.targetLabel());
	}
	
	@Override
	public void add(ArrayList<LinkReference> contents) {
		for (LinkReference link : contents) {
			add(link); // break them out for validation and indexing
		}
	}
	
	@Override
	public LinkReference remove(int i) {
		LinkReference link = _contents.remove(i);
		deindex(link);
		return link;
	}
	
	@Override
	public boolean remove(LinkReference content) {
		if  (_contents.remove(content)) {
			deindex(content);
			return true;
		}
		return false;
	}
	
	@Override
	public boolean remove(Link content) {
		return remove(content.getReference());
	}
	
	@Override
	public void removeAll() {
		super.removeAll();
		clearIndices();
	}
	
	protected void clearIndices() {
		_readers.clear();
		_writers.clear();
		_managers.clear();
	}
	
	protected void index(LinkReference link) {
		if (LABEL_READER.equals(link.targetLabel())) {
			_readers.add(link);
		} else if (LABEL_WRITER.equals(link.targetLabel())) {
			_writers.add(link);
		} else if (LABEL_MANAGER.equals(link.targetLabel())) {
			_managers.add(link);
		} else {
			Library.logger().info("Unexpected: attempt to index ACL entry with unknown label: " + link.targetLabel());
		}
	}
	
	protected void deindex(LinkReference link) {
		if (LABEL_READER.equals(link.targetLabel())) {
			_readers.remove(link);
		} else if (LABEL_WRITER.equals(link.targetLabel())) {
			_writers.remove(link);
		} else if (LABEL_MANAGER.equals(link.targetLabel())) {
			_managers.remove(link);
		} else {
			Library.logger().info("Unexpected: attempt to index ACL entry with unknown label: " + link.targetLabel());
		}		
	}
}
