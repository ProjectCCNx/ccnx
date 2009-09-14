/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.profiles.access;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;




public class ACL extends Collection {
	
	public static final String LABEL_READER = "r";
	public static final String LABEL_WRITER = "rw";
	public static final String LABEL_MANAGER = "rw+";
	public static final String [] ROLE_LABELS = {LABEL_READER, LABEL_WRITER, LABEL_MANAGER};
	
	public static class ACLOperation extends Link {
		public static final String ACL_OPERATION_ELEMENT = "ACLOperation";
		
		public static final String LABEL_ADD_READER = "+r";
		public static final String LABEL_ADD_WRITER = "+rw";
		public static final String LABEL_ADD_MANAGER = "+rw+";
		public static final String LABEL_DEL_READER = "-r";
		public static final String LABEL_DEL_WRITER = "-rw";
		public static final String LABEL_DEL_MANAGER = "-rw+";
		
		public ACLOperation(String label, Link linkRef){
			super(linkRef.targetName(), label, linkRef.targetAuthenticator());
		}
		
		public static ACLOperation addReaderOperation(Link linkRef){
			return new ACLOperation(LABEL_ADD_READER, linkRef);
		}
		public static ACLOperation removeReaderOperation(Link linkRef){
			return new ACLOperation(LABEL_DEL_READER, linkRef);
		}
		public static ACLOperation addWriterOperation(Link linkRef){
			return new ACLOperation(LABEL_ADD_WRITER, linkRef);
		}
		public static ACLOperation removeWriterOperation(Link linkRef){
			return new ACLOperation(LABEL_DEL_WRITER, linkRef);
		}
		public static ACLOperation addManagerOperation(Link linkRef){
			return new ACLOperation(LABEL_ADD_MANAGER, linkRef);
		}
		public static ACLOperation removeManagerOperation(Link linkRef){
			return new ACLOperation(LABEL_DEL_MANAGER, linkRef);
		}
		
		// In case anyone tries to serialize. NOT IN SCHEMA
		@Override
		public String getElementLabel() { return ACL_OPERATION_ELEMENT; }
	}
	
	// maintain a set of index structures. want to match on unversioned link target name only,
	// not label and potentially not signer if specified. Use a set class that can
	// allow us to specify a comparator; use one that ignores labels and versions on names.
	public static class SuperficialLinkComparator implements Comparator<Link> {

		public int compare(Link o1, Link o2) {
			int result = 0;
			if (null != o1) {
				if (null == o2) {
					return 1;
				}
			} else if (null != o2) {
				return -1;
			} else {
				return 0;
			}
			// Want an ordering on un-versioned names, not a comparison of versions.
			result = VersioningProfile.cutTerminalVersion(o1.targetName()).first().compareTo(VersioningProfile.cutTerminalVersion(o2.targetName()).first());
			if (result != 0)
				return result;
			if (null != o1.targetAuthenticator()) {
				if (null != o2.targetAuthenticator()) {
					return o1.targetAuthenticator().compareTo(o2.targetAuthenticator());
				} else {
					return 1;
				}
			} else if (null != o2.targetAuthenticator()) {
				return -1;
			} else {
				return 0;
			}
		}
	}
	
	static SuperficialLinkComparator _comparator = new SuperficialLinkComparator();
	
	protected TreeSet<Link> _readers = new TreeSet<Link>(_comparator);
	protected TreeSet<Link> _writers = new TreeSet<Link>(_comparator);
	protected TreeSet<Link> _managers = new TreeSet<Link>(_comparator);

	public static class ACLObject extends CCNEncodableObject<ACL> {

		public ACLObject(ContentName name, ACL data, CCNHandle handle) throws ConfigurationException, IOException {
			super(ACL.class, name, data, handle);
		}
		
		public ACLObject(ContentName name, PublisherPublicKeyDigest publisher,
				CCNHandle handle) throws ConfigurationException, IOException, XMLStreamException {
			super(ACL.class, name, publisher, handle);
		}
		
