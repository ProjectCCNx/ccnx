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
