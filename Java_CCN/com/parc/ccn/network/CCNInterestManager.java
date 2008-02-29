package com.parc.ccn.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.acplt.oncrpc.OncRpcException;
import org.acplt.oncrpc.OncRpcProtocols;

import com.parc.ccn.Library;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.network.rpc.Name;
import com.parc.ccn.network.rpc.RepoTransport_REPOTOTRANSPORTPROG_Client;

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
	
	RepoTransport_REPOTOTRANSPORTPROG_Client _client = null;

	/**
	 * Static singleton.
	 */
	protected static CCNInterestManager _interestManager = null;

	protected CCNInterestManager() throws IOException {
		super();
		try {
			// without portmap
			//_client = new RepoTransport_REPOTOTRANSPORTPROG_Client(
			//			InetAddress.getLocalHost(), 
			//			SystemConfiguration.defaultTransportPort(),
			//			OncRpcProtocols.ONCRPC_UDP);
			_client = new RepoTransport_REPOTOTRANSPORTPROG_Client(
					InetAddress.getLocalHost(), 
					OncRpcProtocols.ONCRPC_UDP);
		} catch (UnknownHostException e) {
			Library.logger().severe("Cannot look up localhost!");
			Library.warningStackTrace(e);
			throw new RuntimeException("This should not happen: no localhost!", e);
		} catch (OncRpcException e) {
			Library.logger().warning("RPC exception creating transport client: " + e.getMessage());
			Library.warningStackTrace(e);
			Library.logger().warning("Continuing without...");
			//throw new IOException("RPC exception creating transport client: " + e.getMessage());
		}
	}

	public static CCNInterestManager getInterestManager() throws IOException { 
		if (null != _interestManager) 
			return _interestManager;
		
		return createInterestManager();
	}
	
	protected static synchronized CCNInterestManager createInterestManager() throws IOException {
		if (null == _interestManager) {
			_interestManager = new CCNInterestManager();
			// We don't need a thread for the interest manager;
			// it only includes client functionality.
		}
		return _interestManager;
	}
	
	/**
	 * Query, or express an interest in particular content. This request is
	 * sent out over the CCN to other nodes. On any results, the
	 * callbackListener if given, is notified. Results may also be cached in 
	 * a local repository for later retrieval by get(). Get and expressInterest 
	 * be implemented as a single function that might return some
	 * content immediately and others by callback; we separate the two for now to 
	 * simplify the interface.
	 * @param name
	 * @param authenticator
	 * @param callbackListener
	 * @return returns a unique identifier that can be used to cancel this query.
	 * @throws IOException
	 */
	public CCNQueryDescriptor expressInterest(
			Interest interest,
			CCNInterestListener callbackListener) throws IOException {
		
		// Work around no portmap
		if (null == _client)
			return null;
		
		CCNQueryDescriptor qd = new CCNQueryDescriptor(interest, null, callbackListener);
		Name oncName = interest.name().toONCName();
		// For right now, we skip sending the  authenticator with the query. In the next
		// version we will extend the transport agent to handle full queries.
		// TODO handle full queries in transport agent.
		try {
			_client.RegisterInterest_1(oncName);
			// Won't respond to inputs that come in before this next step...
			callbackListener.addQuery(qd);
		} catch (OncRpcException e) {
			Library.logger().warning("Exception in expressInterest RPC interface: " + e.getMessage());
			Library.warningStackTrace(e);
			// IOException(Throwable) constructor not present in 1.5
			//throw new IOException("Exception in expressInterest RPC interface: " + e.getMessage());
			return null; // DKS make robust to lack of transport
		}
		return qd;
	}
	
	public void cancelInterest(CCNQueryDescriptor query) throws IOException {
		// Work around no portmap
		if (null == _client)
			return;
		
		Name oncName = query.name().toONCName();
		try {
			_client.CancelInterest_1(oncName);
			if (null != query.queryListener()) {
				query.queryListener().queryCanceled(query);
			}
		} catch (OncRpcException e) {
			Library.logger().warning("Exception in expressInterest RPC interface: " + e.getMessage());
			Library.warningStackTrace(e);
			// IOException(Throwable) constructor not present in 1.5
			throw new IOException("Exception in expressInterest RPC interface: " + e.getMessage());
		}
	}

}
