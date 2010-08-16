/*
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

package org.ccnx.ccn.profiles.security.access.group;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * This class represents an Access Control List (ACLs) for CCN content, for use with
 * the Group-based access control scheme (though it might be useful to other schemes as well).
 * 
 * It offers a limited degree of expressibility -- it can grant read, write, or manage
 * privileges to named users or groups (where users and groups are effectively
 * public keys stored in locations defined by the profile). Permissions are supersets
 * of one another -- writers can read, managers can read and write. Managers have the additional
 * capability to change rights -- to create and edit ACLs. An ACL applies to all the content
 * below it in the name tree until it is superseded by another ACL below it in that tree.  
 *
 */
public class ACL extends Collection {
	
	/** Readers can read content */
	public static final String LABEL_READER = "r";
	/** Writers can read and write (or edit) content */
	public static final String LABEL_WRITER = "rw";
	/** Managers can read and write content, and edit access rights to content */
	public static final String LABEL_MANAGER = "rw+";
	public static final String [] ROLE_LABELS = {LABEL_READER, LABEL_WRITER, LABEL_MANAGER};
	
	/**
	 * This class represents the operations that can be performed on an ACL,
	 * such as add or delete readers, writers or managers.
	 *
	 */
	public static class ACLOperation extends Link {
		
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
		
		// In case anyone tries to serialize. NOT IN SCHEMA. Not supposed to be serialized.
		@Override
		public long getElementLabel() { return -1; }
	}
	
	/**
	 * This class is for matching on unversioned link target name only,
	 * not label and potentially not signer if specified. Use a set class that can
	 * allow us to specify a comparator; use one that ignores labels and versions on names.
	 * 
	 */
	public static class SuperficialLinkComparator implements Comparator<Link> {

		/**
		 * Compare two links
		 * @param o1 first link
		 * @param o2 second link
		 * @return result of comparison
		 */
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

	/**
	 * ACL CCN objects; as it only makes sense right now to
	 * operate on ACLs in repositories, it writes all data to repositories..
	 *
	 */
	public static class ACLObject extends CCNEncodableObject<ACL> {

		/**
		 * Constructor
		 * @param name the object name
		 * @param data the ACL
		 * @param handle the CCN handle
		 * @throws ConfigurationException
		 * @throws IOException
		 */
		public ACLObject(ContentName name, ACL data, CCNHandle handle) throws IOException {
			super(ACL.class, true, name, data, SaveType.REPOSITORY, handle);
		}

		public ACLObject(ContentName name, ACL data, 
				PublisherPublicKeyDigest publisher, KeyLocator keyLocator,
				CCNHandle handle) throws IOException {
			super(ACL.class, true, name, data, SaveType.REPOSITORY, publisher, keyLocator,
					handle);
		}

		/**
		 * Read constructor -- opens existing object.
		 * @param name the object name
		 * @param handle the CCN handle
		 * @throws IOException
		 * @throws ContentDecodingException
		 */
		public ACLObject(ContentName name, CCNHandle handle) 
					throws ContentDecodingException, IOException {
			super(ACL.class, true, name, (PublisherPublicKeyDigest)null, handle);
			setSaveType(SaveType.REPOSITORY);
		}
		
		/**
		 * Read constructor
		 * @param name the object name
		 * @param publisher the required publisher
		 * @param handle the CCN handle
		 * @throws IOException
		 * @throws ContentDecodingException
		 */
		public ACLObject(ContentName name, PublisherPublicKeyDigest publisher,
						CCNHandle handle) throws ContentDecodingException, IOException {
			super(ACL.class, true, name, publisher, handle);
			setSaveType(SaveType.REPOSITORY);
		}
		
		public ACLObject(ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(ACL.class, true, firstBlock, handle);
			setSaveType(SaveType.REPOSITORY);
		}

		public ACLObject(ContentName name, PublisherPublicKeyDigest publisher,
				CCNFlowControl flowControl) throws ContentDecodingException,
				IOException {
			super(ACL.class, true, name, publisher, flowControl);
		}

		public ACLObject(ContentObject firstBlock, CCNFlowControl flowControl)
				throws ContentDecodingException, IOException {
			super(ACL.class, true, firstBlock, flowControl);
		}

