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
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * RandomAccessInputStream extends InputStream to allow random reads
 * of the backend repository RandomAccessFile.
 * 
 * This class is intended for backend repository use and is not meant
 * for general CCN stream use.
 * 
 * 
 * @see InputStream
 * @see RandomAccessFile
 */
public class RandomAccessInputStream extends InputStream {

	protected RandomAccessFile underlying;

	/**
	 * Constructor to set the backend repository file for random reads.
	 * @param f Backend RandomAccessFile
	 */
	public RandomAccessInputStream(RandomAccessFile f) {
		underlying = f;
	}

	/**
	 * Method to implement the read() method for the abstract class InputStream.
	 * When called, this method returns the next byte of the file.
	 * 
	 * @return int Next byte of the file
	 * 
	 * @see InputStream.read()
	 * 
	 * @throws IOException
	 */
	@Override
	public int read() throws IOException {
		return underlying.read();
	}
	
	/**
	 * Method to read some number of bytes into the byte[] b.  The number of bytes
	 * read is returned as an integer. If no bytes are read, the method returns -1.
	 * 
	 * @param b byte[] to read data into
	 * 
	 * @return int Number of bytes read (-1 if no more data is available)
	 * 
	 * @throws IOException
	 */
	@Override
	public  int read(byte[] b) throws IOException {
		return underlying.read(b);
	}
	
	
	/**
	 * Method to read len bytes into byte[] b starting at a specific offset.
	 * 
	 * @param b byte[] to read bytes into
	 * @param off starting position for reading
	 * @param len number of bytes to read into the byte array
	 * 
	 * @return int number of bytes read into the byte array
	 * 
	 * @throws IOException
	 */
	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		return underlying.read(b, off, len);
	}
}
