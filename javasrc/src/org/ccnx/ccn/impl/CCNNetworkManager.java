/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.InterestTable.Entry;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.ccnd.CCNDaemonException;
import org.ccnx.ccn.profiles.ccnd.CCNDaemonProfile;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager.ForwardingEntry;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.WirePacket;


/**
 * The low level interface to ccnd. Connects to a ccnd and maintains the connection by sending 
 * heartbeats to it.  Other functions include reading and writing interests and content
 * to/from the ccnd, starting handler threads to feed interests and content to registered handlers,
 * and refreshing unsatisfied interests. 
 * 
 * This class attempts to notice when a ccnd has died and to reconnect to a ccnd when it is restarted.
 * 
 * It also handles the low level output "tap" functionality - this allows inspection or logging of
 * all the communications with ccnd.
 * 
 * Starts a separate thread to listen to, decode and handle incoming data from ccnd.
 */
public class CCNNetworkManager implements Runnable {
	
	public static final int DEFAULT_AGENT_PORT = 9695; // ccnx registered port
	public static final String DEFAULT_AGENT_HOST = "localhost";
	public static final String PROP_AGENT_PORT = "ccn.agent.port";
	public static final String PROP_AGENT_HOST = "ccn.agent.host";
	public static final String PROP_TAP = "ccn.tap";
	public static final String ENV_TAP = "CCN_TAP"; // match C library
	public static final int MAX_PAYLOAD = 8800; // number of bytes in UDP payload
	public static final int SOCKET_TIMEOUT = 1000; // period to wait in ms.
	public static final int PERIOD = 2000; // period for occasional ops in ms.
	public static final int HEARTBEAT_PERIOD = 3500;
	public static final int MAX_PERIOD = PERIOD * 8;
	public static final String KEEPALIVE_NAME = "/HereIAm";
	public static final int THREAD_LIFE = 8;	// in seconds
	
	/**
	 *  Definitions for which network protocol to use.  This allows overriding
	 *  the current default.
	 */
	public enum NetworkProtocol {
		UDP (17), TCP (6);
		NetworkProtocol(Integer i) { this._i = i; }
		private final Integer _i;
		public Integer value() { return _i; }
	}
		
	public static final String PROP_AGENT_PROTOCOL_KEY = "ccn.agent.protocol";
	public static final NetworkProtocol DEFAULT_PROTOCOL = NetworkProtocol.UDP;
	public static final String PROP_AGENT_PREFIX_REG = "ccn.agent.prefix_reg";
	public static final String ENV_AGENT_PREFIX_REG = "CCND_TRYFIB";

	
	/*
	 *  This ccndId is set on the first connection with 'ccnd' and is the
	 *  'device name' that all of our control communications will use to
	 *  ensure that we are talking to our local 'ccnd'.
	 */
	protected static Integer _idSyncer = new Integer(0);
	protected static PublisherPublicKeyDigest _ccndId = null;
	protected Integer _faceID = null;
	protected CCNDIdGetter _getter = null;
	
	/*
	 * Static singleton.
	 */
	protected Thread _thread = null; // the main processing thread
	protected ThreadPoolExecutor _threadpool = null; // pool service for callback threads
	protected DatagramChannel _channel = null; // for use by run thread only!
	protected Selector _selector = null;
	protected Throwable _error = null; // Marks error state of socket
	protected boolean _run = true;

	// protected ContentObject _keepalive; 
	protected FileOutputStream _tapStreamOut = null;
	protected FileOutputStream _tapStreamIn = null;
	protected long _lastHeartbeat = 0;
	protected int _port = DEFAULT_AGENT_PORT;
	protected String _host = DEFAULT_AGENT_HOST;
	protected NetworkProtocol _protocol = DEFAULT_PROTOCOL;

	
	// For handling protocol to speak to ccnd, must have keys
	protected KeyManager _keyManager;
	protected int _localPort = -1;
	
	// Tables of interests/filters: users must synchronize on collection
	protected InterestTable<InterestRegistration> _myInterests = new InterestTable<InterestRegistration>();
	protected InterestTable<Filter> _myFilters = new InterestTable<Filter>();
	public static final boolean DEFAULT_PREFIX_REG = false; // Until ccnd gets updated
	protected boolean _usePrefixReg = DEFAULT_PREFIX_REG;
	protected PrefixRegistrationManager _prefixMgr = null;
	private Timer _periodicTimer = null;
	private boolean _timersSetup = false;
	
