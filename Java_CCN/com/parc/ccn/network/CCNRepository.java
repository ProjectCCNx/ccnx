package com.parc.ccn.network;

import java.io.IOException;
import java.net.MalformedURLException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.logging.Level;

import javax.jmdns.ServiceInfo;

import com.parc.ccn.Library;
import com.parc.ccn.data.CCNBase;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.network.discovery.CCNDiscovery;

public abstract class CCNRepository implements CCNBase {

	public static final long SERVER_DISCOVERY_TIMEOUT = 1000;
	static final String SERVICE_SEPARATOR = "://";
	static final String NAME_SEPARATOR = "/";
	
	/**
	 * Stored to help tell whether this service matches
	 * a given remote service or not.
	 */
	protected ServiceInfo _info = null;
	
	public CCNRepository(ServiceInfo info) {
		// Connect to a remote repository discovered through
		// mDNS.
		_info = info;
	}
	
	public CCNRepository() {}
	
	/**
	 * Subclasses need to implement this, to say whether
	 * or not they come from the same representation as
	 * this one. The default constructor will handle simple
	 * cases.
	 * @param l
	 * @throws IOException
	 */
	public boolean equals(ServiceInfo otherInfo) {
		if ((null == otherInfo) || (null == _info))
			return false;
		return (otherInfo.getURL().equalsIgnoreCase(_info.getURL()));
	}
	
	public ServiceInfo info() { return _info; }
	
	public abstract void subscribe(CCNQueryListener l) throws IOException;
	public abstract void resubscribeAll() throws IOException;
	
	/**
	 * Get immediate results to a query.
	 * DKS: caution required to make sure that the idea of
	 * what matches here is the same as the one in coresponding version in 
	 * CCNQueryDescriptor. 
	 */
	public abstract ArrayList<ContentObject> get(CCNQueryDescriptor query) throws IOException;

	public abstract void logout();
	public abstract void login() throws IOException;
	public abstract void login(String username, String password) throws IOException;

	protected static void advertiseServer(String serviceType, int port) throws IOException {
		CCNDiscovery.advertiseServer(serviceType, port);
	}

	/**
	 * Connect to a remote CCN repository that we found out
	 * about via MDNS.
	 */
	public static CCNRepository connect(ServiceInfo info) throws MalformedURLException, ClassCastException, RemoteException, NotBoundException {
		if (null == info) {
			Library.logger().info("Cannot connect to null server.");
			return null;
		} else {
			Library.logger().info("Found CCN repository on " + info.getNiceTextString());
			try {
				return RepositoryFactory.connect(info);
			} catch (Exception e) {
				Library.logger().warning("Cannot connect to repository at host: " + 
						info.getHostAddress() + ":" + info.getPort() + " of type: " + info.getNiceTextString());
				Library.logStackTrace(Level.WARNING, e);
				return null;
			} 
		}
	}
	
	protected static String constructURL(String protocol, String serviceName,
										 String host, int port) {
		return new String(protocol + SERVICE_SEPARATOR + host + ":" + port + NAME_SEPARATOR + serviceName);
	}
}
