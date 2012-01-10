/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.support;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Routes output from the daemon stdout/stderr to a file
 */
public class DaemonOutput extends Thread {
	private InputStream _is;
	private OutputStream _os;
	
	public DaemonOutput(InputStream is, OutputStream os) throws FileNotFoundException {
		_is = is;
		_os = os;
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
	
	public void close() throws IOException {
		try {
			_os.close();
		} finally {
			_is.close();
		}
	}
}