		/**
		 * Read constructor -- opens existing object.
		 * @param type
		 * @param name
		 * @param handle
		 * @throws XMLStreamException
		 * @throws IOException
		 * @throws ClassNotFoundException 
		 */
		public ACLObject(ContentName name, 
				CCNHandle handle) throws ConfigurationException, IOException, XMLStreamException {
			super(ACL.class, name, (PublisherPublicKeyDigest)null, handle);
		}
		
		public ACLObject(ContentObject firstBlock,
				CCNHandle handle) throws ConfigurationException, IOException, XMLStreamException {
			super(ACL.class, firstBlock, handle);
		}
		
		public ACL acl() throws ContentNotReadyException, ContentGoneException { return data(); }
	}

	public ACL() {
		super();
	}

	public ACL(ArrayList<Link> contents) {
		super(contents);
		if (!validate()) {
			throw new IllegalArgumentException("Invalid contents for ACL.");
		}
	}
		
	public boolean validLabel(Link lr) {
		return LABEL_MANAGER.contains(lr.targetLabel());
	}
	
	/**
	 * DKS -- add placeholder for public content. These will be represented by some
	 * form of marker entry, and need to be handled specially.
	 */
	public boolean publiclyReadable() { return false; }
	public boolean publiclyWritable() { return false; }

	@Override
	public boolean validate() {
		if (!super.validate())
			return false;
		for (Link lr : contents()) {
			if ((null == lr.targetLabel()) || (!validLabel(lr))) {
				return false;
			}
		}
		return true;
	}
	
	public void addReader(Link reader) {
		addLabeledLink(reader, LABEL_READER);
	}
	
	public void addWriter(Link writer) {
		addLabeledLink(writer, LABEL_WRITER);
	}
	
	public void addManager(Link manager) {
		addLabeledLink(manager, LABEL_MANAGER);
	}
	
	/**
	 * Batch perform a set of ACL update Operations
	 * @param ACLUpdates: ordered set of ACL update operations
	 * 
	 * @return We return a LinkedList<Link> of the principals newly granted read
	 *   access on this ACL. If no individuals are granted read access, we return a 0-length
	 *   LinkedList. If any individuals are completely removed, requiring the caller to generate
	 *   a new node key or otherwise update cryptographic data, we return null.
	 *   (We could return the removed principals, but it's a little weird -- some people are
	 *   removed from a role and added to others. For now, we just return the thing we need
	 *   for our current implementation, which is whether anyone lost read access entirely.)
	 */
	public LinkedList<Link> update(ArrayList<ACLOperation> ACLUpdates){
		
		final int LEVEL_NONE = 0;
		final int LEVEL_READ = 1;
		final int LEVEL_WRITE = 2;
		final int LEVEL_MANAGE = 3;
		
		//for principals that are affected, 
		//tm records the previous privileges of those principals
		TreeMap<Link, Integer> tm = new TreeMap<Link, Integer>(_comparator);
		
		for (ACLOperation op: ACLUpdates){
			int levelOld = LEVEL_NONE;
			if(_readers.contains(op)){
				levelOld = LEVEL_READ;
			}else if(_writers.contains(op)){
				levelOld = LEVEL_WRITE;
			}else if(_managers.contains(op)){
				levelOld = LEVEL_MANAGE;
			}
			
			
			if(ACLOperation.LABEL_ADD_READER.equals(op.targetLabel())){
				if(levelOld > LEVEL_NONE){
					continue;
				}
				
				addReader(op);
				
			}else if(ACLOperation.LABEL_ADD_WRITER.equals(op.targetLabel())) {				
				if(levelOld > LEVEL_WRITE){
					continue;
				}
				
				if(levelOld == LEVEL_READ){
					removeLabeledLink(op, LABEL_READER);
				}
				
				addWriter(op);
			}else if (ACLOperation.LABEL_ADD_MANAGER.equals(op.targetLabel())) {
				if(levelOld == LEVEL_MANAGE){
					continue;
				}

				if(levelOld == LEVEL_READ){
					removeLabeledLink(op, LABEL_READER);
				}else if(levelOld == LEVEL_WRITE){					
					removeLabeledLink(op, LABEL_WRITER);
				}
				
				addManager(op);
			}else if (ACLOperation.LABEL_DEL_READER.equals(op.targetLabel())){
				if(levelOld != LEVEL_READ){
					Log.info("trying to remove a non-existent reader, ignoring this operation..."); 
					continue;
				}

				removeLabeledLink(op, LABEL_READER);	
			}else if (ACLOperation.LABEL_DEL_WRITER.equals(op.targetLabel())){
				if(levelOld != LEVEL_WRITE){ 
					Log.info("trying to remove a non-existent writer, ignoring this operation...");
					continue;
				}

				removeLabeledLink(op, LABEL_WRITER);
			}else if (ACLOperation.LABEL_DEL_MANAGER.equals(op.targetLabel())){
				if(levelOld != LEVEL_MANAGE){
					Log.info("trying to remove a non-existent manager, ignoring this operation...");
					continue;
				}

				removeLabeledLink(op, LABEL_MANAGER);
			}
			
			if(!tm.containsKey(op)){
				tm.put(op, levelOld);
			}
		}
		
		// a new node key is required if someone with LEVEL_READ or above
		// is down-graded to LEVEL_NONE
		boolean newKeyRequired = false;
		LinkedList<Link> newReaders = new LinkedList<Link>();
		
		Iterator<Link> it = tm.keySet().iterator();
		while(it.hasNext()){
			Link p = it.next();
			int lvOld = tm.get(p);
			
			if (_readers.contains(p) || _writers.contains(p) || _managers.contains(p)){
				if(lvOld == LEVEL_NONE){
					newReaders.add(p);
				}				
			}else if (lvOld > LEVEL_NONE){
				newKeyRequired = true;				
			}
		}
		
		if (newKeyRequired) {
			return null;
		}
		return newReaders;
		
	}
		
