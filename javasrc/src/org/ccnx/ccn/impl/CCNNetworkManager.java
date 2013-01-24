/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

import static org.ccnx.ccn.profiles.context.ServiceDiscoveryProfile.CCND_SERVICE_NAME;
import static org.ccnx.ccn.profiles.context.ServiceDiscoveryProfile.localServiceName;
import static org.ccnx.ccn.profiles.security.KeyProfile.KEY_NAME;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNStats.CCNEnumStats;
import org.ccnx.ccn.impl.CCNStats.CCNEnumStats.IStatsEnum;
import org.ccnx.ccn.impl.InterestTable.Entry;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.ccnd.CCNDaemonException;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager.ForwardingEntry;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * The low level interface to ccnd. This provides the main data API between the java library
 * and ccnd. Access to ccnd can be either via TCP or UDP. This is controlled by the
 * SystemConfiguration.AGENT_PROTOCOL property and currently defaults to TCP.
 *
 * The write API is implemented by methods of this class but users should typically access these via the
 * CCNHandle API rather than directly.
 *
 * The read API is implemented in a thread that continuously reads from ccnd. Whenever the thread reads
 * a complete packet, it calls back a handler or handlers that have been previously setup by users. Since
 * there is only one callback thread, users must take care to avoid slow or blocking processing directly
 * within the callback. This is similar to the restrictions on the event dispatching thread in Swing. The
 * setup of callback handlers should also normally be done via the CCNHandle API.
 *
 * The class also has a separate timer process which is used to refresh unsatisfied interests and to
 * keep UDP connections alive by sending a heartbeat packet at regular intervals.
 *
 * The class attempts to notice when a ccnd has died and to reconnect to a ccnd when it is restarted.
 *
 * It also handles the low level output "tap" functionality - this allows inspection or logging of
 * all the communications with ccnd.
 *
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
	protected static Object _idSyncer = new Object();
	protected static PublisherPublicKeyDigest _ccndId = null;
	protected Integer _faceID = null;
	protected CCNDIdGetter _getter = null;

	protected Thread _thread = null; // the main processing thread

	protected CCNNetworkChannel _channel = null;
	protected boolean _run = true;

	protected FileOutputStream _tapStreamOut = null;
	protected FileOutputStream _tapStreamIn = null;
	protected long _lastHeartbeat = 0;
	protected int _port = DEFAULT_AGENT_PORT;
	protected String _host = DEFAULT_AGENT_HOST;
	protected NetworkProtocol _protocol = SystemConfiguration.AGENT_PROTOCOL;

	// For handling protocol to speak to ccnd, must have keys
	protected KeyManager _keyManager;

	// Tables of interests/filters
	protected InterestTable<InterestRegistration> _myInterests = new InterestTable<InterestRegistration>();
	protected InterestTable<Filter> _myFilters = new InterestTable<Filter>();

	// Prefix registration handling. Only one registration change (add or remove a registration) with ccnd is
	// allowed at once. To enforce this, before attempting a registration change, users must acquire
	// _registrationChangeInProgress which locks access to ccnd registration across the entire face.
	//
	// _registeredPrefixes must be locked on read/write access of the prefixes
	//
	// setInterestFilter and cancelInterestFilter which may both potentially change the prefix registration with
	// ccnd are not symmetrical. When calling setInterestFilter we must have completed any necessary prefix registration
	// before returning to the user, but when we call cancelInterestFilter we can return before completing a prefix deregistration
	// since we have already disabled the mechanism to call the users handler so any spurious interest for the prefix will be
	// ignored.
	public static final boolean DEFAULT_PREFIX_REG = true;  // Should we use prefix registration? (TODO - this is pretty much obsolete
															// - it was used during the transition to prefix registration and
															// should probably be removed)
	protected boolean _usePrefixReg = DEFAULT_PREFIX_REG;
	protected PrefixRegistrationManager _prefixMgr = null;
	protected TreeMap<ContentName, RegisteredPrefix> _registeredPrefixes = new TreeMap<ContentName, RegisteredPrefix>();

	// Note that we always acquire this semaphore "uninterruptibly". I believe the dangers of trying to allow
	// this semaphore to be interrupted outweigh any advantage in doing that. Also I have tried as much as possible
	// to make it impossible or at least unlikely that this semaphore can be held for a long period without being released.
	protected Semaphore _registrationChangeInProgress = new Semaphore(1);

	// Periodic timer
	protected ScheduledThreadPoolExecutor _periodicTimer = null;
	protected Object _timersSetupLock = new Object();
	protected Boolean _timersSetup = false;
	protected PeriodicWriter _periodicWriter = null;

	// Attempt to break up non returning handlers
	protected boolean _inHandler = false;
	protected long _timeForThisHandler;
	protected long _currentHandler = 0;
	protected long _lastHandler = -1;

	// Atomic cancel
	protected InterestRegistration _beingDelivered = null;
	protected Object _beingDeliveredLock = new Object();

	/**
	 * Keep track of prefixes that are actually registered with ccnd (as opposed to Filters used
	 * to dispatch interests). There may be several filters for each registered prefix.
	 */
	public class RegisteredPrefix implements CCNContentHandler {
		private int _refCount = 0;
		private ForwardingEntry _forwarding = null;
		// FIXME: The lifetime of a prefix is returned in seconds, not milliseconds.  The refresh code needs
		// to understand this.  This isn't a problem for now because the lifetime we request when we register a
		// prefix we use Integer.MAX_VALUE as the requested lifetime.
		private long _lifetime = -1; // in seconds
		protected long _nextRefresh = -1;

		public RegisteredPrefix(ForwardingEntry forwarding) {
			_forwarding = forwarding;
			if (null != forwarding) {
				_lifetime = forwarding.getLifetime();
				_nextRefresh = System.currentTimeMillis() + (_lifetime / 2);
			}
		}

		/**
		 * Catch results of prefix deregistration. We can then unlock registration to allow
		 * new registrations or deregistrations. Note that we wait for prefix registration to
		 * complete during the setInterestFilter call but we don't wait for deregistration to
		 * complete during cancelInterestFilter. This is because we need to insure that we see
		 * interests for our prefix after a registration, but we don't need to worry about spurious
		 * interests arriving after a deregistration because they can't be delivered anyway. However
		 * to insure registrations are done correctly, we must wait for a pending deregistration
		 * to complete before starting another registration or deregistration.
		 */
		public Interest handleContent(ContentObject data, Interest interest) {
			synchronized (_registeredPrefixes) {
				if (Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE))
					Log.fine(Log.FAC_NETMANAGER, "Cancel registration completed for {0}",
							_forwarding.getPrefixName());
				_registeredPrefixes.remove(_forwarding.getPrefixName());
			}
			_registrationChangeInProgress.release();
			return null;
		}
	}

	/**
	 * Do scheduled interest, registration refreshes, and UDP heartbeats.
	 * Called periodically. Each instance calculates when it should next be called.
	 * TODO - registrations are currently always set to never expire so we don't need to
	 * refresh them here yet. At some point this should be fixed.
	 */
	private class PeriodicWriter implements Runnable {
		@Override
		public void run() {
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
                        _periodicTimer.schedule(this, CCNNetworkChannel.SOCKET_TIMEOUT, TimeUnit.MILLISECONDS);
                return;
            }

            long ourTime = System.currentTimeMillis();
            long minInterestRefreshTime = PERIOD + ourTime;

			// Re-express interests that need to be re-expressed
			try {
				for (Entry<InterestRegistration> entry : _myInterests.values()) {
					InterestRegistration reg = entry.value();
					 // allow some slop for scheduling
                    if (ourTime + 20 > reg.nextRefresh) {
                            if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
                                    Log.finer(Log.FAC_NETMANAGER, "Refresh interest: {0}", reg.interest);
                            _lastHeartbeat = ourTime;
                            reg.nextRefresh = ourTime + SystemConfiguration.INTEREST_REEXPRESSION_DEFAULT;
;
                            try {
                                write(reg.interest);
                        } catch (NotYetConnectedException nyce) {
                                refreshError = true;
                        }
                    }
					if (minInterestRefreshTime > reg.nextRefresh)
						minInterestRefreshTime = reg.nextRefresh;
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
            /* if (_usePrefixReg) {
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
			} // _usePrefixReg */

        	if (refreshError) {
                Log.warning(Log.FAC_NETMANAGER, "we have had an error when refreshing an interest or prefix registration...  do we need to reconnect to ccnd?");
        	}

			long currentTime = System.currentTimeMillis();

        	// Try to bring back the run thread if its hung
        	// TODO - do we want to keep this in permanently?
        	if (_inHandler) {
        		if (_currentHandler == _lastHandler) {
	        		long delta = currentTime - _timeForThisHandler;
	        		if (delta > SystemConfiguration.MAX_TIMEOUT) {

	        			// Print out what the thread was doing first
	        			Throwable t = new Throwable("Handler took too long to return - stack trace follows");
	        			t.setStackTrace(_thread.getStackTrace());
	        			Log.logStackTrace(Log.FAC_NETMANAGER, Level.SEVERE, t);

	        			_thread.interrupt();
	        		}
        		} else {
	        		_lastHandler = _currentHandler;
	        		_timeForThisHandler = currentTime;
        		}
        	}

        	// Calculate when we should next be run
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
				_periodicTimer.schedule(this, useMe, TimeUnit.MILLISECONDS);
		} /* run */
	} /* private class PeriodicWriter extends TimerTask */

	/**
	 * First time startup of processing thread and periodic timer after first registration. We do this
	 * after the first registration rather than at startup, because in some cases network managers get
	 * created (via a CCNHandle) that are never used. We don't want to burden the JVM with more processing
	 * until we are sure we are going to be used (which can't happen until there is a registration,
	 * either of an interest in which case we expect to receive matching data, or of a prefix in
	 * which case we expect to receive interests).
	 *
	 * We don't bother to "unstartup" if everything is deregistered
	 * @throws IOException
	 */
	private void setupTimers() throws IOException {
		synchronized (_timersSetupLock) {
			if (!_timersSetup) {
				// Create main processing thread
				_thread = new Thread(this, "CCNNetworkManager " + _managerId);
				_thread.setPriority(Thread.MAX_PRIORITY);
				_thread.start();

				_timersSetup = true;
				_channel.init();
				if (_protocol == NetworkProtocol.UDP) {
					_channel.heartbeat();
					_lastHeartbeat = System.currentTimeMillis();
				}

				// Create timer for periodic behavior
				_periodicTimer = new ScheduledThreadPoolExecutor(1);
				_periodicWriter = new PeriodicWriter();
				_periodicTimer.schedule(_periodicWriter, PERIOD, TimeUnit.MILLISECONDS);
			}
		}
	}

	/** Generic superclass for registration objects that may have a callback handler
	 */
	protected class CallbackHandlerRegistration {
		protected Object handler;
		public Semaphore sema = null;	//used to block thread waiting for data or null if none
		public Object owner = null;
		public boolean cancelled = false;

		/** Equality based on handler if present, so multiple objects can
		 *  have the same interest registered without colliding
		 */
		@Override
		public boolean equals(Object obj) {
			if (obj instanceof CallbackHandlerRegistration) {
				CallbackHandlerRegistration other = (CallbackHandlerRegistration)obj;
				if (this.owner == other.owner) {
					if (null == this.handler && null == other.handler){
						return super.equals(obj);
					} else if (null != this.handler && null != other.handler) {
						return this.handler.equals(other.handler);
					}
				}
			}
			return false;
		}

		@Override
		public int hashCode() {
			if (null != this.handler) {
				if (null != owner) {
					return owner.hashCode() + this.handler.hashCode();
				} else {
					return this.handler.hashCode();
				}
			} else {
				return super.hashCode();
			}
		}
	}

	/**
	 * Record of Interest
	 * This is the mechanism that calls a user contentHandler when a ContentObject
	 * that matches their interest is received by the network manager.
	 *
	 * handler must be set (non-null) for cases of standing Interest that holds
	 * until canceled by the application.  The listener should be null when a
	 * thread is blocked waiting for data, in which case the thread will be
	 * blocked on semaphore.
	 */
	protected class InterestRegistration extends CallbackHandlerRegistration {
		public final Interest interest;
		protected long nextRefresh;		// next time to refresh the interest
		protected ContentObject content;

		// All internal client interests must have an owner
		public InterestRegistration(Interest i, Object h, Object owner) {
			interest = i;
			handler = h;
			this.owner = owner;
			if (null == handler) {
				sema = new Semaphore(0);
			}
			nextRefresh = System.currentTimeMillis() + SystemConfiguration.INTEREST_REEXPRESSION_DEFAULT;
		}

		/**
		 * Deliver content to a registered handler
		 */
		public void deliver(ContentObject co) {
			synchronized (_beingDeliveredLock) {
				_beingDelivered = this;
			}
			try {
				if (null != this.handler) {
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
						Log.finer(Log.FAC_NETMANAGER, "Content callback (" + co + " data) for: {0}", this.interest.name());

					unregisterInterest(this);

					// Callback the client - we can't hold any locks here!
					Interest updatedInterest = ((CCNContentHandler)handler).handleContent(co, interest);

					// Possibly we should optimize here for the case where the same interest is returned back
					// (now we would unregister it, then reregister it) but need to be careful that the timing
					// behavior is right if we do that
					if (null != updatedInterest && !cancelled) {
						if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
							Log.finer(Log.FAC_NETMANAGER, "Interest callback: updated interest to express: {0}", updatedInterest.name());
						// if we want to cancel this one before we get any data, we need to remember the
						// updated interest in the handler
						expressInterest(this.owner, updatedInterest, handler);
					}
				} else {
					// This is the "get" case
					content = co;
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

			synchronized (_beingDeliveredLock) {
				_beingDelivered = null;
			}
		}

	} /* protected class InterestRegistration extends CallbackHandlerRegistration */

	/**
	 * Record of a filter describing portion of namespace for which this
	 * application can respond to interests. Used to deliver incoming interests
	 * to registered interest handlers
	 */
	protected class Filter extends CallbackHandlerRegistration {
		protected Interest interest = null; // interest to be delivered
		// extra interests to be delivered: separating these allows avoidance of ArrayList obj in many cases
		protected ContentName prefix = null;

		public Filter(ContentName n, Object h, Object o) {
			prefix = n; handler = h; owner = o;
		}

		/**
		 * Call the user's interest handler callback
		 * @param interest - the interest that triggered this
		 * @return - whether we handled the interest. If true we won't call any more handlers
		 *           matching this interest
		 */
		public boolean deliver(Interest interest) {
			try {
				// Call into client code without holding any library locks
				if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
					Log.finer(Log.FAC_NETMANAGER, "Filter callback for: {0}", prefix);
				return ((CCNInterestHandler)handler).handleInterest(interest);
			} catch (RuntimeException ex) {
				_stats.increment(StatsEnum.DeliverInterestFailed);
				Log.warning(Log.FAC_NETMANAGER, "failed to deliver interest: {0}", ex);
				Log.warningStackTrace(ex);
				return false;
			}
		}
		@Override
		public String toString() {
			return prefix.toString();
		}
	} /* protected class Filter extends CallbackHandlerRegistration */

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
						Log.info(Log.FAC_NETMANAGER, "CCNDIdGetter: ccndId {0}", Component.printURI(sentID.digest()));
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
		_channel.open();
	}

	/**
	 * Shutdown the connection to ccnd and all threads associated with this network manager
	 */
	public void shutdown() {
		Log.info(Log.FAC_NETMANAGER, formatMessage("Shutdown requested"));

		_run = false;
		if (_periodicTimer != null)
			_periodicTimer.shutdownNow();
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

		// Print the statistics for this network manager
		if (SystemConfiguration.DUMP_NETMANAGER_STATS)
			System.out.println(getStats().toString());
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
	 * @param interest	the interest
	 * @param timeout	time to wait for return in ms
	 * @return	ContentObject or null on timeout
	 * @throws IOException 	on incorrect interest data
	 * @throws InterruptedException	if process is interrupted during wait
	 */
	public ContentObject get(Interest interest, long timeout) throws IOException, InterruptedException {
        boolean acquired = true;
		_stats.increment(StatsEnum.Gets);

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("get: {0} with timeout: {1}"), interest, timeout);
		InterestRegistration reg = new InterestRegistration(interest, null, null);
		expressInterest(reg);
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
			Log.finest(Log.FAC_NETMANAGER, formatMessage("blocking for {0} on {1}"), interest.name(), reg.sema);
		// Await data to consume the interest
		try {
			if (timeout == SystemConfiguration.NO_TIMEOUT)
				reg.sema.acquire(); // currently no timeouts
			else
				acquired = reg.sema.tryAcquire(timeout, TimeUnit.MILLISECONDS);
			if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
				Log.finest(Log.FAC_NETMANAGER,
                           formatMessage("unblocked for {0} on {1} (content{2} received)"),
                           interest.name(), reg.sema, acquired ? "" : " not");
		} catch (InterruptedException e) {
			if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
				Log.finest(Log.FAC_NETMANAGER, formatMessage("interupted during acquire for {0} on {1}"), interest.name(), reg.sema);
			unregisterInterest(reg);
			throw e;
		}
		// Typically the main processing thread will have registered the interest
		// which must be undone here, but no harm if never registered
		unregisterInterest(reg);
		return reg.content;
	}

	/**
	 * We express interests to the ccnd and register them within the network manager
	 *
	 * @param caller 	must not be null
	 * @param interest 	the interest
	 * @param handler	handler to callback on receipt of data
	 * @throws IOException on incorrect interest
	 */
	public void expressInterest(
			Object caller,
			Interest interest,
			Object handler) throws IOException {
		// TODO - use of "caller" should be reviewed - don't believe this is currently serving
		// serving any useful purpose.
		if (null == handler) {
			throw new NullPointerException(formatMessage("expressInterest: callbackHandler cannot be null"));
		}

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("expressInterest: {0}"), interest);
		InterestRegistration reg = new InterestRegistration(interest, handler, caller);
		expressInterest(reg);
	}
	
	/**
	 * Register to receive a content object without issuing an interest
	 * 
	 * @param caller 	must not be null
	 * @param interest 	the interest
	 * @param handler	handler to callback on receipt of data
	 * @throws IOException on incorrect interest
	 */
	public void registerInterest(
			Object caller,
			Interest interest,
			Object handler) throws IOException {
		// TODO - use of "caller" should be reviewed - don't believe this is currently serving
		// serving any useful purpose.
		if (null == handler) {
			throw new NullPointerException(formatMessage("registerInterest: callbackHandler cannot be null"));
		}

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("registerInterest: {0}"), interest);
		InterestRegistration reg = new InterestRegistration(interest, handler, caller);
		registerInterest(reg);
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
	 * Cancel this query
	 *
	 * @param caller 	must not be null
	 * @param interest
	 * @param handler
	 */
	public void cancelInterest(Object caller, Interest interest, Object handler) {
		if (null == handler) {
			// TODO - use of "caller" should be reviewed - don't believe this is currently serving
			// serving any useful purpose.
			throw new NullPointerException(formatMessage("cancelInterest: handler cannot be null"));
		}
		_stats.increment(StatsEnum.CancelInterest);

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("cancelInterest: {0}"), interest.name());
		// Remove interest from repeated presentation to the network.
		InterestRegistration reg = unregisterInterest(caller, interest, handler);

		// Make sure potential remnants of cancelled interest are also cancelled
		synchronized (_beingDeliveredLock) {
			if (null != _beingDelivered  && _beingDelivered.equals(reg))
				_beingDelivered.cancelled = true;
		}
	}

	/**
	 * Register a standing interest filter with callback to receive any
	 * matching interests seen. Any interests whose prefix completely matches "filter" will
	 * be delivered to the handler. Also if this filter matches no currently registered
	 * prefixes, register its prefix with ccnd.
	 *
	 * @param caller 	must not be null
	 * @param filter	ContentName containing prefix of interests to match
	 * @param handler 	a CCNInterestHandler
	 * @throws IOException
	 */
	public void setInterestFilter(Object caller, ContentName filter, Object handler) throws IOException {
		setInterestFilter(caller, filter, handler, null);
	}

	/**
	 * Register a standing interest filter with callback to receive any
	 * matching interests seen. Any interests whose prefix completely matches "filter" will
	 * be delivered to the handler. Also if this filter matches no currently registered
	 * prefixes, register its prefix with ccnd.
	 *
	 * Note that this is mismatched with deregistering prefixes. When registering, we wait for the
	 * registration to complete before continuing, but when deregistering we don't.
	 *
	 * @param caller 	must not be null
	 * @param filter	ContentName containing prefix of interests to match
	 * @param callbackHandler a CCNInterestHandler
	 * @param registrationFlags to use for this registration.
	 * @throws IOException
	 *
	 * TODO - use of "caller" should be reviewed - don't believe this is currently serving
	 * serving any useful purpose.
	 */
	public void setInterestFilter(Object caller, ContentName filter, Object callbackHandler,
			Integer registrationFlags) throws IOException {

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("setInterestFilter: {0}"), filter);
		if ((null == _keyManager) || (!_keyManager.initialized() || (null == _keyManager.getDefaultKeyID()))) {
			Log.warning(Log.FAC_NETMANAGER, formatMessage("Cannot set interest filter -- key manager not ready!"));
			throw new IOException(formatMessage("Cannot set interest filter -- key manager not ready!"));
		}

		setupTimers();
		// set up filters before registering as registration may cause a pending interest
		// to be delivered immediately.
		Filter newOne = new Filter(filter, callbackHandler, caller);
		_myFilters.add(filter, newOne);
		if (_usePrefixReg) {
			RegisteredPrefix prefix = null;
			_registrationChangeInProgress.acquireUninterruptibly();
			synchronized(_registeredPrefixes) {
				// Determine whether we need to register our prefix with ccnd
				// We do if either its not registered now, or the one registered now is being
				// cancelled but its still in the process of getting deregistered. In the second
				// case (closing) we need to wait until the prefix has been deregistered before
				// we go ahead and register it. And of course, someone else could have registered it
				// before we got to it so check for that also. If its already registered, just bump
				// its use count.
				prefix = getRegisteredPrefix(filter);  // Did someone else already register it?
				if (null != prefix) {  // no
					prefix._refCount++;
				}
			}

			if (null == prefix) {
				// We don't want to hold the _registeredPrefixes lock here, but we're safe to change things
				// because we have acquired _registrationChangeInProgress.
				try {
					if (null == _prefixMgr) {
						_prefixMgr = new PrefixRegistrationManager(this);
					}
					prefix = registerPrefix(filter, registrationFlags);
				} catch (CCNDaemonException e) {
					_myFilters.remove(filter, newOne);
					Log.warning(Log.FAC_NETMANAGER, formatMessage("setInterestFilter: unexpected CCNDaemonException: " + e.getMessage()));
					throw new IOException(e.getMessage());
				}
				synchronized (_registeredPrefixes) {
					prefix._refCount++;
				}
			}
			_registrationChangeInProgress.release();
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
	 * Register a prefix with ccnd.
	 *
	 * @param filter
	 * @param registrationFlags
	 * @throws CCNDaemonException
	 */
    private RegisteredPrefix registerPrefix(ContentName filter, Integer registrationFlags) throws CCNDaemonException {
    	ForwardingEntry entry = null;
    	if (_channel.isConnected()) {
	    	if (null == registrationFlags) {
				entry = _prefixMgr.selfRegisterPrefix(filter);
			} else {
				entry = _prefixMgr.selfRegisterPrefix(filter, null, registrationFlags, Integer.MAX_VALUE);
			}
    	}
    	RegisteredPrefix newPrefix = null;
    	synchronized (_registeredPrefixes) {
			newPrefix = new RegisteredPrefix(entry);
			_registeredPrefixes.put(filter, newPrefix);
			// FIXME: The lifetime of a prefix is returned in seconds, not milliseconds.  The refresh code needs
			// to understand this.  This isn't a problem for now because the lifetime we request when we register a
			// prefix we use Integer.MAX_VALUE as the requested lifetime.
			if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
				Log.fine(Log.FAC_NETMANAGER, "registerPrefix for {0}: entry.lifetime: {1} entry.faceID: {2}", filter, entry.getLifetime(), entry.getFaceID());
    	}
		return newPrefix;
    }

	/**
	 * Unregister a standing interest filter.
	 * If we are the last user of a filter registered with ccnd, we request a deregistration with
	 * ccnd but we don't need to wait for it to complete.
	 *
	 * @param caller 	must not be null
	 * @param filter	currently registered filter
	 * @param handler	the handler registered to it
	 */
	public void cancelInterestFilter(Object caller, ContentName filter, Object handler) {
		// TODO - use of "caller" should be reviewed - don't believe this is currently serving
		// serving any useful purpose.
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
			Log.fine(Log.FAC_NETMANAGER, formatMessage("cancelInterestFilter: {0}"), filter);
		Filter newOne;
		newOne = new Filter(filter, handler, caller);
		Entry<Filter> found = null;
		found = _myFilters.remove(filter, newOne);
		if (null != found) {
			boolean semaAcquired = false;
			if (_usePrefixReg) {
				// Deregister it with ccnd only if the refCount would go to 0
				RegisteredPrefix prefix = null;
				boolean doRemove = false;
				synchronized (_registeredPrefixes) {
					prefix = getRegisteredPrefix(filter);
				}
				if (null != prefix && prefix._refCount <= 1) {
					// We need to deregister it with ccnd. But first we need to make sure nobody else is messing around
					// with the ccnd prefix registration.
					_registrationChangeInProgress.acquireUninterruptibly();
					semaAcquired = true;
					synchronized (_registeredPrefixes) {
						prefix = getRegisteredPrefix(filter); // Did some else already remove this prefix?
						if (null != prefix) {  // no
							if (prefix._refCount <= 1) {
								doRemove = true;
							}
						}
						if (null != prefix)
							prefix._refCount--;
					}
				}
				if (doRemove) {
					// We are going to deregister the prefix with ccnd. We don't want to hold locks here but
					// we don't have to worry about others changing the prefix registration underneath us because
					// we have acquired _registrationChangeInProgress.
					try {
						if (null == _prefixMgr) {
							_prefixMgr = new PrefixRegistrationManager(this);
						}
						if (_channel.isConnected()) {
							ForwardingEntry entry = prefix._forwarding;
							_prefixMgr.unRegisterPrefix(filter, prefix, entry.getFaceID());
						} else
							_registrationChangeInProgress.release();
					} catch (CCNDaemonException e) {
						Log.warning(Log.FAC_NETMANAGER, formatMessage("cancelInterestFilter failed with CCNDaemonException: " + e.getMessage()));
						_registrationChangeInProgress.release();
					}
				} else {
					if (semaAcquired)
						_registrationChangeInProgress.release();
				}
			}
		} else {
			if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINE) )
				Log.fine(Log.FAC_NETMANAGER, formatMessage("cancelInterestFilter: {0} not found"), filter);
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

		writeInner(data);
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
			Log.finest(Log.FAC_NETMANAGER, formatMessage("Wrote content object: {0}"), data.name());
	}

	/**
	 * Write an interest directly to ccnd
	 * Don't do this unless you know what you are doing! See CCNHandle.expressInterest for the proper
	 * way to output interests to the network.
	 *
	 * @param interest
	 * @throws ContentEncodingException
	 */
	public void write(Interest interest) throws ContentEncodingException {
		_stats.increment(StatsEnum.WriteInterest);
		writeInner(interest);
	}

	// DKS TODO unthrown exception
	private void writeInner(GenericXMLEncodable packet) throws ContentEncodingException {
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
	 * Internal registration of interest to callback for matching data relationship.
	 *
	 * @throws IOException
	 */
	private InterestRegistration registerInterest(InterestRegistration reg) throws IOException {
		// Add to standing interests table
		setupTimers();
		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
			Log.finest(Log.FAC_NETMANAGER, formatMessage("registerInterest for {0}, and obj is " + _myInterests.hashCode()), reg.interest.name());
		_myInterests.add(reg.interest, reg);
		return reg;
	}

	private InterestRegistration unregisterInterest(Object caller, Interest interest, Object handler) {
		InterestRegistration reg = new InterestRegistration(interest, handler, caller);
		return unregisterInterest(reg);
	}

	/**
	 * @param reg - registration to unregister
	 */
	private InterestRegistration unregisterInterest(InterestRegistration reg) {
		InterestRegistration result = reg;
		Entry<InterestRegistration> entry = _myInterests.remove(reg.interest, reg);
		if (null != entry)
			result = entry.value();
		return result;
	}

	/**
	 * Reader thread: this thread will handle reading datagrams and perform callbacks after reading
	 * complete packets.
	 */
	public void run() {
		if (! _run) {
			Log.warning(Log.FAC_NETMANAGER, formatMessage("CCNNetworkManager run() called after shutdown"));
			return;
		}

		if( Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO) )
			Log.info(Log.FAC_NETMANAGER, formatMessage("CCNNetworkManager processing thread started for port: " + _port));
		while (_run) {
			try {
				boolean wasConnected = _channel.isConnected();
				XMLEncodable packet = _channel.getPacket();
				if (null == packet) {
					// If ccnd went up and down, we have to reregister all prefixes that used to be
					// registered to restore normal operation
					if (_run && !wasConnected && _channel.isConnected())
						reregisterPrefixes();
					if (_run && !_channel.isConnected()) {
						if (SystemConfiguration.EXIT_ON_NETWORK_ERROR) {
							Log.warning(Log.FAC_NETMANAGER,
									formatMessage("ccnd down and exit on network error requested - exiting"));

							// Note - exit is pretty drastic but this is not the default behaviour and if someone
							// requested it, there's really no easy way to get this to happen without modifying
							// upper level applications to learn about this. Perhaps there should be another option to
							// do that i.e. exit netmanager and notify without immediate exit.
							System.exit(1);
						}
					}
					if (!_run || !_channel.isConnected()) {
						// This is probably OK - its better than the altenative which is that if ccnd went down while
						// someone held the semaphore we could (potentially) hang.
						_registrationChangeInProgress.release();
					}
					continue;
				}
				_currentHandler++;
				_inHandler = true;	// Do in this order

				if (packet instanceof ContentObject) {
					_stats.increment(StatsEnum.ReceiveObject);
					ContentObject co = (ContentObject)packet;
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
						Log.finer(Log.FAC_NETMANAGER, formatMessage("Data from net for port: " + _port + " {0}"), co.name());

					//	SystemConfiguration.logObject("Data from net:", co);

					deliverContent(co);
				} else if (packet instanceof Interest) {
					_stats.increment(StatsEnum.ReceiveInterest);
					Interest interest = (Interest)	packet;
					if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST) )
						Log.finest(Log.FAC_NETMANAGER, formatMessage("Interest from net for port: " + _port + " {0}"), interest);
					InterestRegistration oInterest = new InterestRegistration(interest, null, null);
					deliverInterest(oInterest, interest);
				}  else { // for interests
					_stats.increment(StatsEnum.ReceiveUnknown);
				}
			} catch (Exception ex) {
				_stats.increment(StatsEnum.ReceiveErrors);
				Log.severe(Log.FAC_NETMANAGER, formatMessage("Processing thread failure (UNKNOWN): " + ex.getMessage() + " for port: " + _port));
                Log.severeStackTrace(Log.FAC_NETMANAGER, ex);
			} catch (Error er) {
				_stats.increment(StatsEnum.ReceiveErrors);
				Log.severe(Log.FAC_NETMANAGER, formatMessage("Processing thread error: " + er.getMessage() + " - exiting"));
                Log.severeStackTrace(Log.FAC_NETMANAGER, er);
                System.exit(1);
			}
			_inHandler = false;
		}

		Log.info(Log.FAC_NETMANAGER, formatMessage("Shutdown complete for port: " + _port));
	}

	/**
	 * Internal delivery of interests to pending filter handlers
	 * @param ireg
	 */
	protected void deliverInterest(InterestRegistration ireg, Interest interest) {
		_stats.increment(StatsEnum.DeliverInterest);

		// Call any handlers with matching filters
		for (Filter filter : _myFilters.getValues(ireg.interest.name())) {
			if (filter.owner != ireg.owner) {
				if( Log.isLoggable(Log.FAC_NETMANAGER, Level.FINER) )
					Log.finer(Log.FAC_NETMANAGER, formatMessage("Schedule delivery for interest: {0}"), interest);
				_stats.increment(StatsEnum.DeliverInterestMatchingFilters);
				long startTime = System.nanoTime();
				boolean succeeded = filter.deliver(interest);
				_stats.addSample(StatsEnum.InterestHandlerTime, System.nanoTime() - startTime);
				if (succeeded)
					break;	// We only run interest handlers until one succeeds
			}
		}
	}

	/**
	 *  Deliver data to all blocked getters and registered interests
	 * @param co
	 */
	protected void deliverContent(ContentObject co) {
		_stats.increment(StatsEnum.DeliverContent);

		for (InterestRegistration ireg : _myInterests.getValues(co)) {
			_stats.increment(StatsEnum.DeliverContentMatchingInterests);
			long startTime = System.nanoTime();
			ireg.deliver(co);
			_stats.addSample(StatsEnum.ContentHandlerTime, System.nanoTime() - startTime);
		}
	}

	/**
	 * Diagnostic routine to get a handler stack trace in time of suspected problem
	 */
	public void dumpHandlerStackTrace(String message) {
		if (_inHandler) {
			Throwable t = new Throwable(message);
			t.setStackTrace(_thread.getStackTrace());
			Log.logStackTrace(Log.FAC_NETMANAGER, Level.SEVERE, t);
		}
	}

	protected PublisherPublicKeyDigest fetchCCNDId(CCNNetworkManager mgr, KeyManager keyManager) throws IOException {
		try {
			ContentName serviceKeyName = new ContentName(localServiceName(CCND_SERVICE_NAME), KEY_NAME);
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
		new Thread() {
			@Override
			public void run() {
				TreeMap<ContentName, RegisteredPrefix> newPrefixes = new TreeMap<ContentName, RegisteredPrefix>();
				_registrationChangeInProgress.acquireUninterruptibly();
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
		}.start();
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
		DeliverInterestMatchingFilters ("calls", "Count of the number of calls to interest handlers"),
		DeliverContentMatchingInterests ("calls", "Count of the number of calls to content handlers"),
		DeliverContentFailed ("calls", "The number of content deliveries that failed"),
		DeliverInterestFailed ("calls", "The number of interest deliveries that failed"),

		InterestHandlerTime("nanos", "The average amount of time spent in interest handlers"),
		ContentHandlerTime("nanos", "The average amount of time spent in content handlers"),

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


