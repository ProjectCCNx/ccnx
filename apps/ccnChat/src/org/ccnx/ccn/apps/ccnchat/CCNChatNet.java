/*
 * A CCNx chat library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.apps.ccnchat;

import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Based on a client/server chat example in Robert Sedgewick's Algorithms
 * in Java.
 * 
 * This is the base class that does all the CCN networking. It is
 * instantiated by the UI class and then called in a blocking listen() call.
 * 
 * The UI uses sendMessage() to send a message to the network and implements
 * the CCNChatCallback interface to receive a message from the network.
 * 
 * The UI should call shutdown() on close.
 */
public final class CCNChatNet {

	/**
	 * The callback from the network to the UI to display
	 * a message from the network.
	 */
	public interface CCNChatCallback {
		/**
		 * Implemented by concrete UI class
		 * Receive a message from the network
		 * @param message
		 */
		public void recvMessage(String message);
	}
	
    // ==================================================================
    // The public API to the UI class

	/**
	 * Construct a CCNChat 
	 * @param callback The callback to the UI to receive a message
	 * @param namespace The namespace of the Chat channel
	 * @throws MalformedContentNameStringException
	 */
    public CCNChatNet(CCNChatCallback callback, String namespace) throws MalformedContentNameStringException {
    	_callback = callback;
    	_namespace = ContentName.fromURI(namespace);  	
    	_namespaceStr = namespace;
       	_friendlyNameToDigestHash = new HashMap<PublisherPublicKeyDigest, String>();
    }

	
	/**
	 * Send a message out to the network.
	 * @param message The text string to send
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 */
	public synchronized void sendMessage(String message) throws ContentEncodingException, IOException {
		_writeString.save(message);
	}
	
    /**
     * Turn off everything.
     * @throws IOException 
     */
	public void shutdown() throws IOException {
		_finished = true;
		if (null != _readString) {
			_readString.cancelInterest();
			showMessage(SYSTEM, now(), "Shutting down " + _namespace + "...");
		}
	}
	
	/**
	 * Some UIs (like the text one) want to turn off all logging
	 */
	public void setLogging(Level level) {
		Log.setLevel(Log.FAC_ALL, level);
	}
	
	
	/**
	 * This actual CCN loop to send/receive messages.  Called by
	 * the UI class.  This method blocks!  If the UI is not multi-threaded,
	 * you should start a thread to hold listen().
	 * 
	 * When shutdown() is called, listen() will exit.
	 * 
	 * @throws ConfigurationException
	 * @throws IOException
	 * @throws MalformedContentNameStringException
	 */
	public void listen() throws ConfigurationException, IOException, MalformedContentNameStringException {
		
		//Also publish your keys under the chat "channel name" namespace
		if (_namespace.toString().startsWith("ccnx:/")) {
			UserConfiguration.setDefaultNamespacePrefix(_namespace.toString().substring(5));		
		} else {
			UserConfiguration.setDefaultNamespacePrefix(_namespace.toString());
		}

		CCNHandle tempReadHandle = CCNHandle.getHandle();

		// Writing must be on a different handle, to enable us to read back text we have
		// written when nobody else is reading.
		CCNHandle tempWriteHandle = CCNHandle.open();

		_readString = new CCNStringObject(_namespace, (String)null, SaveType.RAW, tempReadHandle);
		_readString.updateInBackground(true); 
		
		String introduction = UserConfiguration.userName() + " has entered " + _namespace;
		_writeString = new CCNStringObject(_namespace, introduction, SaveType.RAW, tempWriteHandle);
		_writeString.save();

		// Publish the user's friendly name under a new ContentName
		String friendlyNameNamespaceStr = _namespaceStr + "/members/";  
		_friendlyNameNamespace = KeyProfile.keyName(ContentName.fromURI(friendlyNameNamespaceStr), _writeString.getContentPublisher());
		Log.info("**** Friendly Namespace is " + _friendlyNameNamespace);
		
		//read the string here.....	
		_readNameString = new CCNStringObject(_friendlyNameNamespace, (String)null, SaveType.RAW, tempReadHandle);
		_readNameString.updateInBackground(true);
		
		String publishedNameStr = UserConfiguration.userName();
		Log.info("*****I am adding my own friendly name as " + publishedNameStr);
		_writeNameString = new CCNStringObject(_friendlyNameNamespace, publishedNameStr, SaveType.RAW, tempWriteHandle);
		_writeNameString.save();
		
		
		try {
			addNameToHash(_writeNameString.getContentPublisher(), _writeNameString.string());
		}
		catch (IOException e) {
			System.err.println("Unable to read from " + _writeNameString + "for writing to hashMap");
			e.printStackTrace();
		}
		
		
		// Need to do synchronization for updates that come in while we're processing last one.
		
		while (!_finished) {
			try {
				synchronized(_readString) {
					_readString.wait(CYCLE_TIME);
				}
			} catch (InterruptedException e) {
			}
			
			if (_readString.isSaved()) {
				Timestamp thisUpdate = _readString.getVersion();
				if ((null == _lastUpdate) || thisUpdate.after(_lastUpdate)) {
					Log.info("Got an update: " + _readString.getVersion());
					_lastUpdate = thisUpdate;	
					
					//lookup friendly name to display for this user.....
					String userFriendlyName = getFriendlyName(_readString.getContentPublisher());
								
						if (userFriendlyName.equals("")) {
						
							// Its not in the hashMap.. So, try and read the user's friendly name from the ContentName and then add it to the hashMap....
							String userNameStr = _namespaceStr + "/members/";  
							_friendlyNameNamespace = KeyProfile.keyName(ContentName.fromURI(userNameStr), _readString.getContentPublisher());
													
							try {
								_readNameString = new CCNStringObject(_friendlyNameNamespace, (String)null, SaveType.RAW, tempReadHandle);
							} catch (Exception e) {
						
							}
						
							_readNameString.update(WAIT_TIME_FOR_FRIENDLY_NAME); // for now, I am just waiting for 2.5 secs.. Otherwise, I might have to update in background and have a callback
												
							if (_readNameString.available()) {
							 
								if (! _readString.getContentPublisher().equals(_readNameString.getContentPublisher())) {
									showMessage(_readString.getContentPublisher(), _readString.getPublisherKeyLocator(), thisUpdate, _readString.string());						 
								} else { 											 
									addNameToHash(_readNameString.getContentPublisher(), _readNameString.string());
									showMessage(_readNameString.string(), thisUpdate, _readString.string());
								}
							} else {
								showMessage(_readString.getContentPublisher(), _readString.getPublisherKeyLocator(), thisUpdate, _readString.string());
							}
						 
						} else {
							showMessage(userFriendlyName, thisUpdate, _readString.string());	
						}				
				}	
			}
		}
	}