	protected void addLabeledLink(Link link, String desiredLabel) {
		// assume that the reference has link's name and authentication information,
		// but possibly not the right label. Also assume that the link object might
		// be used in multiple places, and we can't modify it.
		if (((null == desiredLabel) && (null != link.targetLabel()))
			|| (!desiredLabel.equals(link.targetLabel()))) {
			link = new Link(link.targetName(), desiredLabel, link.targetAuthenticator());
		}
		add(link);
	}

	protected void removeLabeledLink(Link link, String desiredLabel) {
		// assume that the reference has link's name and authentication information,
		// but possibly not the right label. Also assume that the link object might
		// be used in multiple places, and we can't modify it.
		if (((null == desiredLabel) && (null != link.targetLabel()))
			|| (!desiredLabel.equals(link.targetLabel()))) {
			link = new Link(link.targetName(), desiredLabel, link.targetAuthenticator());
		}
		remove(link);
	}

	@Override
	public void add(Link link) {
		if (validLabel(link)) {
			super.add(link);
			index(link);
		}
		else throw new IllegalArgumentException("Invalid label: " + link.targetLabel());
	}
	
	@Override
	public void add(ArrayList<Link> contents) {
		for (Link link : contents) {
			add(link); // break them out for validation and indexing
		}
	}
	
	@Override
	public Link remove(int i) {
		Link link = _contents.remove(i);
		deindex(link);
		return link;
	}
	
	@Override
	public boolean remove(Link content) {
		if  (_contents.remove(content)) {
			deindex(content);
			return true;
		}
		return false;
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
	
	protected void index(Link link) {
		if (LABEL_READER.equals(link.targetLabel())) {
			_readers.add(link);
		} else if (LABEL_WRITER.equals(link.targetLabel())) {
			_writers.add(link);
		} else if (LABEL_MANAGER.equals(link.targetLabel())) {
			_managers.add(link);
		} else {
			Log.info("Unexpected: attempt to index ACL entry with unknown label: " + link.targetLabel());
		}
	}
	
	protected void deindex(String label, Link link) {
		if (LABEL_READER.equals(label)) {
			_readers.remove(link);
		} else if (LABEL_WRITER.equals(label)) {
			_writers.remove(link);
		} else if (LABEL_MANAGER.equals(label)) {
			_managers.remove(link);
		} else {
			Log.info("Unexpected: attempt to index ACL entry with unknown label: " + label);
		}				
	}
	
	protected void deindex(Link link) {
		deindex(link.targetLabel(), link);
	}
}
