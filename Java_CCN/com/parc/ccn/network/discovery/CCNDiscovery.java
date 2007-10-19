package com.parc.ccn.network.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.logging.Level;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import com.parc.ccn.Library;

public class CCNDiscovery {
	
	/**
	 * Need slightly more principled way to cover a variety of
	 * service types.
	 * DKS - check synchronization
	 */
	
	protected static ArrayList<String> _serviceTypes = new ArrayList<String>();
	public static final String SERVICE_NAME = "CCN";
	public static final int CCN_PORT = 5399;
	
	protected static JmDNS _jmDNS = null;
	protected static ArrayList<CCNDiscoveryListener> _discoveryListeners = new ArrayList<CCNDiscoveryListener>();
	protected static ServiceListener _serviceListener = null;
	protected static ArrayList<ServiceInfo> _ccnServiceInfos = new ArrayList<ServiceInfo>();
	protected static ArrayList<ServiceInfo> _localCCNServiceInfos = new ArrayList<ServiceInfo>();
	
	public static class CCNServiceListener implements ServiceListener {

		public CCNServiceListener() {
		}
		
		public void serviceAdded(ServiceEvent event) {
			return; // don't care
		}

		public void serviceRemoved(ServiceEvent event) {
			// service went away -- pass it up the food chain
			ServiceInfo info = event.getInfo();
			if (_localCCNServiceInfos.contains(info)) {
				_localCCNServiceInfos.remove(info);
				Library.logger().warning("A local CCN service " + event.getName() + " is shutting down.");
			}
			if (_ccnServiceInfos.contains(info)) {
				_ccnServiceInfos.remove(info);

				// only tell people if we've told them about it already
				for (CCNDiscoveryListener dl : _discoveryListeners) {
					dl.serviceRemoved(event.getInfo(), isLocal(event.getInfo().getInetAddress()));
				}
			}
			Library.logger().warning("A CCN service " + event.getName() + " is shutting down.");
		}

		public void serviceResolved(ServiceEvent event) {
			if (null != event.getInfo()) {
				Library.logger().info("Found a CCN service: " + event.getName());
				// If it's new, tell everybody.
				if (!_ccnServiceInfos.contains(event.getInfo())) {
					_ccnServiceInfos.add(event.getInfo());
					for (CCNDiscoveryListener dl : _discoveryListeners) {
						dl.serviceAdded(event.getInfo(), isLocal(event.getInfo().getInetAddress()));
					}
				}
			}
		}
	}
	
	static {
		if (null == _jmDNS) {
			try {
				_jmDNS = new JmDNS();
				for (int i=0; i < _serviceTypes.size(); ++i)
					_jmDNS.registerServiceType(_serviceTypes.get(i));
			} catch (IOException e) {
				Library.logger().warning("Cannot create discovery object.");
				Library.logStackTrace(Level.WARNING, e);
			}
		}
	}
	
	public static void registerServiceType(String serviceType) {
		_serviceTypes.add(serviceType);
		_jmDNS.registerServiceType(serviceType);
		if (null != _serviceListener) {
			for (int i=0; i < _serviceTypes.size(); ++i) {
				_jmDNS.addServiceListener(serviceType, _serviceListener);
			}			
		}
	}
	
	public static void advertiseServer(String serviceType, int port) throws IOException {
		ServiceInfo si = getServiceInfo(serviceType, localHostName(), port);
		_jmDNS.registerService(si);
		_localCCNServiceInfos.add(si);
		Library.logger().info("Advertising server: " + si.toString());
	}
	
	public static ServiceInfo getServiceInfo(String serviceType, String host, int port) {
		if (null == host) {
			host = localHostName();
		}
		return new ServiceInfo(serviceType,
				host + "'s " + 
					SERVICE_NAME, 
				port, "");		
	}
	
	public static void findServers(CCNDiscoveryListener discoveryListener) {
		if (null == _serviceListener) {
			synchronized(CCNDiscovery.class) {
				if (null == _serviceListener) {
					addDiscoveryListener(discoveryListener);
					_serviceListener = new CCNServiceListener();
					for (int i=0; i < _serviceTypes.size(); ++i) {
						_jmDNS.addServiceListener(_serviceTypes.get(i), _serviceListener);
					}
				}
			}
		}
	}
	
	public static void addDiscoveryListener(CCNDiscoveryListener dl) {
		if (null != dl) {
			_discoveryListeners.add(dl);
			
			// Tell the new listener about the services we currently know about.
			for (ServiceInfo serviceInfo : _ccnServiceInfos) {
				// DKS -- potential for deadlock?
				dl.serviceAdded(serviceInfo, isLocal(serviceInfo.getInetAddress()));
			}
		}
	}

	public static void shutdown() {
		if (_serviceListener != null) {
			for (int i=0; i < _serviceTypes.size(); ++i) {
				_jmDNS.removeServiceListener(_serviceTypes.get(i), _serviceListener);
			}
		}
		_jmDNS.unregisterAllServices();
	}
	
	public static boolean isLocal(InetAddress inetAddress) {
		try {
			InetAddress [] localAddresses = 
					InetAddress.getAllByName(InetAddress.getLocalHost().getHostName());
			if (null == localAddresses) {
				Library.logger().info("Can't tell if address " + inetAddress + " is local, no apparent local addresses.");
				return false;
			}
			for (int i=0; i < localAddresses.length; ++i) {
				if (inetAddress.equals(localAddresses[i]))
					return true;
			}
		} catch (UnknownHostException e) {
			try {
				InetAddress localhost = InetAddress.getLocalHost();
				return inetAddress.equals(localhost);
			} catch (UnknownHostException e1) {
				Library.logger().info("Can't tell if address " + inetAddress + " is local.");
				return false;
			}
		}
		return false;
	}

	public static String localHostName() {
		try {
			InetAddress localMachine = InetAddress.getLocalHost();	
			return localMachine.getHostName();
		} catch (UnknownHostException uhe) {
			return "localhost";
		}
	}
}
