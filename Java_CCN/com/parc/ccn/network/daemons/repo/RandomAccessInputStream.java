package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

public class RandomAccessInputStream extends InputStream {

	protected RandomAccessFile underlying;
	
	public RandomAccessInputStream(RandomAccessFile f) {
		underlying = f;
	}

	@Override
	public int read() throws IOException {
		return underlying.read();
	}
	
	@Override
	public  int read(byte[] b) throws IOException {
		return underlying.read(b);
	}
	
	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		return underlying.read(b, off, len);
	}

}
