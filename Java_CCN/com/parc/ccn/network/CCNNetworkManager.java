package com.parc.ccn.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;

import javax.xml.stream.XMLStreamException;


import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.WirePacket;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.util.InterestTable;
import com.parc.ccn.data.util.InterestTable.Entry;
import com.parc.ccn.security.keys.KeyManager;
import com.sun.org.apache.xpath.internal.operations.Equals;

/**
 * Interface to the lowest CCN levels. Eventually will be
 * just the interface to ccnd, but right now also talks
 * directly to the repository, as we don't know how to
 * talk to the repository via ccnd yet. Most clients,
 * and the CCN library, will use this as the "CCN".
 * 
 * TODO DKS sort out interfaces -- CCNRepository is right now
 * the slight extension of CCNBase to add enumeration functionality.
 * Once we've figured out where that is to go, we can change what
 * this implements.
 * @author smetters
 *
 */
public class CCNNetworkManager implements CCNRepository, Runnable {
	
	public static final int DEFAULT_AGENT_PORT = 4485;
	public static final int MAX_PAYLOAD = 8800; // number of bytes in UDP payload
	public static final int PERIOD = 100; // period for re-express interests in ms.
	public static final String KEEPALIVE_NAME = "/HereIAm";
	
	/**
	 * Static singleton.
	 */
	protected static CCNNetworkManager _networkManager = null;
	
	protected Thread _thread = null;
	protected DatagramSocket _socket = null;
	protected boolean _error = true; // Marks error state of socket
	protected boolean _run = true;
	protected KeyManager _keyManager;
	protected ContentObject _keepalive; 
	// Maps of interests: users must synchronize on collection
	protected InterestTable<InterestLabel> _othersInterests = new InterestTable<InterestLabel>();
	protected InterestTable<InterestRegistration> _myInterests = new InterestTable<InterestRegistration>();
	protected InterestTable<CCNFilterListener> _myFilters = new InterestTable<CCNFilterListener>();
	// Map ContentName -> Object to notify() of interest
	protected Map<CompleteName, Semaphore> _writers = Collections.synchronizedMap(new HashMap<CompleteName, Semaphore>());
		
	/**
	 * Local label for an Interest heard from the network that may be consumed
	 * by data from this application in the future. 
	 * @author jthornto
	 *
	 */
	protected class InterestLabel {
		public final Date recvd;
		public InterestLabel() {
			recvd = new Date();
		}
	}
	
	/**
	 * Record of Interest from this application that may be satisfied 
	 * by data from the network in the future.
	 * @field interest Interest itself
	 * @field listener Listener to be notified of data matching the Interest.  The
	 * listener must be set (non-null) for cases of standing Interest that holds 
	 * until canceled by the application.  The listener should be null when a 
	 * thread is blocked waiting for data, in which case the thread will be 
	 * waiting on this object (InterestRegistration).
	 * @field data Holds data responsive to the interest for a waiting thread
	 * @author jthornto
	 *
	 */
	protected class InterestRegistration {
		public final Interest interest;
		public final CCNInterestListener listener;
		public ContentObject data = null;
		public Semaphore sema = new Semaphore(0);
		public InterestRegistration(Interest i, CCNInterestListener l) {
			interest = i; listener = l;
		}
		// Equality based on listener if present, so multiple objects can 
		// have the same interest registered without colliding
		public boolean equals(Object obj) {
			if (obj instanceof InterestRegistration) {
				InterestRegistration other = (InterestRegistration)obj;
				if (null == this.listener && null == other.listener){
					return super.equals(obj);
				} else if (null != this.listener && null != other.listener) {
					return this.listener.equals(other.listener);
				}
			}
			return false;
		}
		public int hashCode() {
			if (null != this.listener) {
				return this.listener.hashCode();
			} else {
				return super.hashCode();
			}
		}
	}
	
	/**
	 * Record of a filter describing portion of namespace for which this 
	 * application can respond to interests.
	 * @author jthornto
	 *
	 */
	protected class Filter {
		public ContentName name;
		public CCNFilterListener listener;
	}
	