		public ACLObject(ContentName name, ACL data, PublisherPublicKeyDigest publisher,
				KeyLocator keyLocator, CCNFlowControl flowControl)
				throws IOException {
			super(ACL.class, true, name, data, publisher, keyLocator, flowControl);
		}

		public ACL acl() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
	}

	public ACL() {
		super();
	}

	/**
	 * Constructor
	 * @param contents the contents of the ACL
	 */
	public ACL(ArrayList<Link> contents) {
		if (validate()) add(contents);
		else throw new IllegalArgumentException("Invalid contents for ACL."); 
	}
	
	/**
	 * Return whether an ACL element is valid
	 * @param lr the element
	 * @return
	 */
	public boolean validLabel(Link lr) {
		return LABEL_MANAGER.contains(lr.targetLabel());
	}
	
	/**
	 * Placeholder for public content. These will be represented by some
	 * form of marker entry, and need to be handled specially.
	 * @return
	 */
	public boolean publiclyReadable() { return false; }
	
	/**
	 * Placeholder for public content. These will be represented by some
	 * form of marker entry, and need to be handled specially.
	 * @return
	 */	
	public boolean publiclyWritable() { return false; }

	/**
	 * Return whether an ACL is valid
	 * @return
	 */
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
	
	/**
	 * Add a specified reader to the ACL.
	 * The method does nothing if the reader is already a reader, a writer or a manager.
	 * @param reader the reader
	 */
	public void addReader(Link reader) {
		// add the reader only if it's not already a reader, a writer or a manager.
		if ((! _readers.contains(reader)) &&
			(! _writers.contains(reader)) &&
			(! _managers.contains(reader))) {
			if (!LABEL_READER.equals(reader.targetLabel())) {
				reader = new Link(reader.targetName(), LABEL_READER, reader.targetAuthenticator());
			}
			super.add(reader);
			_readers.add(reader);
		}
	}
	
	/**
	 * Remove a specified reader from the ACL.
	 * @param reader the reader
	 */
	public boolean removeReader(Link reader) {
		if (!LABEL_READER.equals(reader.targetLabel())) {	
			reader = new Link(reader.targetName(), LABEL_READER, reader.targetAuthenticator());	
		}
		if (_readers.contains(reader)) {
			_contents.remove(reader);
			_readers.remove(reader);
			return true;
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "trying to remove a non-existent reader, ignoring this operation...");  
		}
		return false;
	}

	/**
	 * Add a specified writer to the ACL.
	 * The method does nothing if the writer is already a writer or a manager.
	 * If the writer is already a reader, it is deleted from _readers and added to _writers.
	 * @param writer the writer
	 */
	public void addWriter(Link writer) {
		// add the writer only if it's not already a writer or a manager.
		if ((! _writers.contains(writer)) && (! _managers.contains(writer))) {
			if (!LABEL_WRITER.equals(writer.targetLabel())) {
				writer = new Link(writer.targetName(), LABEL_WRITER, writer.targetAuthenticator());
			}
			// if the writer is already a reader, delete it from readers.
			if (_readers.contains(writer)) {
				// TODO: this will not work if link has different authenticator
				removeReader(writer);
			}
			// add the writer as a writer
			super.add(writer);
			_writers.add(writer);			
		}
	}
	
