package com.parc.ccn.network;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	public static final int SOCKET_TIMEOUT = 1000; // period to wait in ms.
	public static final int PERIOD = 4000; // period for occasional ops in ms.
	public static final String KEEPALIVE_NAME = "/HereIAm";
	
	/**
	 * Static singleton.
	 */
	protected static CCNNetworkManager _networkManager = null;
	
	protected Thread _thread = null; // the main processing thread
	protected ExecutorService _threadpool = null; // pool service for callback threads
	protected DatagramChannel _channel = null; // for use by run thread only!
	protected Selector _selector = null;
	protected Throwable _error = null; // Marks error state of socket
	protected boolean _run = true;
	protected KeyManager _keyManager;
	protected ContentObject _keepalive; 
	protected FileOutputStream _tapStream = null;
	// Tables of interests/filters: users must synchronize on collection
	protected InterestTable<InterestRegistration> _othersInterests = new InterestTable<InterestRegistration>();
	protected InterestTable<InterestRegistration> _myInterests = new InterestTable<InterestRegistration>();
	protected InterestTable<Filter> _myFilters = new InterestTable<Filter>();
	protected List<DataRegistration> _writers = Collections.synchronizedList(new LinkedList<DataRegistration>());
	// Queues of new arrivals from inside to be processed
	protected Queue<DataRegistration> _newData = new LinkedList<DataRegistration>();
	protected Queue<InterestRegistration> _newInterests = new LinkedList<InterestRegistration>();
			
	// Generic superclass for registration objects that may have a listener
	// Handles invalidation and pending delivery consistently to enable 
	// subclass to call listener callback without holding any library locks,
	// yet avoid delivery to a cancelled listener.
	protected abstract class ListenerRegistration implements Runnable {
		protected Object listener;
		public Semaphore sema = null;
		protected boolean deliveryPending = false;
		public void invalidate() {
			// There may be a pending delivery in progress, and it doesn't 
			// happen while holding this lock because that would give the 
			// application callback code power to block library processing.
			// Instead, we use a flag that is checked and set under this lock
			// to be sure that on exit from invalidate() there will be 
			// no future deliveries based on the now-invalid registration.
			while (true) {
				synchronized (this) {
					// Make invalid, this will prevent any new delivery that comes
					// along from doing anything.
					this.listener = null;
					this.sema = null;
					// Return only if no delivery is in progress now
					if (!deliveryPending) {
						return;
					}
				}
				Thread.yield();
			}
		}
		// Equality based on listener if present, so multiple objects can 
		// have the same interest registered without colliding
		public boolean equals(Object obj) {
			if (obj instanceof ListenerRegistration) {
				ListenerRegistration other = (ListenerRegistration)obj;
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
	 * Record of Interest
	 * @field interest Interest itself
	 * @field listener Listener to be notified of data matching the Interest.  The
	 * listener must be set (non-null) for cases of standing Interest that holds 
	 * until canceled by the application.  The listener should be null when a 
	 * thread is blocked waiting for data, in which case the thread will be 
	 * blocked on semaphore.
	 * @field semaphore used to block thread waiting for data or null if none
	 * @field data data for waiting thread
	 * @field lastRefresh last time this interest was refreshed
	 * @field data Holds data responsive to the interest for a waiting thread
	 * @author jthornto
	 *
	 */
	protected class InterestRegistration extends ListenerRegistration {
		public final Interest interest;
		protected ArrayList<ContentObject> data = new ArrayList<ContentObject>(1);
		public Date lastRefresh;
		public InterestRegistration(Interest i, CCNInterestListener l) {
			interest = i; 
			listener = l;
			if (null == listener) {
				sema = new Semaphore(0);
			}
			lastRefresh = new Date();
		}
		// Add a copy of data, not the original data object, so that 
		// the recipient cannot disturb the buffers of the sender
		// We need this even when data comes from network, since receive
		// buffer will be reused while recipient thread may proceed to read
		// from buffer it is handed
		public synchronized void add(ContentObject obj) {
			this.data.add(obj.clone());
		}
		public synchronized ArrayList<ContentObject> data() {
			ArrayList<ContentObject> result = this.data;
			this.data = new ArrayList<ContentObject>(1);
			return result;
		}
		public void run() {
			synchronized (this) {
				// Mark us pending delivery, so that any invalidate() that comes 
				// along will not return until delivery has finished
				this.deliveryPending = true;
			}
			try {
				Library.logger().fine("data delivery for " + this.interest.name());
				if (null != this.listener) {
					// Standing interest: call listener callback
					ArrayList<ContentObject> results = null;
					CCNInterestListener listener = null;
					synchronized (this) {
						if (this.data.size() > 0) {
							results = this.data;
							this.data = new ArrayList<ContentObject>(1);
							listener = (CCNInterestListener)this.listener;
						}
					}
					// Call into client code without holding any library locks
					if (null != results) {
						listener.handleResults(results);
					}
				} else if (null != this.sema) {
					// Waiting thread will pickup data -- wake it up
					// If this interest came from net or waiting thread timed out,
					// then no thread will be waiting but no harm is done
					this.sema.release();
					Library.logger().fine("released " + this.sema);
				} // else this is no longer valid registration
			} catch (Exception ex) {
				Library.logger().warning("failed to deliver data: " + ex.toString());
				//ex.printStackTrace();
			} finally {
				synchronized(this) {
					this.deliveryPending = false;
				}
			}

		}
	}
	
	/**
	 * Record of a filter describing portion of namespace for which this 
	 * application can respond to interests.
	 * @author jthornto
	 *
	 */
	protected class Filter extends ListenerRegistration {
		public ContentName name;
		protected boolean deliveryPending = false;
		protected ArrayList<Interest> interests= new ArrayList<Interest>(1);
		public Filter(ContentName n, CCNFilterListener l) {
			name = n; listener = l;
		}
		public synchronized void add(Interest i) {
			interests.add(i);
		}
		public void run() {
			synchronized (this) {
				// Mark us pending delivery, so that any invalidate() that comes 
				// along will not return until delivery has finished
				this.deliveryPending = true;
			}
			try {
				ArrayList<Interest> results = null;
				CCNFilterListener listener = null;
				synchronized (this) {
					if (this.interests.size() > 0) { 
						results = interests;
						interests = new ArrayList<Interest>(1);
						listener = (CCNFilterListener)this.listener;
					}
				}
				// Call into client code without holding any library locks
				if (null != results) {
					listener.handleResults(results);
				}
			} catch (RuntimeException ex) {
				Library.logger().warning("failed to deliver interest: " + ex.toString());
			} finally {
				synchronized(this) {
					this.deliveryPending = false;
				}
			}
		}
	}
	
	/** 
	 * Data registration: record of data that has not yet consumed interest.
	 * @field data the data itself
	 * @field sema semaphore used to block thread waiting for interest to consume or null
	 * if data came from the network rather than an internal client
	 * @return
	 */
	protected class DataRegistration {
		protected ContentObject data = null;
		public Semaphore sema = null;
		public DataRegistration(ContentObject d, boolean internal) {
			data = d;
			if (internal) {
				sema = new Semaphore(0);
			}
		}
		// On return from invalidate, buffers underlying data object may be reused safely
		public synchronized void invalidate() {
			data = null;
		}
		public boolean isFromNet() {
			return (null == sema);
		}
		public synchronized CompleteName completeName() {
			if (null != data) {
				return data.completeName();
			} else {
				return null;
			}
		}
		public synchronized ContentName name() {
			if (null != data) {
				return data.name();
			} else {
				return null;
			}
		}
		public synchronized void write(CCNNetworkManager mgr) throws XMLStreamException {
			if (null != data) {
				mgr.write(data);
			} // else already invalidated, buffer may be no good, nothing to do
		}
		public synchronized void copyTo(InterestRegistration ireg) {
			if (null != data) {
				ireg.add(data); // actual copying is here
			} // else already invalidated, buffer may be no good, nothing to do
		}
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
//		_socket = new DatagramSocket(); // binds ephemeral UDP port
//		_socket.setSoTimeout(SOCKET_TIMEOUT);
//		_socket.connect(InetAddress.getByName("localhost"), DEFAULT_AGENT_PORT);
		_channel = DatagramChannel.open();
		_channel.connect(new InetSocketAddress("localhost", DEFAULT_AGENT_PORT));
		_channel.configureBlocking(false);
		_selector = Selector.open();
		_channel.register(_selector, SelectionKey.OP_READ);
		// Create callback threadpool and main processing thread
		_threadpool = Executors.newCachedThreadPool();
		_thread = new Thread(this);
		_thread.start();
	}
	
	public void shutdown() {
		Library.logger().info("Shutdown requested");
		_run = false;
		_selector.wakeup();
	}
	
	/**
	 * Turn on writing of all packets to a file for test/debug
	 * @param filename
	 */
	public void setTap(String pathname) throws IOException {
		_tapStream = new FileOutputStream(new File(pathname));
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
	 * Put a piece of data, blocking until there is Interest to consume so that 
	 * flow control is maintained
	 * TODO persistence protocol
	 */
	public CompleteName put(ContentName name, ContentAuthenticator authenticator, 
							byte [] signature, byte[] content) throws IOException, InterruptedException {
		CompleteName complete = new CompleteName(name, authenticator, signature);
		ContentObject co = new ContentObject(name, authenticator, signature, content); 
		DataRegistration reg = new DataRegistration(co, true);
		// Add to internal processing queue
		synchronized (_newData) {
			_newData.add(reg);
		}
		_selector.wakeup();
		// Await interest consumption
		reg.sema.acquire(); // currently no timeouts
		// The main processing thread may have had to register this writer
		// which must be undone here, but no harm if never registered
		_writers.remove(reg);
		reg.invalidate(); // make sure that buffer is ignored from here on
		return complete;
		//return CCNRepositoryManager.getRepositoryManager().put(name, authenticator, signature, content);
	}

	/**
	 * Gets we also currently forward to the repository.
	 */
	public ArrayList<ContentObject> get(ContentName name, 
									    ContentAuthenticator authenticator,
									    boolean isRecursive) throws IOException, InterruptedException {
		Interest interest = new Interest(name);
		InterestRegistration reg = new InterestRegistration(interest, null);
		// Add to internal processing queue
		synchronized (_newInterests) {
			_newInterests.add(reg);
		}
		_selector.wakeup();
		Library.logger().fine("blocking for " + interest.name() + " on " + reg.sema);
		// Await data to consume the interest 
		reg.sema.acquire(); // currently no timeouts
		Library.logger().fine("unblocked for " + interest.name() + " on " + reg.sema);
		// Typically the main processing thread will have registered the interest
		// which must be undone here, but no harm if never registered
		unregisterInterest(reg);
		return reg.data();
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
		//CCNRepositoryManager.getRepositoryManager().expressInterest(interest, callbackListener);

		InterestRegistration reg = new InterestRegistration(interest, callbackListener);
		// Add to internal processing queue
		// We leave actual registration to the processing thread so that 
		// concurrency problems like double-delivery can be avoided
		synchronized (_newInterests) {
			_newInterests.add(reg);
		}
		_selector.wakeup();
	}
	
	/**
	 * Cancel this query with all the repositories we sent
	 * it to.
	 */
	public void cancelInterest(Interest interest, CCNInterestListener callbackListener) throws IOException {
		if (null == callbackListener) {
			throw new NullPointerException("cancelInterest: callbackListener cannot be null");
		}
	
		// Remove interest from repeated presentation to the network.
		unregisterInterest(interest, callbackListener);
	}

	
	/**
	 * Register a standing interest filter with callback to receive any 
	 * matching interests seen
	 */
	public void setInterestFilter(ContentName filter, CCNFilterListener callbackListener) {
		synchronized (_myFilters) {
			_myFilters.add(filter, new Filter(filter, callbackListener));
		}
	}
	
	/**
	 * Unregister a standing interest filter
	 */
	public void cancelInterestFilter(ContentName filter, CCNFilterListener callbackListener) {
		synchronized (_myFilters) {
			Entry<Filter> found = _myFilters.remove(filter, new Filter(filter, callbackListener));
			if (null != found) {
				found.value().invalidate();
			}
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
			ByteBuffer datagram = ByteBuffer.wrap(bytes);
			_channel.write(datagram);
			if (null != _tapStream) {
				try {
					_tapStream.write(bytes);
				} catch (IOException io) {
					Library.logger().warning("Unable to write packet to tap stream for debugging");
				}
			}
		} catch (IOException io) {
			// We do not see errors on send typically even if 
			// agent is gone, so log each but do not track
			Library.logger().warning("Error sending packet: " + io.toString());
		}
	}


	/**
	 * Pass things on to the network stack.
	 */
	InterestRegistration registerInterest(InterestRegistration reg) {
		// Add to standing interests table
		Library.logger().fine("registerInterest for " + reg.interest.name());
		synchronized (_myInterests) {
			_myInterests.add(reg.interest, reg);
		}
		return reg;
	}
	
	// external version: for use when we only have interest from client.  For all internal
	// purposes we should unregister the InterestRegistration we already have
	void unregisterInterest(Interest interest, CCNInterestListener callbackListener) {
		InterestRegistration reg = new InterestRegistration(interest, callbackListener);
		unregisterInterest(reg);
	}
	
	void unregisterInterest(InterestRegistration reg) {
		synchronized (_myInterests) {
			Entry<InterestRegistration> found = _myInterests.remove(reg.interest, reg);
			if (null != found) {
				found.value().invalidate();
			}
		}		
	}

	// Thread method: this thread will handle reading datagrams and 
	// the periodic re-expressing of standing interests
	public void run() {
		// Allocate datagram buffer: want to wrap array to ensure backed by
		// array to permit decoding
		byte[] buffer = new byte[MAX_PAYLOAD];
		ByteBuffer datagram = ByteBuffer.wrap(buffer);
		WirePacket packet = new WirePacket();
		Date lastsweep = new Date(); 
		Date lastheartbeat = new Date(0);
		while (_run) {
			try {
				
				//--------------------------------- Heartbeat
				// Send 0-length packet to alert agent to our presence
				if (new Date().getTime() - lastheartbeat.getTime() > (PERIOD * 2)) {

					try {
						ByteBuffer heartbeat = ByteBuffer.allocate(0);
						_channel.write(heartbeat);
						lastheartbeat = new Date();
					} catch (IOException io) {
						// We do not see errors on send typically even if 
						// agent is gone, so log each but do not track
						Library.logger().warning("Error sending heartbeat packet");
					}
				}
				
				//--------------------------------- Read and decode
				try {
					if (_selector.select(SOCKET_TIMEOUT) != 0) {
						// Note: we're selecting on only one channel to get
						// the ability to use wakeup, so there is no need to 
						// inspect the selected-key set
						datagram.clear(); // make ready for new read
						_channel.read(datagram);
						if (null != _error) {
							Library.logger().info("Receive error cleared");
							_error = null;
						}
						datagram.rewind(); // make ready to decode
						packet.decode(datagram);
					} else {
						// This was a timeout or wakeup, no data
						packet.clear();
						if (!_run) {
							// exit immediately if wakeup for shutdown
							break;
						}
					}
				} catch (IOException io) {
					// We see IOException on receive every time if agent is gone
					// so track it to log only start and end of outages
					if (null == _error) {
						Library.logger().info("Unable to receive from agent: is it still running?");
					}
					_error = io;
					packet.clear();
				}
				
				//--------------------------------- Process internal data (if any)
				// Must do internal data first so all internal interests get delivery
				// before a new external interest can be consumed by this data 
				DataRegistration iData = null;
				synchronized (_newData) { iData = _newData.poll(); }
				while (null != iData) {
					boolean consumer = deliverData(iData);
					synchronized(_othersInterests) {
						if (null != _othersInterests.removeMatch(iData.completeName())) {
							consumer = true;
						}
					}
					if (consumer) {
						// Internal data goes to network only if there was Interest 
						// either internal or external
						iData.write(this);
						// Do not release the put() until data has been completely
						// delivered so that buffer may be reused
						Library.logger().fine("releasing writer for " + iData.name());
						iData.sema.release();
					} else {
						// No interest to consume yet: hold on to this data for 
						// future interest, which will keep the put thread blocked
						_writers.add(iData);
					}
					synchronized (_newData) { iData = _newData.poll(); }
				}

				//--------------------------------- Process data from net (if any) 
				for (ContentObject co : packet.data()) {
					DataRegistration oData = new DataRegistration(co, false);
					deliverData(oData);
					// External data never goes back to network, never held onto here
					// External data never has a thread waiting, so no need to release sema
				}

				//--------------------------------- Process internal interests (if any)
				InterestRegistration iInterest = null;
				synchronized (_newInterests) { iInterest = _newInterests.poll(); }
				while (null != iInterest) {
					if (null == deliverInterest(iInterest) || null != iInterest.listener) {
						// Unsatisfied temporary and all standing interests go to network
						// This prevents internal interests from consuming data 
						// so it never gets to agent to cache. 
						// The only reason we will withhold data from the network
						// is if there is not interest in it from anywhere
						write(iInterest.interest);		
						registerInterest(iInterest);
					}
					synchronized (_newInterests) { iInterest = _newInterests.poll(); }
				}

				//--------------------------------- Process interests from net (if any)
				for (Interest interest : packet.interests()) {
					InterestRegistration oInterest = new InterestRegistration(interest, null);
					DataRegistration dreg = deliverInterest(oInterest);
					if (null == dreg) {
						// Record this known interest that has not already been consumed
						synchronized (_othersInterests) {
							_othersInterests.add(interest, oInterest);
						}
					} else {
						dreg.write(this);
					}
					// External interests never go back to network
				} // for interests
				
				//--------------------------------- Trigger periodic behavior if time
				if (new Date().getTime() - lastsweep.getTime() > PERIOD) {
					// This is where we do all the periodic behavior

					// Re-express all of the registered interests
					// TODO re-express only those due to be re-expressed
					synchronized (_myInterests) {
						for (Entry<InterestRegistration> entry : _myInterests.getMatches(new ContentName("/"))) {
							InterestRegistration reg = entry.value();
							write(reg.interest);
							deliverInterest(reg);
						}
					}
					lastsweep = new Date();
				}

			} catch (XMLStreamException xmlex) {
				Library.logger().warning("Malformed datagram: " + xmlex.getMessage());
			}  catch (MalformedContentNameStringException ex) {
				Library.logger().warning("Internal error: malformed content name string");
			}
		}
		_threadpool.shutdown();
		Library.logger().info("Shutdown complete");
	}

	// Internal delivery of interests to pending writers and filter listeners
	protected DataRegistration deliverInterest(InterestRegistration ireg) throws XMLStreamException {
		DataRegistration result = null;
		// First pending writer with data gets to consume this interest
		for (Iterator<DataRegistration> writeIter = _writers.iterator(); writeIter.hasNext();) {
			DataRegistration dreg = (DataRegistration) writeIter.next();
			if (ireg.interest.matches(dreg.completeName())) {
				dreg.copyTo(ireg); // this is a copy of the data
				_threadpool.execute(ireg);
				dreg.sema.release();
				result = dreg;
				break;
			}
		}
		// Call any listeners with matching filters
		synchronized (_myFilters) {
			for (Filter filter : _myFilters.getValues(ireg.interest.name())) {
				filter.add(ireg.interest);
				_threadpool.execute(filter);
			}
		}
		return result;
	}

	// internal delivery of data, which may have originated locally
	// or from the network.
	protected boolean deliverData(DataRegistration dreg) {
		boolean consumer = false; // is there a consumer?

		// Check local interests
		synchronized (_myInterests) {
			for (InterestRegistration ireg : _myInterests.getValues(dreg.completeName())) {
				dreg.copyTo(ireg); // this is a copy of the data
				_threadpool.execute(ireg);
				consumer = true;
			}
		}
		return consumer;
	}
}
