package org.ccnx.ccn.impl.repo;

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
	/*
	@Override
	public boolean markSupported(){
		return true;
	}
	
	@Override
	public void mark(int readlimit){
		synchronized(underlying){
			try{
				mark = readlimit;
				position = underlying.getFilePointer();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	@Override
	public void reset(){
		synchronized(underlying){
			try {
				underlying.seek(position);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
*/
}
