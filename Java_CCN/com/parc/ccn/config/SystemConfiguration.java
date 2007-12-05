package com.parc.ccn.config;

public class SystemConfiguration {

	protected static final int DEFAULT_REPO2TRANSPORT_PORT = 43567;
	protected static final int DEFAULT_TRANSPORT2REPO_PORT = 43568;

	public static int defaultTransportPort() { return DEFAULT_REPO2TRANSPORT_PORT; }

	public static int defaultRepositoryPort() { return DEFAULT_TRANSPORT2REPO_PORT; }

	public static String getLocalHost() {
//		InetAddress.getLocalHost().toString(),
		return "127.0.0.1"; // using InetAddress.getLocalHost gives bad results
	}
}
