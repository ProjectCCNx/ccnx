package com.parc.ccn.network.impl;

import java.io.IOException;
import java.util.ArrayList;

import javax.jmdns.ServiceInfo;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.query.CCNQueryListener.CCNQueryType;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.network.CCNRepository;
import com.parc.ccn.network.CCNRepositoryFactory;
import com.parc.ccn.network.discovery.CCNDiscovery;

/**
 * This is the local frontend for a remote repository
 * speaking the network sync protocol. Right now the
 * interface is CCNRepository, might want to make
 * a separate protocol interface. The interest mgr
 * calls this inerface, it speaks out its backend
 * over the network to anothre server (wanted something
 * more generic than talking directly to a jackrabbit).
 * 
 * Interest mgr should speak only to these.
 * @author smetters
 *
 */
public class NetworkCCNRepository extends CCNRepository {

	/**
	 * A node speaking the CCN network interest sync protocol.
	 */
	public static final String NETWORK_CCN_SERVICE_TYPE = "_ccn._interest._xml";
	public static final String CCN_NETWORK_SERVICE_NAME = "CCN Network Repository";

	static {
		CCNRepositoryFactory.registerRepositoryType(NETWORK_CCN_SERVICE_TYPE, CCN_NETWORK_SERVICE_NAME, NetworkCCNRepository.class);
		CCNDiscovery.registerServiceType(NETWORK_CCN_SERVICE_TYPE);
	}

	public NetworkCCNRepository(ServiceInfo info) {
		super(info);
		// TODO Auto-generated constructor stub
	}

	public NetworkCCNRepository() {
		// TODO Auto-generated constructor stub
	}

	@Override
	public ArrayList<ContentObject> get(CCNQueryDescriptor query)
			throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void login() throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void login(String username, String password) throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void logout() {
		// TODO Auto-generated method stub

	}

	@Override
	public void resubscribeAll() throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void subscribe(CCNQueryListener l) throws IOException {
		// TODO Auto-generated method stub

	}

	public void cancel(CCNQueryDescriptor query) throws IOException {
		// TODO Auto-generated method stub

	}

	public CCNQueryDescriptor get(ContentName name,
			ContentAuthenticator authenticator, CCNQueryType type,
			CCNQueryListener listener, long TTL) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public ArrayList<ContentObject> get(ContentName name, ContentAuthenticator authenticator, CCNQueryType type) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public void put(ContentName name, ContentAuthenticator authenticator,
			byte[] content) throws IOException {
		// TODO Auto-generated method stub

	}


}
