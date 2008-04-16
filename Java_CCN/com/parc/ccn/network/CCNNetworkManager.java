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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
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
	protected DatagramSocket _socket = null; // for use by run thread only!
	protected Throwable _error = null; // Marks error state of socket
	protected boolean _run = true;
	protected KeyManager _keyManager;
	protected ContentObject _keepalive; 
	// Tables of interests/filters: users must synchronize on collection
	protected InterestTable<InterestLabel> _othersInterests = new InterestTable<InterestLabel>();
	protected InterestTable<InterestRegistration> _myInterests = new InterestTable<InterestRegistration>();
	protected InterestTable<CCNFilterListener> _myFilters = new InterestTable<CCNFilterListener>();
	// Map of blocked writers: ContentName -> Object to notify() of interest
	protected Map<CompleteName, Semaphore> _writers = Collections.synchronizedMap(new HashMap<CompleteName, Semaphore>());
	// Set of local data to be delivered to satisfy my local interests 
	protected Queue<ContentObject> _newData = new LinkedList<ContentObject>();
	protected Queue<Interest> _newInterests = new LinkedList<Interest>();
		
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
		// Socket is to belong exclusively to run thread started here
		_socket = new DatagramSocket(); // binds ephemeral UDP port
		_socket.setSoTimeout(DEFAULT_AGENT_PORT);
		_socket.connect(InetAddress.getByName("localhost"), DEFAULT_AGENT_PORT);
		_error = new IOException("need to send first packet to CCN agent");
		_thread = new Thread(this);
		_thread.start();
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
		ContentObject co = new ContentObject(name, authenticator, signature, content); 
		Semaphore waitobj = null; // semaphore we use to block when no consumer found
		boolean consumer = false; // is there a consumer?
		while (true) {
			// Check local (within this JVM)
			synchronized (_myInterests) {
				if (null != _myInterests.getMatch(complete)) {
					consumer = true;
					for (InterestRegistration reg : _myInterests.getValues(complete)) {
						try {
							if (null == reg.listener) {
								// Store data for the waiting thread
								reg.data = co;
								// Wake it up
								reg.sema.release();
								// Remove so we don't try to deliver multiple data
								_myInterests.remove(reg.interest, reg);
							}
						} catch (Exception ex) {
							Library.logger().warning("failed to deliver data: " + ex.getMessage());
						}
				    }
				}
			}
			// Check others (outside world)
			synchronized(_othersInterests) {
				if (null != _othersInterests.removeMatch(complete)) {
					consumer = true;
					// Delivery to others will happen below
				}
			}
			if (consumer) {
				if (null != waitobj) {
					// I was waiting, but no longer, I have interest to consume
					_writers.remove(complete);
				}
				// Deliver: queue for delivery by 
				// dedicated run thread that handles socket, callbacks etc.
				// NOTE: We write the data out to the network even if the
				// interest consumed is internal to this process (JVM), because 
				// there is no caching inside the library.  If there are multiple
				// consumers of the data we have a possible race between internal
				// and external interests.  External interests in a race 
				// should normally all be satisfied with data from cache in the
				// agent(s), but in order for that to happen the agent must 
				// see the data.
				// The only reason we will withhold data from the network
				// is if there is not interest in it from anywhere
				synchronized (_newData) {
					_newData.add(co);
				}
				return complete;
			}
			// If we got here then there is no Interest for this data yet
			// We can block waiting for one to show up
			if (null == waitobj) {
				waitobj = new Semaphore(0);
			
				// Assume there is not one in map already since CompleteName unique
				_writers.put(complete, waitobj);
			}
			Library.logger().info("put() blocks with data for " + complete.name());
			waitobj.acquire(); // currently no timeouts
		}
		
		//return CCNRepositoryManager.getRepositoryManager().put(name, authenticator, signature, content);
	}

	protected void write(ContentObject data) throws XMLStreamException {
		WirePacket packet = new WirePacket(data);
		writeInner(packet);
	}

	protected void write(Interest interest) throws XMLStreamException {
		WirePacket packet = new WirePacket(interest);
		writeInner(packet);
	}

	private void writeInner(WirePacket packet) throws XMLStreamException {
		try {
			byte[] bytes = packet.encode();
			DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
			_socket.send(datagram);
		} catch (IOException io) {
			// Best-effort, just mark error
			_error = io;
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

	
	/**
	 * Register a standing interest filter with callback to receive any 
	 * matching interests seen
	 */
	public void setInterestFilter(ContentName filter, CCNFilterListener callbackListener) {
		synchronized (_myFilters) {
			_myFilters.add(filter, callbackListener);
		}
	}
	
	/**
	 * Unregister a standing interest filter
	 */
	public void cancelInterestFilter(ContentName filter, CCNFilterListener callbackListener) {
		synchronized (_myFilters) {
			_myFilters.remove(filter, callbackListener);
		}
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
		// Add to standing interests table
		synchronized (_myInterests) {
			_myInterests.add(interest, reg);
		}
		// Add to inbound processing queue
		synchronized (_newInterests) {
			_newInterests.add(interest);
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
				if (null != _error) {
					// We expect the typical error to be ICMP unreachable 
					// for the agent should it be down.  This is not a normal,
					// healthy state of the system but need to prevent communication
					// within the JVM
					// Try to re-establish normal contact with the agent
					// Send 0-length packet to alert agent to our presence
					Library.logger().info("Network error observed: is agent still running?");
					try {
						_socket.send(new DatagramPacket(new byte[0], 0));
						_error = null;
						Library.logger().info("Network error cleared");
					} catch (IOException io) {
						// Not fixed yet.
						_error = io;
					}
				}
				//--------------------------------- Read and decode
				try {
					_socket.receive(datagram);
					packet.decode(datagram.getData());
				} catch (IOException io) {
					_error = io;
					packet = new WirePacket();
				}
				
				//--------------------------------- Process interests (if any)
				for (Interest interest : packet.interests()) {
					// Record this known interest
					synchronized (_othersInterests) {
						_othersInterests.add(interest, new InterestLabel());
					}
					synchronized (_newInterests) {
						_newInterests.add(interest);
					}
				} // for interests
				processInterests();
				
				//--------------------------------- Process data (if any) 
				synchronized (_newData) {
					_newData.addAll(packet.data());
				}
				processData();
				
				//--------------------------------- Trigger periodic behavior if time
				if (new Date().getTime() - lastsweep.getTime() > PERIOD) {
					throw new SocketTimeoutException();
				}
			} catch (SocketTimeoutException se) {
				// This is where we do all the periodic behavior
				try {
					
					// Process anything delayed in main loop due to lack of inbound
					// packets from network
					processData();
					processInterests();

					// Re-express to the network all of the registered interests
					// TODO re-express only those due to be re-expressed
					synchronized (_myInterests) {
						for (Entry<InterestRegistration> entry : _myInterests.getMatches(new ContentName("/"))) {
							InterestRegistration reg = entry.value();
							write(reg.interest);
							synchronized (_newInterests) {
								_newInterests.add(reg.interest);
							}
						}
					}
					
				} catch (MalformedContentNameStringException ex) {
					Library.logger().warning("Internal error: malformed content name string");
				} catch (XMLStreamException xmlex) {
					Library.logger().warning("Malformed datagram: " + xmlex.getMessage());
				} 
			} catch (XMLStreamException xmlex) {
				Library.logger().warning("Malformed datagram: " + xmlex.getMessage());
			} 
		}
	}

	protected void processInterests() throws XMLStreamException {
		Interest interest = null;
		synchronized (_newInterests) { interest = _newInterests.poll(); }
		while (null != interest) {
			deliverInterest(interest);
			write(interest);
			synchronized (_newInterests) { interest = _newInterests.poll(); }
		}
	}

	protected void deliverInterest(Interest interest) {
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
	}

	protected void processData() throws XMLStreamException {
		ContentObject data = null;
		synchronized (_newData) { data = _newData.poll(); }
		while (null != data) {
			deliverData(data);
			write(data);
			synchronized (_newData) { data = _newData.poll(); }
		}
	}

	// Locally deliver a packet of data, which may have originated locally
	// or from the network
	protected void deliverData(ContentObject data) {
		System.out.println("Deliver data " + data.name());
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
						// Remove so we don't try to deliver multiple data
						_myInterests.remove(reg.interest, reg);
					}
				} catch (Exception ex) {
					Library.logger().warning("failed to deliver data: " + ex.getMessage());
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
	}
}
