package com.parc.ccn.network.daemons;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Needed to route output from the daemon stdout/stderr to a file
 * @author rasmusse
 *
 */
public class DaemonOutput extends Thread {
	private InputStream _is;
	private OutputStream _os;
	
	public DaemonOutput(InputStream is, String outputFile) throws FileNotFoundException {
		this(is, outputFile, false);
	}
	
	public DaemonOutput(InputStream is, String outputFile, boolean append) throws FileNotFoundException {
		_is = is;
		_os = new FileOutputStream(outputFile, append);
		this.start();
	}
	
	public void run() {
		while (true) {
			try {
				while (_is.available() > 0) {
					int size = _is.available();
					byte[] b = new byte[size];
					_is.read(b, 0, size);
					_os.write(b);
					_os.flush();
				}
				Thread.sleep(1000);
			} catch (IOException e) {
				return;
			} catch (InterruptedException e) {}
		}
	}
}