	public static CCNNetworkManager getNetworkManager() { 
		if (null != _networkManager) 
			return _networkManager;
		try {
		return createNetworkManager();
		} catch (IOException io) {
			throw new RuntimeException(io);
		}
	}
	
	protected static synchronized CCNNetworkManager 
				createNetworkManager() throws IOException {
		if (null == _networkManager) {
			_networkManager = new CCNNetworkManager();
		}
		return _networkManager;
	}
	
	protected CCNNetworkManager() throws IOException {
		_socket = new DatagramSocket(); // binds ephemeral UDP port
		_socket.setSoTimeout(DEFAULT_AGENT_PORT);
		_thread = new Thread(this);
		_thread.run();
	}
	
	/** Create a single special piece of content that we can send 
	 *  to the network to make the local agent aware of our presence 
	 *  and the ephemeral UDP port to which we are listening.
	 *  This method is an experiment that may never be used.
	 * @throws IOException
	 */
	private void createKeepalive() throws IOException {
		try {
			// name
			ContentName keepname = new ContentName(KEEPALIVE_NAME);
			// contents = current date value
			ByteBuffer bb = ByteBuffer.allocate(Long.SIZE/Byte.SIZE);
			bb.putLong(new Date().getTime());
			byte[] contents = bb.array();
			// security bits
			PrivateKey signingKey = _keyManager.getDefaultSigningKey();
			KeyLocator locator = _keyManager.getKeyLocator(signingKey);
			PublisherKeyID publisher =  _keyManager.getDefaultKeyID();
			// unique name		
				CompleteName uniqueName =
					CompleteName.generateAuthenticatedName(
							keepname, publisher, ContentAuthenticator.now(),
							ContentAuthenticator.ContentType.LEAF, locator, contents, false, signingKey);
			_keepalive = new ContentObject(uniqueName.name(), uniqueName.authenticator(), uniqueName.signature(), contents);
		} catch (InvalidKeyException e) {
			throw new IOException("CCNNetworkManager: bad default signing key: " + e.getMessage());
		} catch (MalformedContentNameStringException e) {
			throw new RuntimeException("CCNNetworkManager internal fault: malformed keep alive name");
		} catch (SignatureException e) {
			throw new IOException("CCNNetworkManager: signature failure: " + e.getMessage());
		} catch (NullPointerException e) {
			throw new RuntimeException("CCNNetworkManager: publisher;/keys not initialized properly");
		}
	}
	
	/**
	 * Puts we put only to our local repository. Right now we deal with
	 * a repository manager, as we need a persistence protocol.
	 * TODO persistence protocol
	 */
	public CompleteName put(ContentName name, ContentAuthenticator authenticator, 
							byte [] signature, byte[] content) throws IOException, InterruptedException {
		// Is there a matching interest to consume?
		CompleteName complete = new CompleteName(name, authenticator, signature);
		Semaphore waitobj = null;
		while (true) {
			synchronized(_othersInterests) {
				if (null != _othersInterests.removeMatch(complete)) {
					if (null != waitobj) {
						// I was waiting, but no longer, I have interest to consume
						_writers.remove(complete);
					}
					ContentObject co = new ContentObject(name, authenticator, signature, content); 
					write(co);
					return complete;
				}
			}
			// If we got here then there is no Interest for this data yet
			// We can block waiting for one to show up
			if (null == waitobj) {
				waitobj = new Semaphore(0);
			
				// Assume there is not one in map already since CompleteName unique
				_writers.put(complete, waitobj);
			}
			waitobj.acquire(); // currently no timeouts
		}
		
		//return CCNRepositoryManager.getRepositoryManager().put(name, authenticator, signature, content);
	}

	protected void write(ContentObject data) throws IOException {
		WirePacket packet = new WirePacket(data);
		writeInner(packet);
	}

