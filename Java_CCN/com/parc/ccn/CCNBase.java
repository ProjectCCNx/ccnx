package com.parc.ccn;

import java.io.IOException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.network.CCNNetworkManager;

/**
 * DKS TODO this should change to reflect only the core CCN network
 * operations.
 * @author smetters, rasmussen
 *
 */
public class CCNBase {
	
	public final static int NO_TIMEOUT = 0;
	
	/** 
	 * DKS -- TODO temporary values to workaround problems in packet drops on the
	 * 	local ccnd connection. 
	 */
	public static final boolean CONFIRM_PUTS = true;
	public static final int CONFIRMATION_TIMEOUT = 10;
	
	/**
	 * Allow separate per-instance to control reading/writing within
	 * same app. Get default one if use static VM instance of StandardCCNLibrary,
	 * but if you make a new instance, get a new connection to ccnd.
	 */
	protected CCNNetworkManager _networkManager = null;
	
	public CCNNetworkManager getNetworkManager() { 
		if (null == _networkManager) {
			synchronized(this) {
				if (null == _networkManager) {
					try {
						_networkManager = new CCNNetworkManager();
					} catch (IOException ex){
						Library.logger().warning("IOException instantiating network manager: " + ex.getMessage());
						ex.printStackTrace();
						_networkManager = null;
					}
				}
			}
		}
		return _networkManager;
	}
	
	/**
	 * Implementation of CCNBase.put.
	 * @param co
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public ContentObject put(ContentObject co) throws IOException, InterruptedException {
		ContentObject reply = getNetworkManager().put(co);
		// DKS -- total hack, but we're dropping stuff on the floor all over
		// the place. We need an ack on the channel for localhost, according to
		// Michael.
		if (CONFIRM_PUTS) {
		//	Interest readBackInterest = new Interest(co.name(), 0, co.authenticator().publisherKeyID());
			Interest readBackInterest = new Interest(co.name());
			ContentObject readBack = get(readBackInterest, CONFIRMATION_TIMEOUT);
			while (null == readBack) {
				Library.logger().info("Put failed, resubmitting " + co.name() + ".");
				getNetworkManager().put(co);
				try {
					readBack = get(readBackInterest, CONFIRMATION_TIMEOUT);
				} catch (InterruptedException ie) {}
			}
			Library.logger().finer("Confirmed put, retrieived " + readBack.name());
		}
		return reply;
	}
	
	/**
	 * Implementation of CCNBase get
	 * @param interest
	 * @param timeout
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public ContentObject get(Interest interest, long timeout) throws IOException, InterruptedException {
		return getNetworkManager().get(interest, timeout);
	}
	
	/**
	 * Register a standing interest filter with callback to receive any 
	 * matching interests seen
	 */
	public void registerFilter(ContentName filter,
			CCNFilterListener callbackListener) {
		getNetworkManager().setInterestFilter(this, filter, callbackListener);
	}
	
	/**
	 * Unregister a standing interest filter
	 */
	public void unregisterFilter(ContentName filter,
			CCNFilterListener callbackListener) {
		getNetworkManager().cancelInterestFilter(this, filter, callbackListener);		
	}
	
	/**
	 * Query, or express an interest in particular
	 * content. This request is sent out over the
	 * CCN to other nodes. On any results, the
	 * callbackListener if given, is notified.
	 * Results may also be cached in a local repository
	 * for later retrieval by get().
	 * Get and expressInterest could be implemented
	 * as a single function that might return some
	 * content immediately and others by callback;
	 * we separate the two for now to simplify the
	 * interface.
	 * @param name
	 * @param authenticator
	 * @param callbackListener
	 * @return returns a unique identifier that can
	 * 		be used to cancel this query.
	 * @throws IOException
	 * 
	 * Pass it on to the CCNInterestManager to
	 * forward to the network. Also express it to the
	 * repositories we manage, particularly the primary.
	 * Each might generate their own CCNQueryDescriptor,
	 * so we need to group them together.
	 */
	public void expressInterest(
			Interest interest,
			CCNInterestListener listener) throws IOException {
		// Will add the interest to the listener.
		getNetworkManager().expressInterest(this, interest, listener);
	}

	/**
	 * Cancel this interest. 
	 * @param interest
	 * @param callbackListener Used to distinguish the same interest
	 * 	requested by more than one listener.
	 * @throws IOException
	 */
	public void cancelInterest(Interest interest, CCNInterestListener listener) {
		getNetworkManager().cancelInterest(this, interest, listener);
	}
}