	/**
	 * Do scheduled writes of heartbeats and interest refreshes
	 */
	private class PeriodicWriter extends TimerTask {
		// TODO Interest refresh time is supposed to "decay" over time but there are currently
		// unresolved problems with this.
		public void run() {
			// MM Avoid new calls
			//long ourTime = new Date().getTime();
			long ourTime = System.currentTimeMillis();
			if ((ourTime - _lastHeartbeat) > HEARTBEAT_PERIOD) {
				_lastHeartbeat = ourTime;
				heartbeat();
			}

			long minInterestRefreshTime = PERIOD + ourTime;
			// Library.finest("Refreshing interests (size " + _myInterests.size() + ")");

			// Re-express interests that need to be re-expressed
			try {
				synchronized (_myInterests) {
					for (Entry<InterestRegistration> entry : _myInterests.values()) {
						InterestRegistration reg = entry.value();
						if (ourTime > reg.nextRefresh) {
							if( Log.isLoggable(Level.FINER) )
								Log.finer("Refresh interest: {0}", reg.interest);
							// Temporarily back out refresh period decay
							//reg.nextRefreshPeriod = (reg.nextRefreshPeriod * 2) > MAX_PERIOD ? MAX_PERIOD
							//: reg.nextRefreshPeriod * 2;
							reg.nextRefresh += reg.nextRefreshPeriod;
							try {
								write(reg.interest);
							} catch (NotYetConnectedException nyce) {}
						}
						if (minInterestRefreshTime > reg.nextRefresh)
							minInterestRefreshTime = reg.nextRefresh;
					}
				}
			} catch (ContentEncodingException xmlex) {
				Log.severe("PeriodicWriter interest refresh thread failure (Malformed datagram): {0}", xmlex.getMessage()); 
				Log.warningStackTrace(xmlex);
			}


			long minFilterRefreshTime = PERIOD + ourTime;
			// Re-express prefix registrations that need to be re-expressed
			// FIXME: The lifetime of a prefix is returned in seconds, not milliseconds.  The refresh code needs
			// to understand this.  This isn't a problem for now because the lifetime we request when we register a 
			// prefix we use Integer.MAX_VALUE as the requested lifetime.
			if (_usePrefixReg) {
				synchronized (_myFilters) {
					if( Log.isLoggable(Level.FINE) )
						Log.fine("Refresh registration.  size: " + _myFilters.size() + " sizeNames: " + _myFilters.sizeNames());
					for (Entry<Filter> entry : _myFilters.values()) {
						Filter filter = entry.value();
						if (null != filter.forwarding && filter.lifetime != -1 && filter.nextRefresh != -1) {
							if (ourTime > filter.nextRefresh) {
								if( Log.isLoggable(Level.FINER) )
									Log.finer("Refresh registration: {0}", filter.prefix);
								filter.nextRefresh = -1;
								try {
									ForwardingEntry forwarding = _prefixMgr.selfRegisterPrefix(filter.prefix);
									if (null != forwarding) {
										filter.lifetime = forwarding.getLifetime();
//										filter.nextRefresh = new Date().getTime() + (filter.lifetime / 2);
										filter.nextRefresh = System.currentTimeMillis() + (filter.lifetime / 2);
									}
									filter.forwarding = forwarding;

								} catch (CCNDaemonException e) {
									Log.warning(e.getMessage());
									filter.forwarding = null;
									filter.lifetime = -1;
									filter.nextRefresh = -1;
								}
							}	
							if (minFilterRefreshTime > filter.nextRefresh)
								minFilterRefreshTime = filter.nextRefresh;
						}
					} /* for (Entry<Filter> entry : _myFilters.values()) */
				} /* synchronized (_myFilters) */
			} /* _usePrefixReg */

			long currentTime = System.currentTimeMillis();
			long checkInterestDelay = minInterestRefreshTime - currentTime;
			if (checkInterestDelay < 0)
				checkInterestDelay = 0;
			if (checkInterestDelay > PERIOD)
				checkInterestDelay = PERIOD;

			long checkPrefixDelay = minFilterRefreshTime - currentTime;
			if (checkPrefixDelay < 0)
				checkPrefixDelay = 0;
			if (checkPrefixDelay > PERIOD)
				checkPrefixDelay = PERIOD;
			
			long useMe;
			if (checkInterestDelay < checkPrefixDelay) {
				useMe = checkInterestDelay;
			} else {
				useMe = checkPrefixDelay;
			}
			_periodicTimer.schedule(new PeriodicWriter(), useMe);
		} /* run */
	} /* private class PeriodicWriter extends TimerTask */
	
	/**
	 * Send the heartbeat. Also attempt to detect ccnd going down.
	 */
	private void heartbeat() {
		try {
			ByteBuffer heartbeat = ByteBuffer.allocate(1);
			if (_channel.isConnected())
				_channel.write(heartbeat);
		} catch (IOException io) {
			// We do not see errors on send typically even if 
			// agent is gone, so log each but do not track
			Log.warning("Error sending heartbeat packet: {0}", io.getMessage());
			try {
				if (_channel.isConnected())
					_channel.disconnect();
			} catch (IOException e) {}
		}
	}
	
	/**
	 * First time startup of timing stuff after first registration
	 * We don't bother to "unstartup" if everything is deregistered
	 */
	private void setupTimers() {
		if (!_timersSetup) {
			_timersSetup = true;
			heartbeat();
			
			// Create timer for heartbeats and other periodic behavior
			_periodicTimer = new Timer(true);
			_periodicTimer.schedule(new PeriodicWriter(), PERIOD);
		}
	}
			
	/** Generic superclass for registration objects that may have a listener
	 *	Handles invalidation and pending delivery consistently to enable 
	 *	subclass to call listener callback without holding any library locks,
	 *	yet avoid delivery to a cancelled listener.
	 */
	protected abstract class ListenerRegistration implements Runnable {
		protected Object listener;
		protected CCNNetworkManager manager;
		public Semaphore sema = null;	//used to block thread waiting for data or null if none
		public Object owner = null;
		protected boolean deliveryPending = false;
		protected long id;
		
		public abstract void deliver();
		
