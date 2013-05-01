/*
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
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * RandomAccessOutputStream extends OutputStream to allow writing
 * to the RandomAccessFile that is the backend of the repository.
 * 
 * This class is intended to be used for writing to the backend
 * repository and not general CCN stream use.
 * 
 * @see OutputStream
 * @see RandomAccessFile
 */
public class RandomAccessOutputStream extends OutputStream {

	protected RandomAccessFile underlying;
	
	/**
	 * Method to set the backend RandomAccessFile for writing
	 * 
	 * @param f Backend RandomAccessFile
	 */
	
	public RandomAccessOutputStream(RandomAccessFile f) {
		underlying = f;
	}

	
	/**
	 * Method implementing write one byte at a time.
	 * 
	 * @param b byte to be written
	 * 
	 * @return void
	 * 
	 * @throws IOException
	 * 
	 * @see OutputStream
	 * @see RandomAccessFile
	 */
	@Override
	public void write(int b) throws IOException {
		underlying.write(b);
	}
	
	
	/**
	 * Method to write a byte array to the underlying repository file.
	 * 
	 * @param b byte[] to write to the file
	 * 
	 * @return void
	 * 
	 * @throws IOException
	 * 
	 * @see OutputStream
	 * @see RandomAccessFile
	 */
	@Override
	public void write(byte[] b) throws IOException {
		underlying.write(b);
	}
	
	
	/**
	 * Method to write a byte array of len bytes to the underlying repository file starting at offset off.
	 * 
	 * @param b byte[] to write to the file
	 * @param off Offset to start writing in the file
	 * @param len number of bytes to write from the byte[] b
	 * 
	 * @return void
	 * 
	 * @throws IOException
	 * 
	 * @see OutputStream
	 * @see RandomAccessFile
	 */
	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		underlying.write(b, off, len);
	}

}
