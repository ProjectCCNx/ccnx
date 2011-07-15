/*
 * Part of the CCNx Java Library.
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

package org.ccnx.ccn.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNStats.CCNEnumStats;
import org.ccnx.ccn.impl.CCNStats.CCNEnumStats.IStatsEnum;
import org.ccnx.ccn.impl.InterestTable.Entry;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.ccnd.CCNDaemonException;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager.ForwardingEntry;
import org.ccnx.ccn.profiles.context.ServiceDiscoveryProfile;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.WirePacket;


/**
 * The low level interface to ccnd. Connects to a ccnd. For UDP it must maintain the connection by 
 * sending heartbeats to it.  Other functions include reading and writing interests and content
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
	public static final int PERIOD = 2000; // period for occasional ops in ms.
	public static final int MAX_PERIOD = PERIOD * 8;
	public static final String KEEPALIVE_NAME = "/HereIAm";
	public static final int THREAD_LIFE = 8;	// in seconds
	public static final int MAX_PAYLOAD = 8800; // number of bytes in UDP payload
	
	// These are to make log messages from CCNNetworkManager intelligable when
	// there are multiple managers running
	protected final static AtomicInteger _managerIdCount = new AtomicInteger(0);
	protected final int _managerId;
	protected final String _managerIdString;
	
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

	protected CCNNetworkChannel _channel = null; // for use by run thread only!
	protected boolean _run = true;

	// protected ContentObject _keepalive; 
	protected FileOutputStream _tapStreamOut = null;
	protected FileOutputStream _tapStreamIn = null;
	protected long _lastHeartbeat = 0;
	protected int _port = DEFAULT_AGENT_PORT;
	protected String _host = DEFAULT_AGENT_HOST;
	protected NetworkProtocol _protocol = SystemConfiguration.AGENT_PROTOCOL;

	// For handling protocol to speak to ccnd, must have keys
	protected KeyManager _keyManager;

	// Tables of interests/filters: users must synchronize on collection
	protected InterestTable<InterestRegistration> _myInterests = new InterestTable<InterestRegistration>();
	protected InterestTable<Filter> _myFilters = new InterestTable<Filter>();
	public static final boolean DEFAULT_PREFIX_REG = true;
	protected boolean _usePrefixReg = DEFAULT_PREFIX_REG;
	protected PrefixRegistrationManager _prefixMgr = null;
	protected Timer _periodicTimer = null;
	protected Object _timersSetupLock = new Object();
	protected Boolean _timersSetup = false;
	protected TreeMap<ContentName, RegisteredPrefix> _registeredPrefixes = new TreeMap<ContentName, RegisteredPrefix>();

	/**
	 * Keep track of prefixes that are actually registered with ccnd (as opposed to Filters used
	 * to dispatch interests). There may be several filters for each registered prefix.
	 */
	public class RegisteredPrefix implements CCNInterestListener {
		private int _refCount = 1;
		private ForwardingEntry _forwarding = null;
		// FIXME: The lifetime of a prefix is returned in seconds, not milliseconds.  The refresh code needs
		// to understand this.  This isn't a problem for now because the lifetime we request when we register a 
		// prefix we use Integer.MAX_VALUE as the requested lifetime.
		private long _lifetime = -1; // in seconds
		private long _nextRefresh = -1;
		private boolean _closing = false;		// Flags in process of closing
		private boolean _wasClosing = false;	// See below for reason for this
		private boolean _doRemove = true;		// To avoid removing just registered prefixes

		public RegisteredPrefix(ForwardingEntry forwarding) {
			_forwarding = forwarding;
			if (null != forwarding) {
				_lifetime = forwarding.getLifetime();
				_nextRefresh = System.currentTimeMillis() + (_lifetime / 2);
			}
		}

		/**
		 * Waiter for prefixes being deregistered. This is because we don't want to
		 * wait for the prefix to be deregistered normally, but if we try to re-register
		 * it we have to to avoid races.
		 */
		public Interest handleContent(ContentObject data, Interest interest) {
			synchronized (this) {
				_closing = false;	// We have to clear this, otherwise we could deadlock if setInterestFilter
									// grabs us after this
				notifyAll();
			}		
			// If setInterestFilter grabbed us right here, we would have cleared _closing (necessary to avoid
			// deadlocks) but we would have actually deregistered so setInterestFilter needs to know that. It can
			// because _wasClosing is still set.
			synchronized (_registeredPrefixes) {
				if (_doRemove)	// Avoid removing a just registered prefix from the map
					_registeredPrefixes.remove(_forwarding.getPrefixName());
			}
			return null;
		}
	}

	/**
	 * Do scheduled interest and registration refreshes
	 */
	private class PeriodicWriter extends TimerTask {
		// TODO Interest refresh time is supposed to "decay" over time but there are currently
		// unresolved problems with this.
		public void run() {	
            //this method needs to do a few things
            // - reopen connection to ccnd if down
            // - refresh interests
            // - refresh prefix registrations
            // - heartbeats

			boolean refreshError = false;			
			if (_protocol == NetworkProtocol.UDP) {
				if (!_channel.isConnected()) {
                    //we are not connected.  reconnect attempt is in the heartbeat function...
					_channel.heartbeat();
				}
			}

			if (!_channel.isConnected()) {
                //we tried to reconnect and failed, try again next loop
                Log.fine(Log.FAC_NETMANAGER, "Not Connected to ccnd, try again in {0}ms", CCNNetworkChannel.SOCKET_TIMEOUT);
                _lastHeartbeat = 0;
                if (_run)
                        _periodicTimer.schedule(new PeriodicWriter(), CCNNetworkChannel.SOCKET_TIMEOUT);
                return;
            }

            long ourTime = System.currentTimeMillis();
            long minInterestRefreshTime = PERIOD + ourTime;
            // Library.finest("Refreshing interests (size " + _myInterests.size() + ")");
				
			// Re-express interests that need to be re-expressed
			try {
				synchronized (_myInterests) {
					for (Entry<InterestRegistration> entry : _myInterests.values()) {
						InterestRegistration reg = entry.value();
						 // allow some slop for scheduling
                        if (ourTime + 20 > reg.nextRefresh) {
                                if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
                                        Log.finer(Log.FAC_NETMANAGER, "Refresh interest: {0}", reg.interest);
                                _lastHeartbeat = ourTime;
                                reg.nextRefresh = ourTime + reg.nextRefreshPeriod;
                                try {
                                    write(reg.interest);
                            } catch (NotYetConnectedException nyce) {
                                    refreshError = true;
                            }
                        }
						if (minInterestRefreshTime > reg.nextRefresh)
							minInterestRefreshTime = reg.nextRefresh;
					}
				}
				
			} catch (ContentEncodingException xmlex) {
                Log.severe(Log.FAC_NETMANAGER, "PeriodicWriter interest refresh thread failure (Malformed datagram): {0}", xmlex.getMessage());
                Log.warningStackTrace(xmlex);
                refreshError = true;
			}

			// Re-express prefix registrations that need to be re-expressed
            // FIXME: The lifetime of a prefix is returned in seconds, not milliseconds.  The refresh code needs
            // to understand this.  This isn't a problem for now because the lifetime we request when we register a
            // prefix we use Integer.MAX_VALUE as the requested lifetime.
            // FIXME: so lets not go around the loop doing nothing... for now.
            long minFilterRefreshTime = PERIOD + ourTime;
            if (false && _usePrefixReg) {
            	synchronized (_registeredPrefixes) {
                    for (ContentName prefix : _registeredPrefixes.keySet()) {
                    	RegisteredPrefix rp = _registeredPrefixes.get(prefix);
						if (null != rp._forwarding && rp._lifetime != -1 && rp._nextRefresh != -1) {
							if (ourTime > rp._nextRefresh) {
								if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
									Log.finer(Log.FAC_NETMANAGER, "Refresh registration: {0}", prefix);
								rp._nextRefresh = -1;
								try {
									ForwardingEntry forwarding = _prefixMgr.selfRegisterPrefix(prefix);
									if (null != forwarding) {
										rp._lifetime = forwarding.getLifetime();
//										filter.nextRefresh = new Date().getTime() + (filter.lifetime / 2);
										_lastHeartbeat = System.currentTimeMillis();
										rp._nextRefresh = _lastHeartbeat + (rp._lifetime / 2);
									}
									rp._forwarding = forwarding;

								} catch (CCNDaemonException e) {
									Log.warning(e.getMessage());
									// XXX - don't think this is right
									rp._forwarding = null;
									rp._lifetime = -1;
									rp._nextRefresh = -1;
									refreshError = true;
								}
							}
							if (minFilterRefreshTime > rp._nextRefresh)
								minFilterRefreshTime = rp._nextRefresh;
						}
						}	// for (Entry<Filter> entry: _myFilters.values())
				}	// synchronized (_myFilters)
			} // _usePrefixReg
        	
        	if (refreshError) {
                Log.warning(Log.FAC_NETMANAGER, "we have had an error when refreshing an interest or prefix registration...  do we need to reconnect to ccnd?");
        	}

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

			if (_protocol == NetworkProtocol.UDP) {

					//we haven't sent anything...  maybe need to send a heartbeat
				if ((currentTime - _lastHeartbeat) >= CCNNetworkChannel.HEARTBEAT_PERIOD) {
					_lastHeartbeat = currentTime;
					_channel.heartbeat();
				}				
	
				//now factor in heartbeat time
				long timeToHeartbeat = CCNNetworkChannel.HEARTBEAT_PERIOD - (currentTime - _lastHeartbeat);
				if (useMe > timeToHeartbeat)
					useMe = timeToHeartbeat;
			}

			if (useMe < 20) {
				useMe = 20;
			}
			if (_run)
				_periodicTimer.schedule(new PeriodicWriter(), useMe);
		} /* run */
	} /* private class PeriodicWriter extends TimerTask */
	
	/**
	 * First time startup of timing stuff after first registration
	 * We don't bother to "unstartup" if everything is deregistered
	 * @throws IOException 
	 */
	private void setupTimers() throws IOException {
		synchronized (_timersSetupLock) {
			if (!_timersSetup) {
				_timersSetup = true;
				_channel.init();
				if (_protocol == NetworkProtocol.UDP) {
					_channel.heartbeat();
					_lastHeartbeat = System.currentTimeMillis();
				}
				
				// Create timer for periodic behavior
				_periodicTimer = new Timer(true);
				_periodicTimer.schedule(new PeriodicWriter(), PERIOD);
			}
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
			// to be sure that on exit from invalidate() there will be.
			// Back off to avoid livelock  
			for (int i = 0; true; i = (2 * i + 1) & 63) {
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
				if (i == 0) {
					Thread.yield();
				} else {
					if (i > 3) Log.finer(Log.FAC_NETMANAGER, "invalidate spin {0}", i);
					try {
						Thread.sleep(i);
					} catch (InterruptedException e) {
					}
				}
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
				Log.warning(Log.FAC_NETMANAGER, "failed delivery: {0}", ex);
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
		protected long nextRefreshPeriod = SystemConfiguration.INTEREST_REEXPRESSION_DEFAULT;	// period to wait before refresh

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
			if (null == data) {
				// No data pending, this obj will consume interest
				this.data = obj; // we let this raise exception if obj == null
				return true;
			} else {
				// Data is already pending, this interest is already consumed, cannot add obj
				_stats.increment(StatsEnum.ContentObjectsIgnored);
				if (Log.isLoggable(Log.FAC_NETMANAGER, Level.WARNING))
					Log.warning(Log.FAC_NETMANAGER, "{0} is not handled - data already pending", obj.name());
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
						if (null != this.data && null != this.listener) {
							pending = this.data;
							this.data = null;
							listener = (CCNInterestListener)this.listener;
						}
					}
					// Call into client code without holding any library locks
					if (null != pending) {
						if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
							Log.finer(Log.FAC_NETMANAGER, "Interest callback (" + pending + " data) for: {0}", this.interest.name());

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
							if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
								Log.finer(Log.FAC_NETMANAGER, "Interest callback: updated interest to express: {0}", updatedInterest.name());
							// luckily we saved the listener
							// if we want to cancel this one before we get any data, we need to remember the
							// updated interest in the listener
							manager.expressInterest(this.owner, updatedInterest, listener);
						}

					} else {
						if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
							Log.finer(Log.FAC_NETMANAGER, "Interest callback skipped (no data) for: {0}", this.interest.name());
					}
				} else {
					synchronized (this) {
						if (null != this.sema) {
							if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
								Log.finer(Log.FAC_NETMANAGER, "Data consumes pending get: {0}", this.interest.name());
							// Waiting thread will pickup data -- wake it up
							// If this interest came from net or waiting thread timed out,
							// then no thread will be waiting but no harm is done
							if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
								Log.finest(Log.FAC_NETMANAGER, "releasing {0}", this.sema);
							this.sema.release();
						} 
					}
					if (null == this.sema) {
						// this is no longer valid registration
						if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
							Log.finer(Log.FAC_NETMANAGER, "Interest callback skipped (not valid) for: {0}", this.interest.name());
					}
				}
			} catch (Exception ex) {
				_stats.increment(StatsEnum.DeliverContentFailed);
				Log.warning(Log.FAC_NETMANAGER, "failed to deliver data: {0}", ex);
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
		protected Interest interest; // interest to be delivered
		// extra interests to be delivered: separating these allows avoidance of ArrayList obj in many cases
		protected ArrayList<Interest> extra = new ArrayList<Interest>(1);
		protected ContentName prefix = null;

		public Filter(CCNNetworkManager mgr, ContentName n, CCNFilterListener l, Object o) {
			prefix = n; listener = l; owner = o;
			manager = mgr;
		}

		public synchronized boolean add(Interest i) {
			if (null == interest) {
				interest = i;
				return true;
			} else {
				// Special case, more than 1 interest pending for delivery
				// Only 1 interest gets added at a time, but more than 1 
				// may arrive before a callback is dispatched
				if (null == extra) {
					extra = new ArrayList<Interest>(1);
				}
				extra.add(i);
				return false;
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
					if (null != this.interest && null != this.listener) {
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
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
						Log.finer(Log.FAC_NETMANAGER, "Filter callback for: {0}", prefix);
					listener.handleInterest(pending);
					// Now extra callbacks for additional interests
					if (null != pendingExtra) {
						int countExtra = 0;
						for (Interest pi : pendingExtra) {
							if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) ) {
								countExtra++;
								Log.finer(Log.FAC_NETMANAGER, "Filter callback (extra {0} of {1}) for: {2}", countExtra, pendingExtra.size(), prefix);
							}
							listener.handleInterest(pi);
						}
					}
				} else {
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
						Log.finer(Log.FAC_NETMANAGER, "Filter callback skipped (no interests) for: {0}", prefix);
				}
			} catch (RuntimeException ex) {
				_stats.increment(StatsEnum.DeliverInterestFailed);
				Log.warning(Log.FAC_NETMANAGER, "failed to deliver interest: {0}", ex);
				Log.warningStackTrace(ex);
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
					Log.severe(Log.FAC_NETMANAGER, "CCNDIdGetter: call to fetchCCNDId returned null.");
				}
				synchronized(_idSyncer) {
					_ccndId = sentID;
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO) )
						Log.info(Log.FAC_NETMANAGER, "CCNDIdGetter: ccndId {0}", ContentName.componentPrintURI(sentID.digest()));
				}
			} /* null == _ccndId */
		} /* run() */

	} /* private class CCNDIdGetter implements Runnable */

	/**
	 * The constructor. Attempts to connect to a ccnd at the currently specified port number
	 * @throws IOException if the port is invalid
	 */
	public CCNNetworkManager(KeyManager keyManager) throws IOException {
		_managerId = _managerIdCount.incrementAndGet();
		_managerIdString = "NetworkManager " + _managerId + ": ";
		
		if (null == keyManager) {
			// Unless someone gives us one later, we won't be able to register filters. Log this.
			if( Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO) )
				Log.info(Log.FAC_NETMANAGER, formatMessage("CCNNetworkManager: being created with null KeyManager. Must set KeyManager later to be able to register filters."));
		}

		_keyManager = keyManager;

		// Determine port at which to contact agent
		String portval = System.getProperty(PROP_AGENT_PORT);
		if (null != portval) {
			try {
				_port = new Integer(portval);
			} catch (Exception ex) {
				throw new IOException(formatMessage("Invalid port '" + portval + "' specified in " + PROP_AGENT_PORT));
			}
			Log.warning(Log.FAC_NETMANAGER, formatMessage("Non-standard CCN agent port " + _port + " per property " + PROP_AGENT_PORT));
		}
		String hostval = System.getProperty(PROP_AGENT_HOST);
		if (null != hostval && hostval.length() > 0) {
			_host = hostval;
			Log.warning(Log.FAC_NETMANAGER, formatMessage("Non-standard CCN agent host " + _host + " per property " + PROP_AGENT_HOST));
		}
		
		_protocol = SystemConfiguration.AGENT_PROTOCOL;

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO) )
			Log.info(Log.FAC_NETMANAGER, formatMessage("Contacting CCN agent at " + _host + ":" + _port));

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
		
		_channel = new CCNNetworkChannel(_host, _port, _protocol, _tapStreamIn);
		_ccndId = null;
		_channel.open();
		
		// Create callback threadpool and main processing thread
		_threadpool = (ThreadPoolExecutor)Executors.newCachedThreadPool();
		_threadpool.setKeepAliveTime(THREAD_LIFE, TimeUnit.SECONDS);
		_threadpool.setMaximumPoolSize(SystemConfiguration.MAX_DISPATCH_THREADS);
		_thread = new Thread(this, "CCNNetworkManager " + _managerId);
		_thread.start();
	}

	/**
	 * Shutdown the connection to ccnd and all threads associated with this network manager
	 */
	public void shutdown() {
		Log.info(Log.FAC_NETMANAGER, formatMessage("Shutdown requested"));
//		Thread.dumpStack();
		
		_run = false;
		if (_periodicTimer != null)
			_periodicTimer.cancel();
		if (_thread != null)
			_thread.interrupt();
		if (null != _channel) {
			try {
				setTap(null);
			} catch (IOException io) {
				// Ignore since we're shutting down
			}
			
			try {
				_channel.close();
			} catch (IOException io) {
				// Ignore since we're shutting down
			}

		}
	}
	
	@Override
	protected void finalize() throws Throwable {
		try {
			if (_run) {
				Log.warning(Log.FAC_NETMANAGER, formatMessage("Shutdown from finalize"));
			}
			shutdown();
		} finally {
			super.finalize();
		}
	}

	/**
	 * Get the protocol this network manager is using
	 * @return the protocol
	 */
	public NetworkProtocol getProtocol() {
		return _protocol;
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
			if( Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO) )
				Log.info(Log.FAC_NETMANAGER, formatMessage("Tap writing to {0}"), pathname);
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
				Log.severe(Log.FAC_NETMANAGER, formatMessage("getCCNDId: call to fetchCCNDId returned null."));
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
		_stats.increment(StatsEnum.Puts);

		try {
			write(co);
		} catch (ContentEncodingException e) {
			Log.warning(Log.FAC_NETMANAGER, formatMessage("Exception in lowest-level put for object {0}! {1}"), co.name(), e);
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
		_stats.increment(StatsEnum.Gets);

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("get: {0} with timeout: {1}"), interest, timeout);
		InterestRegistration reg = new InterestRegistration(this, interest, null, null);
		expressInterest(reg);
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
			Log.finest(Log.FAC_NETMANAGER, formatMessage("blocking for {0} on {1}"), interest.name(), reg.sema);
		// Await data to consume the interest
		if (timeout == SystemConfiguration.NO_TIMEOUT)
			reg.sema.acquire(); // currently no timeouts
		else
			reg.sema.tryAcquire(timeout, TimeUnit.MILLISECONDS);
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
			Log.finest(Log.FAC_NETMANAGER, formatMessage("unblocked for {0} on {1}"), interest.name(), reg.sema);
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
			throw new NullPointerException(formatMessage("expressInterest: callbackListener cannot be null"));
		}		

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("expressInterest: {0}"), interest);
		InterestRegistration reg = new InterestRegistration(this, interest, callbackListener, caller);
		expressInterest(reg);
	}

	private void expressInterest(InterestRegistration reg) throws IOException {
		_stats.increment(StatsEnum.ExpressInterest);

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
			throw new NullPointerException(formatMessage("cancelInterest: callbackListener cannot be null"));
		}
		_stats.increment(StatsEnum.CancelInterest);

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("cancelInterest: {0}"), interest.name());
		// Remove interest from repeated presentation to the network.
		unregisterInterest(caller, interest, callbackListener);
	}

	/**
	 * Register a standing interest filter with callback to receive any 
	 * matching interests seen. Any interests whose prefix completely matches "filter" will
	 * be delivered to the listener. Also if this filter matches no currently registered
	 * prefixes, register its prefix with ccnd.
	 *
	 * @param caller 	must not be null
	 * @param filter	ContentName containing prefix of interests to match
	 * @param callbackListener a CCNFilterListener
	 * @throws IOException 
	 */
	public void setInterestFilter(Object caller, ContentName filter, CCNFilterListener callbackListener) throws IOException {
		setInterestFilter(caller, filter, callbackListener, null);
	}


	/**
	 * Register a standing interest filter with callback to receive any 
	 * matching interests seen. Any interests whose prefix completely matches "filter" will
	 * be delivered to the listener. Also if this filter matches no currently registered
	 * prefixes, register its prefix with ccnd.
	 *
	 * @param caller 	must not be null
	 * @param filter	ContentName containing prefix of interests to match
	 * @param callbackListener a CCNFilterListener
	 * @param registrationFlags to use for this registration.
	 * @throws IOException 
	 */
	public void setInterestFilter(Object caller, ContentName filter, CCNFilterListener callbackListener,
			Integer registrationFlags) throws IOException {

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("setInterestFilter: {0}"), filter);
		if ((null == _keyManager) || (!_keyManager.initialized() || (null == _keyManager.getDefaultKeyID()))) {
			Log.warning(Log.FAC_NETMANAGER, formatMessage("Cannot set interest filter -- key manager not ready!"));
			throw new IOException(formatMessage("Cannot set interest filter -- key manager not ready!"));
		}
		// TODO - use of "caller" should be reviewed - don't believe this is currently serving
		// serving any useful purpose.
		setupTimers();
		if (_usePrefixReg) {
			try {
				if (null == _prefixMgr) {
					_prefixMgr = new PrefixRegistrationManager(this);
				}
				synchronized(_registeredPrefixes) {
					RegisteredPrefix oldPrefix = getRegisteredPrefix(filter);
					if (null != oldPrefix) {
						synchronized (oldPrefix) {
							while (oldPrefix._closing) {
								try {
									oldPrefix.wait();
								} catch (InterruptedException e) {}
							}
							if (oldPrefix._wasClosing) {
								_registeredPrefixes.remove(filter);
								registerPrefix(filter, registrationFlags);
								oldPrefix._doRemove = false;
							} else {
								oldPrefix._refCount++;
							}
						}
					} else {
						registerPrefix(filter, registrationFlags);
					}
				}
			} catch (CCNDaemonException e) {
				Log.warning(Log.FAC_NETMANAGER, formatMessage("setInterestFilter: unexpected CCNDaemonException: " + e.getMessage()));
				throw new IOException(e.getMessage());
			}
		}

		Filter newOne = new Filter(this, filter, callbackListener, caller);
		synchronized (_myFilters) {
			_myFilters.add(filter, newOne);
		}
	}
	
	/**
	 * Get current list of prefixes that are actually registered on the face associated with this
	 * netmanager
	 * 
	 * @return the list of prefixes as an ArrayList of ContentNames
	 */
	public ArrayList<ContentName> getRegisteredPrefixes() {
		ArrayList<ContentName> prefixes = new ArrayList<ContentName>();
		synchronized (_registeredPrefixes) {
			for (ContentName name : _registeredPrefixes.keySet()) {
				prefixes.add(name);
			}
		}
		return prefixes;
	}

	/**
	 * Must be called with _registeredPrefixes locked
	 * 
	 * @param filter
	 * @param registrationFlags
	 * @throws CCNDaemonException
	 */
    private void registerPrefix(ContentName filter, Integer registrationFlags) throws CCNDaemonException {
    	ForwardingEntry entry;
    	if (null == registrationFlags) {
			entry = _prefixMgr.selfRegisterPrefix(filter);
		} else {
			entry = _prefixMgr.selfRegisterPrefix(filter, null, registrationFlags, Integer.MAX_VALUE);
		}
		RegisteredPrefix newPrefix = new RegisteredPrefix(entry);
		_registeredPrefixes.put(filter, newPrefix);
		// FIXME: The lifetime of a prefix is returned in seconds, not milliseconds.  The refresh code needs
		// to understand this.  This isn't a problem for now because the lifetime we request when we register a 
		// prefix we use Integer.MAX_VALUE as the requested lifetime.
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("setInterestFilter: entry.lifetime: " + entry.getLifetime() + " entry.faceID: " + entry.getFaceID()));
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
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("cancelInterestFilter: {0}"), filter);
		Filter newOne = new Filter(this, filter, callbackListener, caller);
		Entry<Filter> found = null;
		synchronized (_myFilters) {
			found = _myFilters.remove(filter, newOne);
		}
		if (null != found) {
			Filter thisOne = found.value();
			thisOne.invalidate();
			if (_usePrefixReg) {
				// Deregister it with ccnd only if the refCount would go to 0
				synchronized (_registeredPrefixes) {
					RegisteredPrefix prefix = getRegisteredPrefix(filter);
					if (null != prefix) {
						synchronized (prefix) {
							if (prefix._refCount <= 1) {
								ForwardingEntry entry = prefix._forwarding;
								// Since we are piggybacking registration entries we can legitimately have a "last" registration entry on a prefix that had
								// been piggybacked on a higher registration earlier so the entries name would not match the filter.
								//
								//if (!entry.getPrefixName().equals(filter)) {
								//	Log.severe(Log.FAC_NETMANAGER, "cancelInterestFilter filter name {0} does not match recorded name {1}", filter, entry.getPrefixName());
								//}
								try {
									if (null == _prefixMgr) {
										_prefixMgr = new PrefixRegistrationManager(this);
									}
									prefix._closing = true;
									prefix._wasClosing = true;
									_prefixMgr.unRegisterPrefix(filter, prefix, entry.getFaceID());
								} catch (CCNDaemonException e) {
									Log.warning(Log.FAC_NETMANAGER, formatMessage("cancelInterestFilter failed with CCNDaemonException: " + e.getMessage()));
								}
							} else
								prefix._refCount--;
						}
					}
				}
			}
		}
	}

	/**
	 * Merge prefixes so we only add a new one when it doesn't have a
	 * common ancestor already registered.
	 * 
	 * Must be called with _registeredPrefixes locked
	 * 
	 * We decided that if we are registering a prefix that already has another prefix that
	 * is an descendant of it registered, we won't bother to now deregister that prefix because
	 * it would be complicated to do that and doesn't hurt anything.
	 * 
	 * Notes on efficiency: First of all I'm not sure how important efficiency is in this routine
	 * because it may not be too common to have many different prefixes registered. Currently we search
	 * all prefixes until we see one that is past the one we want to register before deciding there are
	 * none that encapsulate it. There may be a more efficient way to code this that is still correct but
	 * I haven't come up with it.
	 * 
	 * @param prefix
	 * @return prefix that incorporates or matches this one or null if none found
	 */
	protected RegisteredPrefix getRegisteredPrefix(ContentName prefix) {
		for (ContentName name : _registeredPrefixes.keySet()) {
			if (name.isPrefixOf(prefix))
				return _registeredPrefixes.get(name);
			if (name.compareTo(prefix) > 0)
				break;
		}
		return null;
	}

	protected void write(ContentObject data) throws ContentEncodingException {
		_stats.increment(StatsEnum.WriteObject);

		WirePacket packet = new WirePacket(data);
		writeInner(packet);
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
			Log.finest(Log.FAC_NETMANAGER, formatMessage("Wrote content object: {0}"), data.name());
	}

	/**
	 * Don't do this unless you know what you are doing!
	 * @param interest
	 * @throws ContentEncodingException
	 */
	public void write(Interest interest) throws ContentEncodingException {
		_stats.increment(StatsEnum.WriteInterest);
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
				if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
					Log.finest(Log.FAC_NETMANAGER, formatMessage("Wrote datagram (" + datagram.position() + " bytes, result " + result + ")"));
				
				if( result < bytes.length ) {
					_stats.increment(StatsEnum.WriteUnderflows);
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO) )
						Log.info(Log.FAC_NETMANAGER, 
								formatMessage("Wrote datagram {0} bytes to channel, but packet was {1} bytes"),
								result,
								bytes.length);
				}

				if (null != _tapStreamOut) {
					try {
						_tapStreamOut.write(bytes);
					} catch (IOException io) {
						Log.warning(Log.FAC_NETMANAGER, formatMessage("Unable to write packet to tap stream for debugging"));
					}
				}
			}
		} catch (IOException io) {
			_stats.increment(StatsEnum.WriteErrors);

			// We do not see errors on send typically even if 
			// agent is gone, so log each but do not track
			Log.warning(Log.FAC_NETMANAGER, formatMessage("Error sending packet: " + io.toString()));
		}
	}

	/**
	 * Pass things on to the network stack.
	 * @throws IOException 
	 */
	private InterestRegistration registerInterest(InterestRegistration reg) throws IOException {
		// Add to standing interests table
		setupTimers();
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
			Log.finest(Log.FAC_NETMANAGER, formatMessage("registerInterest for {0}, and obj is " + _myInterests.hashCode()), reg.interest.name());
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
	 * starts threads to dispatch data to handlers registered for it.
	 */
	public void run() {
		if (! _run) {
			Log.warning(Log.FAC_NETMANAGER, formatMessage("CCNNetworkManager run() called after shutdown"));
			return;
		}
		//WirePacket packet = new WirePacket();
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO) )
			Log.info(Log.FAC_NETMANAGER, formatMessage("CCNNetworkManager processing thread started for port: " + _port));
		while (_run) {
			try {
				boolean wasConnected = _channel.isConnected();
				XMLEncodable packet = _channel.getPacket();
				if (null == packet) {
					if (!wasConnected && _channel.isConnected())
						reregisterPrefixes();
					continue;
				}
				
				if (packet instanceof ContentObject) {
					_stats.increment(StatsEnum.ReceiveObject);
					ContentObject co = (ContentObject)packet;
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
						Log.finer(Log.FAC_NETMANAGER, formatMessage("Data from net for port: " + _port + " {0}"), co.name());

					//	SystemConfiguration.logObject("Data from net:", co);

					deliverData(co);
					// External data never goes back to network, never held onto here
					// External data never has a thread waiting, so no need to release sema
				} else if (packet instanceof Interest) {
					_stats.increment(StatsEnum.ReceiveInterest);
					Interest interest = (Interest)	packet;
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
						Log.finest(Log.FAC_NETMANAGER, formatMessage("Interest from net for port: " + _port + " {0}"), interest);
					InterestRegistration oInterest = new InterestRegistration(this, interest, null, null);
					deliverInterest(oInterest);
					// External interests never go back to network
				}  else { // for interests
					_stats.increment(StatsEnum.ReceiveUnknown);
				}
			} catch (Exception ex) {
				_stats.increment(StatsEnum.ReceiveErrors);
				Log.severe(Log.FAC_NETMANAGER, formatMessage("Processing thread failure (UNKNOWN): " + ex.getMessage() + " for port: " + _port));
                Log.warningStackTrace(ex);
			}
		}

		_threadpool.shutdown();
		Log.info(Log.FAC_NETMANAGER, formatMessage("Shutdown complete for port: " + _port));
	}

	/**
	 * Internal delivery of interests to pending filter listeners
	 * @param ireg
	 */
	protected void deliverInterest(InterestRegistration ireg) {
		_stats.increment(StatsEnum.DeliverInterest);

		// Call any listeners with matching filters
		synchronized (_myFilters) {
			for (Filter filter : _myFilters.getValues(ireg.interest.name())) {
				if (filter.owner != ireg.owner) {
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
						Log.finer(Log.FAC_NETMANAGER, formatMessage("Schedule delivery for interest: {0}"), ireg.interest);
					if (filter.add(ireg.interest)) {
						try {
							_threadpool.execute(filter);
						} catch (RejectedExecutionException ree) {
							// TODO - we should probably do something smarter here
							Log.severe(Log.FAC_NETMANAGER, formatMessage("Dispatch thread overflow!!"));
						}
					}
				}
			}
		}
	}

	/**
	 *  Deliver data to blocked getters and registered interests
	 * @param co
	 */
	protected void deliverData(ContentObject co) {
		_stats.increment(StatsEnum.DeliverContent);

		synchronized (_myInterests) {
			for (InterestRegistration ireg : _myInterests.getValues(co)) {
				if (ireg.add(co)) { // this is a copy of the data
					_stats.increment(StatsEnum.DeliverContentMatchingInterests);
					try {
						_threadpool.execute(ireg);
					} catch (RejectedExecutionException ree) {
						// TODO - we should probably do something smarter here
						Log.severe(Log.FAC_NETMANAGER, formatMessage("Dispatch thread overflow!!"));
					}				
				}
			}
		}
	}

	protected PublisherPublicKeyDigest fetchCCNDId(CCNNetworkManager mgr, KeyManager keyManager) throws IOException {
		try {
			ContentName serviceKeyName = new ContentName(ServiceDiscoveryProfile.localServiceName(ServiceDiscoveryProfile.CCND_SERVICE_NAME), KeyProfile.KEY_NAME_COMPONENT);
			Interest i = new Interest(serviceKeyName);
			i.scope(1);
			ContentObject c = mgr.get(i, SystemConfiguration.CCNDID_DISCOVERY_TIMEOUT);
			if (null == c) {
				String msg = formatMessage("fetchCCNDId: ccndID discovery failed due to timeout.");
				Log.severe(Log.FAC_NETMANAGER, msg);
				throw new IOException(msg);
			}
			PublisherPublicKeyDigest sentID = c.signedInfo().getPublisherKeyID();

			// TODO: This needs to be fixed once the KeyRepository is fixed to provide a KeyManager
			if (null != keyManager) {
				ContentVerifier v = new ContentObject.SimpleVerifier(sentID, keyManager);
				if (!v.verify(c)) {
					String msg = formatMessage("fetchCCNDId: ccndID discovery reply failed to verify.");
					Log.severe(Log.FAC_NETMANAGER, msg);
					throw new IOException(msg);
				}
			} else {
				Log.severe(Log.FAC_NETMANAGER, formatMessage("fetchCCNDId: do not have a KeyManager. Cannot verify ccndID."));
				return null;
			}
			return sentID;
		} catch (InterruptedException e) {
			Log.warningStackTrace(e);
			throw new IOException(e.getMessage());
		} catch (IOException e) {
			String reason = e.getMessage();
			Log.warningStackTrace(e);
			String msg = formatMessage("fetchCCNDId: Unexpected IOException in ccndID discovery Interest reason: " + reason);
			Log.severe(Log.FAC_NETMANAGER, msg);
			throw new IOException(msg);
		}
	} /* PublisherPublicKeyDigest fetchCCNDId() */

	/**
	 * Reregister all current prefixes with ccnd after ccnd goes down and then comes back up
	 * Since this is called internally from the network manager run loop, but it needs to make use of
	 * the network manager to correctly process the reregistration, we run the reregistration in a
	 * separate thread
	 * 
	 * @throws IOException 
	 */
	private void reregisterPrefixes() {
		new ReRegisterThread().start();
	}
		
	private class ReRegisterThread extends Thread {
		public void run() {
			TreeMap<ContentName, RegisteredPrefix> newPrefixes = new TreeMap<ContentName, RegisteredPrefix>();
			try {
				synchronized (_registeredPrefixes) {
					for (ContentName prefix : _registeredPrefixes.keySet()) {
						ForwardingEntry entry = _prefixMgr.selfRegisterPrefix(prefix);
						RegisteredPrefix newPrefixEntry = new RegisteredPrefix(entry);
						newPrefixEntry._refCount = _registeredPrefixes.get(prefix)._refCount;
						newPrefixes.put(prefix, newPrefixEntry);
					}
					_registeredPrefixes.clear();
					_registeredPrefixes.putAll(newPrefixes);
				}
			} catch (CCNDaemonException cde) {}
		}
	}	
	
	// ==============================================================
	// Statistics
	
	protected CCNEnumStats<StatsEnum> _stats = new CCNEnumStats<StatsEnum>(StatsEnum.Puts);

	public CCNStats getStats() {
		return _stats;
	}
	
	public enum StatsEnum implements IStatsEnum {
		// ====================================
		// Just edit this list, dont need to change anything else
		
		Puts ("ContentObjects", "The number of put calls"),
		Gets ("ContentObjects", "The number of get calls"),
		WriteInterest ("calls", "The number of calls to write(Interest)"),
		WriteObject ("calls", "The number of calls to write(ContentObject)"),
		WriteErrors ("count", "Error count for writeInner()"),
		WriteUnderflows ("count", "The count of times when the bytes written to the channel < buffer size"),
		
		ExpressInterest ("calls", "The number of calls to expressInterest"),
		CancelInterest ("calls", "The number of calls to cancelInterest"),
		DeliverInterest ("calls", "The number of calls to deliverInterest"),
		DeliverContent ("calls", "The number of calls to cancelInterest"),
		DeliverContentMatchingInterests ("calls", "Count of the calls to threadpool.execute in handleData()"),
		DeliverContentFailed ("calls", "The number of content deliveries that failed"),
		DeliverInterestFailed ("calls", "The number of interest deliveries that failed"),

		ReceiveObject ("objects", "Receive count of ContentObjects from channel"),
		ReceiveInterest ("interests", "Receive count of Interests from channel"),
		ReceiveUnknown ("calls", "Receive count of unknown type from channel"),
		ReceiveErrors ("errors", "Number of errors from the channel in run() loop"),
		
		ContentObjectsIgnored ("ContentObjects", "The number of ContentObjects that are never handled"),
		;

		// ====================================
		// This is the same for every user of IStatsEnum
		
		protected final String _units;
		protected final String _description;
		protected final static String [] _names;

		static {
			_names = new String[StatsEnum.values().length];
			for(StatsEnum stat : StatsEnum.values() )
				_names[stat.ordinal()] = stat.toString();

		}

		StatsEnum(String units, String description) {
			_units = units;
			_description = description;
		}

		public String getDescription(int index) {
			return StatsEnum.values()[index]._description;
		}

		public int getIndex(String name) {
			StatsEnum x = StatsEnum.valueOf(name);
			return x.ordinal();
		}

		public String getName(int index) {
			return StatsEnum.values()[index].toString();
		}

		public String getUnits(int index) {
			return StatsEnum.values()[index]._units;
		}

		public String [] getNames() {
			return _names;
		}
	}
	
	protected String formatMessage(String message) {
		return _managerIdString + message;
	}
}


