package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class RandomAccessOutputStream extends OutputStream {

	protected RandomAccessFile underlying;
	
	public RandomAccessOutputStream(RandomAccessFile f) {
		underlying = f;
	}

	@Override
	public void write(int b) throws IOException {
		underlying.write(b);
	}
	
	@Override
	public void write(byte[] b) throws IOException {
		underlying.write(b);
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		underlying.write(b, off, len);
	}

}
