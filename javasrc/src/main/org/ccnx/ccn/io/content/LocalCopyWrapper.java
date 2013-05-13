/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.repo.RepositoryControl;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * Wrapper for a generic network object that requests a local repository to hold
 * a copy of the object whenever it is updated or saved.  A local repository
 * is one connected directly to the same ccnd as the application; it may have 
 * a distinguished role as the repository that is always available 
 * for local configuration data regardless of external connectivity. If there
 * is more than one repository that is local, the behavior is undefined.
 * 
 * To use this wrapper, create a network object instance as usual and then instantiate
 * the wrapper with the network object.  Use the wrapper whenever possible instead of the
 * underlying network object.  To access distinctive features of the particular network object 
 * subclass, call the object() method on the wrapper to get the underlying object.
 * 
 * @author jthornton
 *
 */
public class LocalCopyWrapper implements UpdateListener {

	final CCNNetworkObject<?> _netobj;
	final CCNHandle _handle;
	final ConcurrentHashMap<UpdateListener, UpdateListener> _updateListeners  = new ConcurrentHashMap<UpdateListener, UpdateListener>(1);

	public LocalCopyWrapper(CCNNetworkObject<?> obj) throws IOException {
		_netobj = obj;
		_handle = _netobj.getHandle();
		_netobj.addListener(this);
		// Check for data already available, before we had a chance to add listener
		if (_netobj.isSaved()) {
			localCopy();
		}
	}
	
	public CCNNetworkObject<?> object() {
		return _netobj;
	}
	
	protected void localCopy() {
		try {
			RepositoryControl.localRepoSync(_handle, _netobj);
		} catch (IOException e) {
			if (Log.isLoggable(Level.INFO)) {
				Log.info("Local repo sync failed for network object: " + e.getMessage());
			}
		}
	}

	public void addListener(UpdateListener listener) {
		_updateListeners.put(listener, listener);
	}
	
	public void removeListener(UpdateListener listener) {
		_updateListeners.remove(listener);
	}
	
	public void clearListeners() {
		_updateListeners.clear();
	}
	
	public boolean available() {
		return _netobj.available(); 
	}
	
	public boolean isSaved() throws IOException {
		return _netobj.isSaved();
	}
	
	public boolean hasError() {
		return _netobj.hasError();
	}
	
	public IOException getError() {
		return _netobj.getError();
	}

	public void clearError() {
		_netobj.clearError();
	}

	public boolean contentEquals(Object obj) {
		return _netobj.contentEquals(obj);
	}
	
	public byte [] getContentDigest() throws IOException {
		return _netobj.getContentDigest();
	}

	public void close() {
		_netobj.removeListener(this);
		_netobj.close();
	}
	
	public void disableFlowControl() {
		_netobj.disableFlowControl();
	}
	
	@Override
	public int hashCode() {
		return _netobj.hashCode();
	}
	
	public boolean equals(Object obj) {
		if( null == obj )
			return false;
		
		if (getClass() != obj.getClass()) {
			return false;
		}
		LocalCopyWrapper other = (LocalCopyWrapper)obj;
		
		return _netobj.equals(other._netobj);
	}
	
	public Long firstSegmentNumber() {
		return _netobj.firstSegmentNumber();
	}
	
	public ContentName getBaseName() {
		return _netobj.getBaseName();
	}
	
	public PublisherPublicKeyDigest getContentPublisher() throws IOException {
		return _netobj.getContentPublisher();
	}
	
	public LinkObject getDereferencedLink() { 
		return _netobj.getDereferencedLink(); 
	}
	
	public byte[] getFirstDigest() {	
		return _netobj.getFirstDigest();
	}
	
	public KeyLocator getPublisherKeyLocator() throws IOException  {
		return _netobj.getPublisherKeyLocator();
	}
	
	public CCNTime getVersion() throws IOException {
		return _netobj.getVersion();
	}
	
	public byte [] getVersionComponent() throws IOException {
		return _netobj.getVersionComponent();
	}
	
	public ContentName getVersionedName()  {
		return _netobj.getVersionedName();
	}
		
	public boolean isGone() {
		return _netobj.isGone();
	}
	
	public boolean save() throws ContentEncodingException, IOException {
		boolean result = _netobj.save();
		if (result) localCopy();
		return result;
	}
	
	public boolean save(CCNTime version) throws ContentEncodingException, IOException {
		boolean result = _netobj.save(version);
		if (result) localCopy();
		return result;
	}

	public boolean saveAsGone() throws ContentEncodingException, IOException {
		boolean result = _netobj.saveAsGone();
		if (result) localCopy();
		return result;
	}
	
	public SaveType saveType() { return _netobj.saveType(); }
	
	public void setOurPublisherInformation(PublisherPublicKeyDigest publisherIdentity, KeyLocator keyLocator) {
		_netobj.setOurPublisherInformation(publisherIdentity, keyLocator);
	}
	
	public boolean update() throws ContentDecodingException, IOException {
		return _netobj.update();
	}
	
	public boolean update(ContentName name, PublisherPublicKeyDigest publisher) throws ContentDecodingException, IOException {
		return _netobj.update(name, publisher);
	}
	
	public boolean update(long timeout) throws ContentDecodingException, IOException {
		return _netobj.update(timeout);
	}
	
	public void updateInBackground() throws IOException {
		_netobj.updateInBackground();
	}
	
	public void updateInBackground(boolean continuousUpdates) throws IOException {
		_netobj.updateInBackground(continuousUpdates);
	}
	
	public void updateInBackground(boolean continuousUpdates, UpdateListener listener) throws IOException {
		addListener(listener);
		_netobj.updateInBackground(continuousUpdates);
	}
	
	public void updateInBackground(ContentName latestVersionKnown, boolean continuousUpdates) throws IOException {
		_netobj.updateInBackground(latestVersionKnown, continuousUpdates);
	}
	
	public void updateInBackground(ContentName latestVersionKnown, boolean continuousUpdates, UpdateListener listener) throws IOException {
		addListener(listener);
		_netobj.updateInBackground(latestVersionKnown, continuousUpdates);
	}
	
	public void waitForData() {
		_netobj.waitForData();
	}
	
	public void waitForData(long timeout) {		
		_netobj.waitForData(timeout);
	}

	public void newVersionAvailable(CCNNetworkObject<?> newVersion, boolean wasSave) {
		// We probably want to make a local copy regardless, as the save might have been raw,
		// or not hit our local repository.
		localCopy();
		
		// any registered listeners
		// keySet() is weakly consistent and will reflect the state of the ConcurerntHashMap
		// at the time keySet is called.
		for (UpdateListener listener : _updateListeners.keySet()) {
			listener.newVersionAvailable(newVersion, wasSave);
		}
	}

	
}