		/**
		 * This is called when removing interest or content handlers. It's purpose
		 * is to insure that once the remove call begins it completes atomically without more 
		 * handlers being triggered. Note that there is still not full atomicity here
		 * because a dispatch to handler might be in progress and we don't hold locks 
		 * throughout the dispatch to avoid deadlocks.
		 */
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
					// Return only if no delivery is in progress now (or if we are
					// called out of our own handler)
					if (!deliveryPending || (Thread.currentThread().getId() == id)) {
						return;
					}
				}
				Thread.yield();
			}
		}
		
		/**
		 * Calls the client handler
		 */
		public void run() {
			id = Thread.currentThread().getId();
			synchronized (this) {
				// Mark us pending delivery, so that any invalidate() that comes 
				// along will not return until delivery has finished
				this.deliveryPending = true;
			}
			try {
				// Delivery may synchronize on this object to access data structures
				// but should hold no locks when calling the listener
				deliver();
			} catch (Exception ex) {
				Log.warning("failed delivery: {0}", ex);
			} finally {
				synchronized(this) {
					this.deliveryPending = false;
				}
			}
		}
		
		/** Equality based on listener if present, so multiple objects can 
		 *  have the same interest registered without colliding
		 */
		public boolean equals(Object obj) {
			if (obj instanceof ListenerRegistration) {
				ListenerRegistration other = (ListenerRegistration)obj;
				if (this.owner == other.owner) {
					if (null == this.listener && null == other.listener){
						return super.equals(obj);
					} else if (null != this.listener && null != other.listener) {
						return this.listener.equals(other.listener);
					}
				}
			}
			return false;
		}
		public int hashCode() {
			if (null != this.listener) {
				if (null != owner) {
					return owner.hashCode() + this.listener.hashCode();
				} else {
					return this.listener.hashCode();
				}
			} else {
				return super.hashCode();
			}
		}
	} /* protected abstract class ListenerRegistration implements Runnable */
	
	/**
	 * Record of Interest
	 * listener must be set (non-null) for cases of standing Interest that holds 
	 * until canceled by the application.  The listener should be null when a 
	 * thread is blocked waiting for data, in which case the thread will be 
	 * blocked on semaphore.
	 */
	protected class InterestRegistration extends ListenerRegistration {
		public final Interest interest;
		ContentObject data = null;
		protected long nextRefresh;		// next time to refresh the interest
		protected long nextRefreshPeriod = PERIOD * 2;	// period to wait before refresh
		
		// All internal client interests must have an owner
		public InterestRegistration(CCNNetworkManager mgr, Interest i, CCNInterestListener l, Object owner) {
			manager = mgr;
			interest = i; 
			listener = l;
			this.owner = owner;
			if (null == listener) {
				sema = new Semaphore(0);
			}
			nextRefresh = System.currentTimeMillis() + nextRefreshPeriod;
		}
		
		/**
		 * Return true if data was added.
		 * If data is already pending for delivery for this interest, the 
	     * interest is already consumed and this new data cannot be delivered.
	     * @throws NullPointerException If obj is null 
		 */
		public synchronized boolean add(ContentObject obj) {
			// Add a copy of data, not the original data object, so that 
			// the recipient cannot disturb the buffers of the sender
			// We need this even when data comes from network, since receive
			// buffer will be reused while recipient thread may proceed to read
			// from buffer it is handed
			if (null == data) {
				// No data pending, this obj will consume interest
				this.data = obj.clone(); // we let this raise exception if obj == null
				return true;
			} else {
				// Data is already pending, this interest is already consumed, cannot add obj
				return false;
			}
		}
		
		/**
		 * This used to be called just data, but its similarity
		 * to a simple accessor made the fact that it cleared the data
		 * really confusing and error-prone...
		 * Pull the available data out for processing.
		 * @return
		 */
		public synchronized ContentObject popData() {
			ContentObject result = this.data;
			this.data = null;
			return result;
		}
		
		/**
		 * Deliver content to a registered handler
		 */
		public void deliver() {
			try {
				if (null != this.listener) {
					// Standing interest: call listener callback
					ContentObject pending = null;
					CCNInterestListener listener = null;
					synchronized (this) {
						if (this.data != null) {
							pending = this.data;
							this.data = null;
							listener = (CCNInterestListener)this.listener;
						}
					}
					// Call into client code without holding any library locks
					if (null != pending) {
						if( Log.isLoggable(Level.FINER) )
							Log.finer("Interest callback (" + pending + " data) for: {0}", this.interest.name());
						
						synchronized (this) {
							// DKS -- dynamic interests, unregister the interest here and express new one if we have one
							// previous interest is final, can't update it
							this.deliveryPending = false;
						}
						manager.unregisterInterest(this);
						
						// paul r. note - contract says interest will be gone after the call into user's code.
						// Eventually this may be modified for "pipelining".
						
						// DKS TODO tension here -- what object does client use to cancel?
						// Original implementation had expressInterest return a descriptor
						// used to cancel it, perhaps we should go back to that. Otherwise
						// we may need to remember at least the original interest for cancellation,
						// or a fingerprint representation that doesn't include the exclude filter.
						// DKS even more interesting -- how do we update our interest? Do we?
						// it's final now to avoid contention, but need to change it or change
						// the registration.
						Interest updatedInterest = listener.handleContent(pending, interest);
						
						// Possibly we should optimize here for the case where the same interest is returned back
						// (now we would unregister it, then reregister it) but need to be careful that the timing
						// behavior is right if we do that
						if (null != updatedInterest) {
							if( Log.isLoggable(Level.FINER) )
								Log.finer("Interest callback: updated interest to express: {0}", updatedInterest.name());
							// luckily we saved the listener
							// if we want to cancel this one before we get any data, we need to remember the
							// updated interest in the listener
							manager.expressInterest(this.owner, updatedInterest, listener);
						}
					
					} else {
						if( Log.isLoggable(Level.FINER) )
							Log.finer("Interest callback skipped (no data) for: {0}", this.interest.name());
					}
				} else {
					synchronized (this) {
						if (null != this.sema) {
							if( Log.isLoggable(Level.FINER) )
								Log.finer("Data consumes pending get: {0}", this.interest.name());
							// Waiting thread will pickup data -- wake it up
							// If this interest came from net or waiting thread timed out,
							// then no thread will be waiting but no harm is done
							if( Log.isLoggable(Level.FINEST) )
								Log.finest("releasing {0}", this.sema);
							this.sema.release();
						} 
					}
					if (null == this.sema) {
						// this is no longer valid registration
						if( Log.isLoggable(Level.FINER) )
							Log.finer("Interest callback skipped (not valid) for: {0}", this.interest.name());
					}
				}
			} catch (Exception ex) {
				Log.warning("failed to deliver data: {0}", ex);
				Log.warningStackTrace(ex);
			}
		}
		
		/**
		 * Start a thread to deliver data to a registered handler
		 */
		public void run() {
			synchronized (this) {
				// For now only one piece of data may be delivered per InterestRegistration
				// This might change when "pipelining" is implemented
				if (deliveryPending)
					return;
			}
			super.run();
		}
	} /* protected class InterestRegistration extends ListenerRegistration */
	
	/**
	 * Record of a filter describing portion of namespace for which this 
	 * application can respond to interests. Used to deliver incoming interests
	 * to registered interest handlers
	 */
	protected class Filter extends ListenerRegistration {
		public ContentName prefix;  /* Also used to remember registration with ccnd */
		protected Interest interest; // interest to be delivered
		// extra interests to be delivered: separating these allows avoidance of ArrayList obj in many cases
		protected ArrayList<Interest> extra = new ArrayList<Interest>(1); 
		// protected Integer faceId = null;
		ForwardingEntry forwarding = null;
		// FIXME: The lifetime of a prefix is returned in seconds, not milliseconds.  The refresh code needs
		// to understand this.  This isn't a problem for now because the lifetime we request when we register a 
		// prefix we use Integer.MAX_VALUE as the requested lifetime.
		long lifetime = -1; // in seconds
		long nextRefresh = -1;
		
		public Filter(CCNNetworkManager mgr, ContentName n, CCNFilterListener l, Object o, ForwardingEntry e) {
			prefix = n; listener = l; owner = o;
			manager = mgr;
			forwarding = e;
			if (null != forwarding) {
				lifetime = forwarding.getLifetime();
				nextRefresh = System.currentTimeMillis() + (lifetime / 2);
			}
		}
		
		public ForwardingEntry getEntry() {
			return forwarding;
		}
		
		public synchronized void add(Interest i) {
			if (null == interest) {
				interest = i;
			} else {
				// Special case, more than 1 interest pending for delivery
				// Only 1 interest gets added at a time, but more than 1 
				// may arrive before a callback is dispatched
				if (null == extra) {
					extra = new ArrayList<Interest>(1);
				}
				extra.add(i);
			}
		}
		
		/**
		 * Deliver interest to a registered handler
		 */
		public void deliver() {
			try {
				Interest pending = null;
				ArrayList<Interest> pendingExtra = null;
				CCNFilterListener listener = null;
				// Grab pending interest(s) under the lock
				synchronized (this) {
					if (null != this.interest) {
						pending = interest;
						interest = null;
						if (null != this.extra) { 
							pendingExtra = extra;
							extra = null;
							// Don't create new ArrayList for extra here, will be done only as needed in add()
						}
					}
					listener = (CCNFilterListener)this.listener;
				}
	
				// pending signifies whether there is anything
				if (null != pending) {	
					// Call into client code without holding any library locks
					if( Log.isLoggable(Level.FINER) )
						Log.finer("Filter callback for: {0}", prefix);
					listener.handleInterest(pending);
					// Now extra callbacks for additional interests
					if (null != pendingExtra) {
						int countExtra = 0;
						for (Interest pi : pendingExtra) {
							if( Log.isLoggable(Level.FINER) ) {
								countExtra++;
								Log.finer("Filter callback (extra {0} of {1}) for: {2}", countExtra, pendingExtra.size(), prefix);
							}
							listener.handleInterest(pi);
						}
					}
				} else {
					if( Log.isLoggable(Level.FINER) )
						Log.finer("Filter callback skipped (no interests) for: {0}", prefix);
				}
			} catch (RuntimeException ex) {
				Log.warning("failed to deliver interest: {0}", ex);
			}
		}
		@Override
		public String toString() {
			return prefix.toString();
		}
	} /* protected class Filter extends ListenerRegistration */

	private class CCNDIdGetter implements Runnable {
		CCNNetworkManager _networkManager;
		KeyManager _keyManager;

		@SuppressWarnings("unused")
		public CCNDIdGetter(CCNNetworkManager networkManager, KeyManager keyManager) { 
			_networkManager = networkManager;
			_keyManager = keyManager;
		}

		public void run() {
			boolean isNull = false;
			PublisherPublicKeyDigest sentID = null;
			synchronized (_idSyncer) {
				isNull = (null == _ccndId);
			}
			if (isNull) {
				try {
					sentID = fetchCCNDId(_networkManager, _keyManager);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if (null == sentID) {
					Log.severe("CCNDIdGetter: call to fetchCCNDId returned null.");
				}
				synchronized(_idSyncer) {
					_ccndId = sentID;
					if( Log.isLoggable(Level.INFO) )
						Log.info("CCNDIdGetter: ccndId {0}", ContentName.componentPrintURI(sentID.digest()));
				}
			} /* null == _ccndId */
		} /* run() */

	} /* private class CCNDIdGetter implements Runnable */

	/**
	 * The constructor. Attempts to connect to a ccnd at the currently specified port number
	 * @throws IOException if the port is invalid
	 */
	public CCNNetworkManager(KeyManager keyManager) throws IOException {
		if (null == keyManager) {
			// Unless someone gives us one later, we won't be able to register filters. Log this.
			if( Log.isLoggable(Level.INFO) )
				Log.info("CCNNetworkManager: being created with null KeyManager. Must set KeyManager later to be able to register filters.");
		}
		
		_keyManager = keyManager;
		
		// Determine port at which to contact agent
		String portval = System.getProperty(PROP_AGENT_PORT);
		if (null != portval) {
			try {
				_port = new Integer(portval);
			} catch (Exception ex) {
				throw new IOException("Invalid port '" + portval + "' specified in " + PROP_AGENT_PORT);
			}
			Log.warning("Non-standard CCN agent port " + _port + " per property " + PROP_AGENT_PORT);
		}
		String hostval = System.getProperty(PROP_AGENT_HOST);
		if (null != hostval && hostval.length() > 0) {
			_host = hostval;
			Log.warning("Non-standard CCN agent host " + _host + " per property " + PROP_AGENT_HOST);
		}
		
		String proto = System.getProperty(PROP_AGENT_PROTOCOL_KEY);
		if (null != proto) {
			boolean found = false;
			for (NetworkProtocol p : NetworkProtocol.values()) {
				String pAsString = p.toString();
				if (proto.equalsIgnoreCase(pAsString)) {
					Log.warning("CCN agent protocol changed to " + pAsString + "per property");
					_protocol = p;
					found = true;
					break;
				}
			}
			if (!found) {
				throw new IOException("Invalid protocol '" + proto + "' specified in " + PROP_AGENT_PROTOCOL_KEY);
			}
		} else {
			_protocol = DEFAULT_PROTOCOL;
		}

		String prefix = System.getProperty(PROP_AGENT_PREFIX_REG);
		if (null == prefix) {
			prefix = System.getenv(ENV_AGENT_PREFIX_REG);
		}
		if (null != prefix) {
			/* if the property is anything other than 'false' assume using prefix registration */
			if (prefix.equalsIgnoreCase("false")) {
				_usePrefixReg = false;
			} else {
				_usePrefixReg = true;
			}
			Log.warning("CCN agent use of prefix registration changed to " + _usePrefixReg + " per property");
		} else {
			_usePrefixReg = DEFAULT_PREFIX_REG;
		}
		

		if( Log.isLoggable(Level.INFO) )
			Log.info("Contacting CCN agent at " + _host + ":" + _port);
		
		String tapname = System.getProperty(PROP_TAP);
		if (null == tapname) {
			tapname = System.getenv(ENV_TAP);
		}
		if (null != tapname) {
			long msecs = System.currentTimeMillis();
			long secs = msecs/1000;
			msecs = msecs % 1000;
			String unique_tapname = tapname + "-T" + Thread.currentThread().getId() +
								    "-" + secs + "-" + msecs;
			setTap(unique_tapname);
		}
		
		// Socket is to belong exclusively to run thread started here
		_channel = DatagramChannel.open();
		_channel.connect(new InetSocketAddress(_host, _port));
		_channel.configureBlocking(false);
		_selector = Selector.open();
		_channel.register(_selector, SelectionKey.OP_READ);
		_localPort = _channel.socket().getLocalPort();
		if( Log.isLoggable(Level.INFO) )
			Log.info("Connection to CCN agent using local port number: " + _localPort);
		
		// Create callback threadpool and main processing thread
		_threadpool = (ThreadPoolExecutor)Executors.newCachedThreadPool();
		_threadpool.setKeepAliveTime(THREAD_LIFE, TimeUnit.SECONDS);
		_thread = new Thread(this, "CCNNetworkManager");
		_thread.start();
	}
	
	/**
	 * Shutdown the connection to ccnd and all threads associated with this network manager
	 */
	public void shutdown() {
		Log.info("Shutdown requested");
		_run = false;
		if (_periodicTimer != null)
			_periodicTimer.cancel();
		_selector.wakeup();
		try {
			setTap(null);
			_channel.close();
		} catch (IOException io) {
			// Ignore since we're shutting down
		}
	}
	
	/**
	 * Turns on writing of all packets to a file for test/debug
	 * Overrides any previous setTap or environment/property setting.
	 * Pass null to turn off tap.
	 * @param pathname name of tap file
	 */
	public void setTap(String pathname) throws IOException {
		// Turn off any active tap
		if (null != _tapStreamOut) {
			FileOutputStream closingStream = _tapStreamOut;
			_tapStreamOut = null;
			closingStream.close();
		}
		if (null != _tapStreamIn) {
			FileOutputStream closingStream = _tapStreamIn;
			_tapStreamIn = null;
			closingStream.close();
		}

		if (pathname != null && pathname.length() > 0) {
			_tapStreamOut = new FileOutputStream(new File(pathname + "_out"));
			_tapStreamIn = new FileOutputStream(new File(pathname + "_in"));
			if( Log.isLoggable(Level.INFO) )
				Log.info("Tap writing to {0}", pathname);
		}
	}
	
	/**
	 * Get the CCN Name of the 'ccnd' we're connected to.
	 * 
	 * @return the CCN Name of the 'ccnd' this CCNNetworkManager is connected to.
	 * @throws IOException 
	 */
	public PublisherPublicKeyDigest getCCNDId() throws IOException {
		/*
		 *  Now arrange to have the ccndId read.  We can't do that here because we need
		 *  to return back to the create before we know we get the answer back.  We can
		 *  cause the prefix registration to wait.
		 */
		PublisherPublicKeyDigest sentID = null;
		boolean doFetch = false;
		
		synchronized (_idSyncer) {
			if (null == _ccndId) {
				doFetch = true;
			} else {
				return _ccndId;
			}
		}
		
		if (doFetch) {
			sentID = fetchCCNDId(this, _keyManager);
			if (null == sentID) {
				Log.severe("getCCNDId: call to fetchCCNDId returned null.");
				return null;
			}
		}
		synchronized (_idSyncer) {
			_ccndId = sentID;
			return _ccndId;
		}
	}
	
	/**
	 * 
	 */
	public KeyManager getKeyManager() {
		return _keyManager;
	}
	
	/**
	 * 
	 */
	public void setKeyManager(KeyManager manager) {
		_keyManager = manager;
	}
	
	/**
	 * Write content to ccnd
	 * 
	 * @param co the content
	 * @return the same content that was passed into the method
	 * 
	 * TODO - code doesn't actually throw either of these exceptions but need to fix upper
	 * level code to compensate when they are removed.
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public ContentObject put(ContentObject co) throws IOException, InterruptedException {	
		try {
			write(co);
		} catch (ContentEncodingException e) {
			Log.warning("Exception in lowest-level put for object {1}! {1}", co.name(), e);
		}
		return co;
	}
	
	/**
	 * get content matching an interest from ccnd. Expresses an interest, waits for ccnd to
	 * return matching the data, then removes the interest and returns the data to the caller.
	 * 
	 * TODO should probably handle InterruptedException at this level instead of throwing it to
	 * 		higher levels
	 * 
	 * @param interest	the interest
	 * @param timeout	time to wait for return in ms
	 * @return	ContentObject or null on timeout
	 * @throws IOException 	on incorrect interest data
	 * @throws InterruptedException	if process is interrupted during wait
	 */
	public ContentObject get(Interest interest, long timeout) throws IOException, InterruptedException {
		if( Log.isLoggable(Level.FINE) )
			Log.fine("get: {0}", interest);
		InterestRegistration reg = new InterestRegistration(this, interest, null, null);
		expressInterest(reg);
		if( Log.isLoggable(Level.FINEST) )
			Log.finest("blocking for {0} on {1}", interest.name(), reg.sema);
		// Await data to consume the interest
		if (timeout == SystemConfiguration.NO_TIMEOUT)
			reg.sema.acquire(); // currently no timeouts
		else
			reg.sema.tryAcquire(timeout, TimeUnit.MILLISECONDS);
		if( Log.isLoggable(Level.FINEST) )
			Log.finest("unblocked for {0} on {1}", interest.name(), reg.sema);
		// Typically the main processing thread will have registered the interest
		// which must be undone here, but no harm if never registered
		unregisterInterest(reg);
		return reg.popData(); 
	}

	/**
	 * We express interests to the ccnd and register them within the network manager
	 * 
	 * @param caller 	must not be null
	 * @param interest 	the interest
	 * @param callbackListener	listener to callback on receipt of data
	 * @throws IOException on incorrect interest
	 */
	public void expressInterest(
			Object caller,
			Interest interest,
			CCNInterestListener callbackListener) throws IOException {
		// TODO - use of "caller" should be reviewed - don't believe this is currently serving
		// serving any useful purpose.
		if (null == callbackListener) {
			throw new NullPointerException("expressInterest: callbackListener cannot be null");
		}		
	
		if( Log.isLoggable(Level.FINE) )
			Log.fine("expressInterest: {0}", interest);
		InterestRegistration reg = new InterestRegistration(this, interest, callbackListener, caller);
		expressInterest(reg);
	}
	
	private void expressInterest(InterestRegistration reg) throws IOException {
		try {
			registerInterest(reg);
			write(reg.interest);
		} catch (ContentEncodingException e) {
			unregisterInterest(reg);
			throw e;
		}
	}
	
	/**
	 * Cancel this query with all the repositories we sent
	 * it to.
	 * 
	 * @param caller 	must not be null
	 * @param interest
	 * @param callbackListener
	 */
	public void cancelInterest(Object caller, Interest interest, CCNInterestListener callbackListener) {
		if (null == callbackListener) {
			// TODO - use of "caller" should be reviewed - don't believe this is currently serving
			// serving any useful purpose.
			throw new NullPointerException("cancelInterest: callbackListener cannot be null");
		}
	
		if( Log.isLoggable(Level.FINE) )
			Log.fine("cancelInterest: {0}", interest.name());
		// Remove interest from repeated presentation to the network.
		unregisterInterest(caller, interest, callbackListener);
	}

	/**
	 * Register a standing interest filter with callback to receive any 
	 * matching interests seen. Any interests whose prefix completely matches "filter" will
	 * be delivered to the listener
	 *
	 * @param caller 	must not be null
	 * @param filter	ContentName containing prefix of interests to match
	 * @param callbackListener a CCNFilterListener
	 * @throws IOException 
	 */
	public void setInterestFilter(Object caller, ContentName filter, CCNFilterListener callbackListener) throws IOException {
		if( Log.isLoggable(Level.FINE) )
			Log.fine("setInterestFilter: {0}", filter);
		if ((null == _keyManager) || (!_keyManager.initialized() || (null == _keyManager.getDefaultKeyID()))) {
			Log.warning("Cannot set interest filter -- key manager not ready!");
			throw new IOException("Cannot set interest filter -- key manager not ready!");
		}
		// TODO - use of "caller" should be reviewed - don't believe this is currently serving
		// serving any useful purpose.
		setupTimers();
		ForwardingEntry entry = null;
		if (_usePrefixReg) {
			try {
				if (null == _prefixMgr) {
					_prefixMgr = new PrefixRegistrationManager(this);
				}
				entry = _prefixMgr.selfRegisterPrefix(filter);
			} catch (CCNDaemonException e) {
				Log.warning("setInterestFilter: unexpected CCNDaemonException: " + e.getMessage());
				throw new IOException(e.getMessage());
			}
		}
		if (null != entry) {
			// FIXME: The lifetime of a prefix is returned in seconds, not milliseconds.  The refresh code needs
			// to understand this.  This isn't a problem for now because the lifetime we request when we register a 
			// prefix we use Integer.MAX_VALUE as the requested lifetime.
			if( Log.isLoggable(Level.FINE) )
				Log.fine("setInterestFilter: entry.lifetime: " + entry.getLifetime() + " entry.faceID: " + entry.getFaceID());
		}
		Filter newOne = new Filter(this, filter, callbackListener, caller, entry);
		synchronized (_myFilters) {
			_myFilters.add(filter, newOne);
		}
	}

	/**
	 * Unregister a standing interest filter
	 *
	 * @param caller 	must not be null
	 * @param filter	currently registered filter
	 * @param callbackListener	the CCNFilterListener registered to it
	 */
	public void cancelInterestFilter(Object caller, ContentName filter, CCNFilterListener callbackListener) {
		// TODO - use of "caller" should be reviewed - don't believe this is currently serving
		// serving any useful purpose.
		if( Log.isLoggable(Level.FINE) )
			Log.fine("cancelInterestFilter: {0}", filter);
		Filter newOne = new Filter(this, filter, callbackListener, caller, null);
		synchronized (_myFilters) {
			Entry<Filter> found = _myFilters.remove(filter, newOne);
			if (null != found) {
				Filter thisOne = found.value();
				thisOne.invalidate();
				ForwardingEntry entry = thisOne.getEntry();
				if (_usePrefixReg) {
					if (!entry.getPrefixName().equals(filter)) {
						Log.severe("cancelInterestFilter filter name {0} does not match recorded name {1}", filter, entry.getPrefixName());
					}
					try {
						if (null == _prefixMgr) {
							_prefixMgr = new PrefixRegistrationManager(this);
						}
						_prefixMgr.unRegisterPrefix(filter, entry.getFaceID());
					} catch (CCNDaemonException e) {
						Log.warning("cancelInterestFilter failed with CCNDaemonException: " + e.getMessage());
					}
				}
			}
		}
	}
	
	protected void write(ContentObject data) throws ContentEncodingException {
		WirePacket packet = new WirePacket(data);
		writeInner(packet);
		if( Log.isLoggable(Level.FINEST) )
			Log.finest("Wrote content object: {0}", data.name());
	}

	protected void write(Interest interest) throws ContentEncodingException {
		WirePacket packet = new WirePacket(interest);
		writeInner(packet);
	}

	// DKS TODO unthrown exception
	private void writeInner(WirePacket packet) throws ContentEncodingException {
		try {
			byte[] bytes = packet.encode();
			ByteBuffer datagram = ByteBuffer.wrap(bytes);
			synchronized (_channel) {
				int result = _channel.write(datagram);
				if( Log.isLoggable(Level.FINEST) )
					Log.finest("Wrote datagram (" + datagram.position() + " bytes, result " + result + ")");
				if (null != _tapStreamOut) {
					try {
						_tapStreamOut.write(bytes);
					} catch (IOException io) {
						Log.warning("Unable to write packet to tap stream for debugging");
					}
				}
			}
		} catch (IOException io) {
			// We do not see errors on send typically even if 
			// agent is gone, so log each but do not track
			Log.warning("Error sending packet: " + io.toString());
		}
	}


	/**
	 * Pass things on to the network stack.
	 */
	private InterestRegistration registerInterest(InterestRegistration reg) {
		// Add to standing interests table
		setupTimers();
		if( Log.isLoggable(Level.FINEST) )
			Log.finest("registerInterest for {0}, and obj is " + _myInterests.hashCode(), reg.interest.name());
		synchronized (_myInterests) {
			_myInterests.add(reg.interest, reg);
		}
		return reg;
	}
	
	private void unregisterInterest(Object caller, Interest interest, CCNInterestListener callbackListener) {
		InterestRegistration reg = new InterestRegistration(this, interest, callbackListener, caller);
		unregisterInterest(reg);
	}
	
	/**
	 * @param reg - registration to unregister
	 * 
	 * Important Note: This can indirectly need to obtain the lock for "reg" with the lock on
	 * "myInterests" held.  Therefore it can't be called when holding the lock for "reg".
	 */
	private void unregisterInterest(InterestRegistration reg) {
		synchronized (_myInterests) {
			Entry<InterestRegistration> found = _myInterests.remove(reg.interest, reg);
			if (null != found) {
				found.value().invalidate();
			}
		}		
	}

	/**
	 * Thread method: this thread will handle reading datagrams and 
	 * the periodic re-expressing of standing interests
	 */
	public void run() {
		if (! _run) {
			Log.warning("CCNNetworkManager run() called after shutdown");
			return;
		}
		// Allocate datagram buffer: want to wrap array to ensure backed by
		// array to permit decoding
		byte[] buffer = new byte[MAX_PAYLOAD];
		ByteBuffer datagram = ByteBuffer.wrap(buffer);
		WirePacket packet = new WirePacket();
		if( Log.isLoggable(Level.INFO) )
			Log.info("CCNNetworkManager processing thread started for port: " + _localPort);
		while (_run) {
			try {
				
				//--------------------------------- Read and decode
				try {
					if (_selector.select(SOCKET_TIMEOUT) != 0) {
						// Note: we're selecting on only one channel to get
						// the ability to use wakeup, so there is no need to 
						// inspect the selected-key set
						datagram.clear(); // make ready for new read
						synchronized (_channel) {
							_channel.read(datagram); // queue readers and writers
						}
						if( Log.isLoggable(Level.FINEST) )
							Log.finest("Read datagram (" + datagram.position() + " bytes) for port: " + _localPort);
						_selector.selectedKeys().clear();
						if (null != _error) {
							if( Log.isLoggable(Level.INFO) )
								Log.info("Receive error cleared for port: " + _localPort);
							_error = null;
						}
						datagram.flip(); // make ready to decode
						if (null != _tapStreamIn) {
							byte [] b = new byte[datagram.limit()];
							datagram.get(b);
							_tapStreamIn.write(b);
							datagram.rewind();
						}
						packet.clear();
						packet.decode(datagram);
					} else {
						// This was a timeout or wakeup, no data
						packet.clear();
						if (!_run) {
							// exit immediately if wakeup for shutdown
							break;
						}
						if (!_channel.isConnected()) {
							/*
							 * This is the case where we noticed that the connect to ccnd went away.  We
							 * try to reconnect, and if successful, we need to re-register our collection
							 * of prefix registrations.
							 */
							_channel.connect(new InetSocketAddress(_host, _port));
							if (_channel.isConnected()) {
								_selector = Selector.open();
								_channel.register(_selector, SelectionKey.OP_READ);
								_localPort = _channel.socket().getLocalPort();
								_faceID = null;
								if( Log.isLoggable(Level.INFO) )
									Log.info("Reconnecting to CCN agent at " + _host + ":" + _port + "on local port" + _localPort);
							}
						}
					}
				} catch (IOException io) {
					// We see IOException on receive every time if agent is gone
					// so track it to log only start and end of outages
					if (null == _error) {
						if( Log.isLoggable(Level.INFO) )
							Log.info("Unable to receive from agent: is it still running? Port: " + _localPort);
					}
					_error = io;
					packet.clear();
				}
                if (!_run) {
                    // exit immediately if wakeup for shutdown
                    break;
                }
                
                // If we got a data packet, hand it back to all the interested
				// parties (registered interests and getters).
				//--------------------------------- Process data from net (if any) 
				for (ContentObject co : packet.data()) {
					if( Log.isLoggable(Level.FINER) )
						Log.finer("Data from net for port: " + _localPort + " {0}", co.name());
					//	SystemConfiguration.logObject("Data from net:", co);
					
					deliverData(co);
					// External data never goes back to network, never held onto here
					// External data never has a thread waiting, so no need to release sema
				}

				//--------------------------------- Process interests from net (if any)
				for (Interest interest : packet.interests()) {
					if( Log.isLoggable(Level.FINEST) )
						Log.finest("Interest from net for port: " + _localPort + " {0}", interest);
					InterestRegistration oInterest = new InterestRegistration(this, interest, null, null);
					deliverInterest(oInterest);
					// External interests never go back to network
				} // for interests
				
			} catch (Exception ex) {
				Log.severe("Processing thread failure (UNKNOWN): " + ex.getMessage() + " for port: " + _localPort);
                Log.warningStackTrace(ex);
			}
		}
		
		_threadpool.shutdown();
		Log.info("Shutdown complete for port: " + _localPort);
	}

	/**
	 * Internal delivery of interests to pending filter listeners
	 * @param ireg
	 */
	protected void deliverInterest(InterestRegistration ireg) {
		// Call any listeners with matching filters
		synchronized (_myFilters) {
			for (Filter filter : _myFilters.getValues(ireg.interest.name())) {
				if (filter.owner != ireg.owner) {
					if( Log.isLoggable(Level.FINER) )
						Log.finer("Schedule delivery for interest: {0}", ireg.interest);
					filter.add(ireg.interest);
					_threadpool.execute(filter);
				}
			}
		}
	}

	/**
	 *  Deliver data to blocked getters and registered interests
	 * @param co
	 */
	protected void deliverData(ContentObject co) {
		synchronized (_myInterests) {
			for (InterestRegistration ireg : _myInterests.getValues(co)) {
				if (ireg.add(co)) { // this is a copy of the data
					_threadpool.execute(ireg);
				}
			}
		}
	}
	
	
	protected PublisherPublicKeyDigest fetchCCNDId(CCNNetworkManager mgr, KeyManager keyManager) throws IOException {
			try {
				Interest interested = new Interest(CCNDaemonProfile.ping);
				interested.nonce(Interest.generateNonce());
				interested.scope(1);
				ContentObject contented = mgr.get(interested, 500);
				if (null == contented) {
					String msg = ("fetchCCNDId: Fetch of content from ping uri failed due to timeout.");
					Log.severe(msg);
					throw new IOException(msg);
				}
				PublisherPublicKeyDigest sentID = contented.signedInfo().getPublisherKeyID();
				
				// TODO: This needs to be fixed once the KeyRepository is fixed to provide a KeyManager
				if (null != keyManager) {
					ContentVerifier verifyer = new ContentObject.SimpleVerifier(sentID, keyManager);
					if (!verifyer.verify(contented)) {
						String msg = ("fetchCCNDId: Fetch of content reply from ping failed to verify.");
						Log.severe(msg);
						throw new IOException(msg);
					}
				} else {
					Log.severe("fetchCCNDId: do not have a KeyManager. Cannot verify ccndID.");
					return null;
				}
				return sentID;
			} catch (InterruptedException e) {
				Log.warningStackTrace(e);
				throw new IOException(e.getMessage());
			} catch (MalformedContentNameStringException e) {
				String reason = e.getMessage();
				Log.warningStackTrace(e);
				String msg = ("fetchCCNDId: Unexpected MalformedContentNameStringException in call creating: " + CCNDaemonProfile.ping + " reason: " + reason);
				Log.severe(msg);
				throw new IOException(msg);
			} catch (IOException e) {
				String reason = e.getMessage();
				Log.warningStackTrace(e);
				String msg = ("fetchCCNDId: Unexpected IOException in call getting ping Interest reason: " + reason);
				Log.severe(msg);
				throw new IOException(msg);
			}
	} /* PublisherPublicKeyDigest fetchCCNDId() */

	
}


