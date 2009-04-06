package test.ccn.util;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class TeedInputStream extends FilterInputStream {
	
	OutputStream _copy;
	
	public TeedInputStream(InputStream inputStream, OutputStream copyStream) throws FileNotFoundException {
		super(inputStream);
		if ((null == copyStream) || (null == inputStream)) {
			throw new IllegalArgumentException("Cannot have null arguments!");
		}
		_copy = copyStream;
	}

	@Override
	public int read() throws IOException {
		int val = super.read();
		if (val == -1) { // EOF
			_copy.flush();
			_copy.close();
		}
		return val; // stream has already converted byte to int
	}	

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int result = super.read(b, off, len);
		if (result >= 0) {
			_copy.write(b, off, result);
		}
		return result;
	}

	@Override
	public int read(byte[] b) throws IOException {
		int result = super.read(b);
		if (result >= 0) {
			_copy.write(b, 0, result);
		}
		return result;
	}

	/**
	 * Only flushes output.
	 */
	public void flush() throws IOException {
		_copy.flush();
	}
	
	/**
	 * Only closes output.
	 */
	public void close() throws IOException {
		_copy.close();
	}
}
