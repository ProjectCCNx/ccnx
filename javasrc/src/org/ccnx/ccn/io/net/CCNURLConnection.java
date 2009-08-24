package org.ccnx.ccn.io.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class CCNURLConnection extends URLConnection {

	public CCNURLConnection(URL url) {
		super(url);
	}

	@Override
	public void connect() throws IOException {
	}
	
	@Override
	public InputStream getInputStream() throws IOException {
		ContentName thisName = null;
		try {
			thisName = ContentName.fromURI(this.url.toString());
			return new CCNInputStream(thisName);
		} catch (MalformedContentNameStringException e) {
			Log.logger().info("Cannot parse URI: " + this.url);
			throw new IOException("Cannot parse URI: " + this.url + ": " + e.getMessage());
		} catch (XMLStreamException e) {
			Log.logger().info("Cannot parse XML: " + e.getMessage());
			throw new IOException("Cannot parse XML.: " + e.getMessage());
		}
	}
}