	/**
	 * Remove a specified writer from the ACL.
	 * @param writer the writer
	 */
	public boolean removeWriter(Link writer) {
		if (!LABEL_WRITER.equals(writer.targetLabel())) {	
			writer = new Link(writer.targetName(), LABEL_WRITER, writer.targetAuthenticator());	
		}
		if (_writers.contains(writer)) {
			_contents.remove(writer);
			_writers.remove(writer);
			return true;
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "trying to remove a non-existent writer, ignoring this operation...");  
		}
		return false;
	}
	
	/**
	 * Add a specified manager to the ACL
	 * This method does nothing if the manager is already a manager.
	 * If the manager is already a reader or a writer, it is removed from 
	 * _readers or _writers and added to _managers.
	 * @param manager the manager
	 */
	public void addManager(Link manager) {
		// add the manager only if it's not already a manager.
		if (! _managers.contains(manager)) {
			if (!LABEL_MANAGER.equals(manager.targetLabel())) {
				manager = new Link(manager.targetName(), LABEL_MANAGER, manager.targetAuthenticator());
			}
			// if the manager is already a reader, delete it from readers.
			if (_readers.contains(manager)) {
				// TODO: this will not work if link has different authenticator
				removeReader(manager);
			}
			// if the manager is already a writer, delete it from readers.
			else if (_writers.contains(manager)) {
				// TODO: this will not work if link has different authenticator
				removeWriter(manager);
			}
			// add the manager as a manager
			super.add(manager);
			_managers.add(manager);
		}
	}
	
	/**
	 * Remove a specified manager from the ACL.
	 * @param manager the manager
	 */
	public boolean removeManager(Link manager) {
		if (!LABEL_MANAGER.equals(manager.targetLabel())) {	
			manager = new Link(manager.targetName(), LABEL_MANAGER, manager.targetAuthenticator());	
		}
		if (_managers.contains(manager)) {
			_contents.remove(manager);
			_managers.remove(manager);
			return true;
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "trying to remove a non-existent manager, ignoring this operation...");  
		}
		return false;
	}
	
	/**
	 * Batch perform a set of ACL update Operations
	 * @param ACLUpdates: ordered set of ACL update operations
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
		
		for (ACLOperation op: ACLUpdates) {
			int levelOld = LEVEL_NONE;
			if (_readers.contains(op)) {
				levelOld = LEVEL_READ;
			} else if (_writers.contains(op)) {
				levelOld = LEVEL_WRITE;
			} else if (_managers.contains(op)){
				levelOld = LEVEL_MANAGE;
			}
			
			if (ACLOperation.LABEL_ADD_READER.equals(op.targetLabel())) {
				addReader(op);				
			} 
			else if (ACLOperation.LABEL_ADD_WRITER.equals(op.targetLabel())) {				
				addWriter(op);
			} 
			else if (ACLOperation.LABEL_ADD_MANAGER.equals(op.targetLabel())) {
				addManager(op);
			} 
			else if (ACLOperation.LABEL_DEL_READER.equals(op.targetLabel())) {
				removeReader(op);	
			} 
			else if (ACLOperation.LABEL_DEL_WRITER.equals(op.targetLabel())){
				removeWriter(op);
			} 
			else if (ACLOperation.LABEL_DEL_MANAGER.equals(op.targetLabel())) {
				removeManager(op);
			}
			
			if (!tm.containsKey(op)) {
				tm.put(op, levelOld);
			}
		}
		
		// a new node key is required if someone with LEVEL_READ or above
		// is down-graded to LEVEL_NONE
		boolean newKeyRequired = false;
		LinkedList<Link> newReaders = new LinkedList<Link>();
		
		Iterator<Link> it = tm.keySet().iterator();
		while (it.hasNext()) {
			Link p = it.next();
			int lvOld = tm.get(p);
			
			if (_readers.contains(p) || _writers.contains(p) || _managers.contains(p)) {
				if (lvOld == LEVEL_NONE) {
					newReaders.add(p);
				}				
			} else if (lvOld > LEVEL_NONE) {
				newKeyRequired = true;				
			}
		}
		
		if (newKeyRequired) {
			return null;
		}
		return newReaders;
		
	}

	@Override
	public void add(Link link) {
		String label = link.targetLabel();
		if (label.equals(LABEL_READER)) addReader(link);
		else if (label.equals(LABEL_WRITER)) addWriter(link);
		else if (label.equals(LABEL_MANAGER)) addManager(link);
		else throw new IllegalArgumentException("Invalid ACL label: " + link.targetLabel());
	}
	
	@Override
	public void add(ArrayList<Link> contents) {
		for (Link link : contents) {
			add(link); // break them out for validation and indexing
		}
	}
	
	@Override
	public Link remove(int i) {
		Link link = _contents.get(i);
		remove(link);
		return link;
	}
	
	@Override
	public boolean remove(Link content) {
		String label = content.targetLabel();
		if (label.equals(LABEL_READER)) return removeReader(content);
		else if (label.equals(LABEL_WRITER)) return removeWriter(content);
		else if (label.equals(LABEL_MANAGER)) return removeManager(content);
		return false;
	}
	
	@Override
	public void removeAll() {
		super.removeAll();
		_readers.clear();
		_writers.clear();
		_managers.clear();
	}
	
	@Override
	public long getElementLabel() { 
		return CCNProtocolDTags.ACL;
	}
}