	protected void write(Interest interest) throws IOException {
		WirePacket packet = new WirePacket(interest);
		writeInner(packet);
	}

	private void writeInner(WirePacket packet) throws IOException {
		try {
			byte[] bytes = packet.encode();
			DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
			_socket.send(datagram);
		} catch (XMLStreamException xmlex) {
			throw new IOException(xmlex.getMessage());
		} catch (IOException io) {
			_error = true;
			throw io;
		}
	}

	/**
	 * Gets we also currently forward to the repository.
	 */
	public ArrayList<ContentObject> get(ContentName name, 
									    ContentAuthenticator authenticator,
									    boolean isRecursive) throws IOException, InterruptedException {
		Interest interest = new Interest(name);
		InterestRegistration reg = registerInterest(interest, null);
		write(interest);
		// Await data to consume the interest just sent out
		reg.sema.acquire(); // currently no timeouts
		ArrayList<ContentObject> results = new ArrayList<ContentObject>(1);
		results.add(reg.data);
		unregisterInterest(interest, null);
		return results;
		//return CCNRepositoryManager.getRepositoryManager().get(name, authenticator, isRecursive);
	}


	/**
	 * We express interests to both the repository and the ccnd. Eventually,
	 * we won't express interests to the repository anymore; we do this currently
	 * so that we get notified about what other local applications put into
	 * the repository (which don't go through the ccnd).
	 * The repository will handle notifying the listener for hits it sees;
	 * we need to notify the listener for hits coming across the network.
	 */
	public void expressInterest(
			Interest interest,
			CCNInterestListener callbackListener) throws IOException {
		if (null == callbackListener) {
			throw new NullPointerException("expressInterest: callbackListener cannot be null");
		}		
	
		// TODO: remove direct connection to repository
		CCNRepositoryManager.getRepositoryManager().expressInterest(interest, callbackListener);

		// Register the interest for repeated presentation to the network.
		registerInterest(interest, callbackListener);
	}
	
	/**
	 * Cancel this query with all the repositories we sent
	 * it to.
	 */
	public void cancelInterest(Interest interest, CCNInterestListener callbackListener) throws IOException {
		if (null == callbackListener) {
			throw new NullPointerException("cancelInterest: callbackListener cannot be null");
		}

		// TODO: remove direct connection to repository
		CCNRepositoryManager.getRepositoryManager().cancelInterest(interest, callbackListener);
	
		// Remove interest from repeated presentation to the network.
		unregisterInterest(interest, callbackListener);
	}

	public ArrayList<CompleteName> enumerate(Interest interest) throws IOException {
		ArrayList<CompleteName> results = 
			CCNRepositoryManager.getRepositoryManager().enumerate(interest);
		return results;
	}

	public ArrayList<CompleteName> getChildren(CompleteName name) throws IOException {
		ArrayList<CompleteName> results = 
			CCNRepositoryManager.getRepositoryManager().getChildren(name);
		return results;
	}
	
	/**
	 * Pass things on to the network stack.
	 */
	InterestRegistration registerInterest(Interest interest, CCNInterestListener callbackListener) {
		InterestRegistration reg = new InterestRegistration(interest, callbackListener);
		synchronized (_myInterests) {
			_myInterests.add(interest, reg);
		}
		return reg;
	}
	
	void unregisterInterest(Interest interest, CCNInterestListener callbackListener) {
		InterestRegistration reg = new InterestRegistration(interest, callbackListener);
		synchronized (_myInterests) {
			_myInterests.remove(interest, reg);
		}
	}

