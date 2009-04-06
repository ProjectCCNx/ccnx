package test.ccn.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class TeedOutputStream extends FilterOutputStream {
	
	OutputStream _copy;
	
	public TeedOutputStream(OutputStream outputStream, OutputStream copyStream) {
		super(outputStream);
		if ((null == outputStream) || (null == copyStream)) {
			throw new IllegalArgumentException("Arguments cannot be null!");
		}
		_copy = copyStream;
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		super.write(b, off, len);
		_copy.write(b, off, len);
	}

	@Override
	public void write(byte[] b) throws IOException {
		super.write(b);
		_copy.write(b);
	}

	@Override
	public void write(int b) throws IOException {
		super.write(b);
		_copy.write(b);
	}
	
	@Override
	public void close() throws IOException {
		super.close();
		_copy.close();
	}

	@Override
	public void flush() throws IOException {
		super.flush();
		_copy.flush();
	}

}
