package com.parc.ccn.library.io;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;

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
			Library.logger().info("Cannot parse URI: " + this.url);
			throw new IOException("Cannot parse URI: " + this.url + ": " + e.getMessage());
		} catch (XMLStreamException e) {
			Library.logger().info("Cannot parse XML: " + e.getMessage());
			throw new IOException("Cannot parse XML.: " + e.getMessage());
		}
	}
}