	// Thread method: this thread will handle reading datagrams and 
	// the periodic re-expressing of standing interests
	public void run() {
		byte[] inbuffer = new byte[MAX_PAYLOAD];
		DatagramPacket datagram = new DatagramPacket(inbuffer, MAX_PAYLOAD);
		WirePacket packet = new WirePacket();
		Date lastsweep = new Date(); 
		while (_run) {
			try {
				
				//--------------------------------- Error Recovery
				if (_error) {
					// Sleep to rate-limit recovery under error conditions
					try { Thread.sleep(PERIOD); } catch (InterruptedException e) {}
					// Try to re-establish normal contact with the agent
					_socket.disconnect(); // no-op if not connected
					_socket.connect(InetAddress.getByName("localhost"), DEFAULT_AGENT_PORT);
					// Send 0-length packet to alert agent to our presence
					_socket.send(new DatagramPacket(new byte[0], 0));
					//write(_keepalive);
					_error = false;
				}
				//--------------------------------- Read and decode
				_socket.receive(datagram);
				packet.decode(datagram.getData());
				
				//--------------------------------- Process interests (if any)
				for (Interest interest : packet.interests()) {
					// Record this known interest
					synchronized (_othersInterests) {
						_othersInterests.add(interest, new InterestLabel());
					}
					// Now notify any pending writers with data to consume this interest
					// if multiple, there's a race.  We can't notify only the first
					// because that writer might timeout and so never consume the 
					// interest
					synchronized (_writers) {
						for (CompleteName n : _writers.keySet()) {
							if (interest.matches(n)) {
								// There must be one since we're synchronized
								// there should be max 1 because CompleteName unique
								_writers.get(n).release();
							}
						}
					}
					// Call any listeners with matching filters
					List<CCNFilterListener> toCallback;
					synchronized (_myFilters) {
						// Save listeners, don't call here while holding lock
						toCallback = _myFilters.getValues(interest.name());
					}
					// Now perform the callbacks
					if (toCallback.size() > 0) {
						ArrayList<Interest> results = new ArrayList<Interest>(1);
						results.add(interest);
						for (CCNFilterListener listener : toCallback) {
							listener.handleResults(results);
						}
					}
				} // for interests
				
				//--------------------------------- Process data (if any) 
				for (ContentObject data : packet.data()) {
					// Deliver this data if it satisfies any of my interests
					List<CCNInterestListener> toCallback = new ArrayList<CCNInterestListener>();
					synchronized (_myInterests) {
						for (InterestRegistration reg : _myInterests.getValues(data.completeName())) {
							try {
								if (null != reg.listener) {
									// Save listener, don't call here while holding _myInterests lock
									toCallback.add(reg.listener);
								} else {
									// Store data for the waiting thread
									reg.data = data;
									// Wake it up
									reg.sema.release();
								}
							} catch (Exception ex) {
								Library.logger().warning("CCNNetworkManager: failed to deliver data: " + ex.getMessage());
							}
					    }
					} // synchronized
					// Now perform the callbacks
					if (toCallback.size() > 0) {
						ArrayList<ContentObject> results = new ArrayList<ContentObject>(
								1);
						results.add(data);
						for (CCNInterestListener listener : toCallback) {
							listener.handleResults(results);
						}
					}
				} // for data 
				
				//--------------------------------- Trigger periodic behavior if time
				if (new Date().getTime() - lastsweep.getTime() > PERIOD) {
					throw new SocketTimeoutException();
				}
			} catch (SocketTimeoutException se) {
				// This is where we do all the periodic behavior
				try {
					// Re-express to the network all of the registered interests
					synchronized (_myInterests) {
						for (Entry<InterestRegistration> entry : _myInterests.getMatches(new ContentName("/"))) {
							InterestRegistration reg = entry.value();
							write(reg.interest);
						}
					}
				} catch (IOException io) {
					Library.logger().warning("CCNNetworkManager: Error writing datagram: " + io.getMessage());
				} catch (MalformedContentNameStringException ex) {
					Library.logger().warning("CCNNetworkManager: Internal error: malformed content name string");
				}
			} catch (XMLStreamException xmlex) {
				Library.logger().warning("CCNNetworkManager: Malformed datagram: " + xmlex.getMessage());
			} catch (IOException io) {
				_error = true;
				Library.logger().warning("CCNNetworkManager: Error reading datagram: " + io.getMessage());
			}
		}
	}
}