    // ==================================================================
    // Internal methods
    
	private final CCNChatCallback _callback;
	private final ContentName _namespace;
    private final String _namespaceStr;

	private Timestamp _lastUpdate;
	private boolean _finished = false;
	
	private static final long CYCLE_TIME = 1000;
	private static final String SYSTEM = "System";
	private static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm");

	private static final int WAIT_TIME_FOR_FRIENDLY_NAME = 2500;
	
	// Separate read and write libraries so we will read our own updates,
	// and don't have to treat our inputs differently than others.
	private CCNStringObject _readString;
	private CCNStringObject _writeString;
	
	// We use these for storing the friendly names of users. 
	private CCNStringObject _readNameString;
	private CCNStringObject _writeNameString;
	
	// this is where we store the friendly name of the user
    private HashMap<PublisherPublicKeyDigest, String> _friendlyNameToDigestHash;
    private ContentName _friendlyNameNamespace;
    
	private String getFriendlyName(PublisherPublicKeyDigest digest) {
		
		if (_friendlyNameToDigestHash.containsKey(digest)) {
			return _friendlyNameToDigestHash.get(digest);
		} else {
			Log.info("We DON'T have an entry in our hash for this " + digest);
		return "";
		}
	}
	
	private void addNameToHash(PublisherPublicKeyDigest digest, String friendlyName) {
		_friendlyNameToDigestHash.put(digest,friendlyName);	
	}
	
	/**
	 * Add a message to the output.
	 * @param message
	 */
	private void showMessage(String sender, Timestamp time, String message) {
		_callback.recvMessage("[" + sender + " " + DATE_FORMAT.format(time) + "]: " + message + "\n");
 	}
	
	private void showMessage(PublisherPublicKeyDigest publisher, KeyLocator keyLocator, Timestamp time, String message) {
		// Start with key fingerprints. Move up to user names.
		showMessage(publisher.shortFingerprint().substring(0, 8), time, message);
	}
    
	private static Timestamp now() { return new Timestamp(System.currentTimeMillis()); }
}
