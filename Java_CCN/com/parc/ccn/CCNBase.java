package com.parc.ccn;

import java.io.IOException;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.Signature;
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
	 * @throws InterruptedException 
	 */
	public CompleteName put(ContentName name, 
							ContentAuthenticator authenticator,
							byte[] content,
							Signature signature) throws IOException, InterruptedException {

		return getNetworkManager().put(this, name, authenticator, content, signature);
	}
	
	/**
	 * The low-level get just gets us blocks that match this
	 * name. (Have to think about metadata matches.) 
	 * Trying to map this into a higher-order "get" that
	 * unfragments and reads into a single buffer is challenging.
	 * For now, let's just pass this one through to the bottom
	 * level, and use open and read to defragment.
	 * 
	 * Note: the jackrabbit implementation (at least) does not
	 * return an exact match to name if isRecursive is true -- it
	 * returns only nodes underneath name.
	 * 
	 * DKS TODO: should this get at least verify?
	 * @throws InterruptedException 
	 */
	public ContentObject get(ContentName name, 
										ContentAuthenticator authenticator,
										boolean isRecursive, long timeout) throws IOException, InterruptedException {
		
		return getNetworkManager().get(this, name, authenticator,isRecursive, timeout);
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
	public void cancelInterest(Interest interest, CCNInterestListener listener) throws IOException {
		getNetworkManager().cancelInterest(this, interest, listener);
	}
}
