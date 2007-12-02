package com.parc.ccn.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.acplt.oncrpc.OncRpcException;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.network.rpc.Name;
import com.parc.ccn.network.rpc.Repo2TransportClient;

/**
 * Forwards queries over an ONC RPC interface
 * to the CCN network daemon. Implements client
 * for Repo2Transport protocol and server for
 * Transport2Repo protocol. Can be run as a
 * standalone program, in which case it loads
 * its own repository manager (eventually
 * using a config file to tell it which repos
 * to manage), so that it can be a headless 
 * interface to a repository for testing or
 * where there is no client app.
 * See InterestDaemon.
 * 
 * @author smetters
 *
 */
public class CCNInterestManager {
	
	Repo2TransportClient _client = null;

	/**
	 * Static singleton.
	 */
	protected static CCNInterestManager _interestManager = null;

	protected CCNInterestManager() throws IOException {
		super();
		try {
			_client = new Repo2TransportClient(InetAddress.getLocalHost(), SystemConfiguration.defaultTransportPort());
		} catch (UnknownHostException e) {
			Library.logger().severe("Cannot look up localhost!");
			Library.warningStackTrace(e);
			throw new RuntimeException("This should not happen: no localhost!", e);
		} catch (OncRpcException e) {
			Library.logger().warning("RPC exception creating transport client: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new IOException(e);
		}
	}

	public static CCNInterestManager getCCNInterestManager() { 
		if (null != _interestManager) 
			return _interestManager;
		
		return createInterestManager();
	}
	
	protected static synchronized CCNInterestManager createInterestManager() {
		if (null == _interestManager) {
			_interestManager = new CCNInterestManager();
			// Need to start RPC server.
			_interestManager.start();
		}
		return _interestManager;
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
	 * @param TTL limited-duration query, removes
	 * 	the requirement to call cancelInterest. TTL
	 *  <= 0 signals a query that runs until cancelled.
	 * @return returns a unique identifier that can
	 * 		be used to cancel this query.
	 * @throws IOException
	 */
	public CCNQueryDescriptor expressInterest(
			ContentName name,
			ContentAuthenticator authenticator,
			CCNQueryListener callbackListener,
			long TTL) throws IOException {
		
		
		Name oncName = new Name();
		// For right now, we skip sending the 
		// authenticator with the query. In the next
		// version we will extend the transport
		// agent to handle full queries.
		// TODO handle full queries in transport agent.
		_client
	}
	
	public void cancelInterest(CCNQueryDescriptor query) throws IOException {
		
	}

}
