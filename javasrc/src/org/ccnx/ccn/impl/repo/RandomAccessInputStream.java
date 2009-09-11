/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
