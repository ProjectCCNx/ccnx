package com.parc.ccn.library.io.net;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;


public class Handler extends URLStreamHandler {

	public Handler() {
		super();
	}

	@Override
	protected URLConnection openConnection(URL url) throws IOException {
		return new CCNURLConnection(url);
	}
	
	public static void register() {
		final String packageName =
			Handler.class.getPackage().getName();
		final String pkg = packageName.substring(0, packageName.lastIndexOf('.'));
		final String protocolPathProp = "java.protocol.handler.pkgs";

		String uriHandlers = System.getProperty(protocolPathProp, "");
		if (uriHandlers.indexOf(pkg) == -1) {
			if (uriHandlers.length() != 0)
				uriHandlers += "|";
			uriHandlers += pkg;
			System.setProperty(protocolPathProp, uriHandlers);
		}
	}
}
