/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Helper class -- an interior output stream for a FilterOutputStream
 * where the filter action is the only thing you care about (e.g.
 * a DigestOutputStream where you just want to do streaming input
 * into a digest with an OutputStream interface).
 */
public class NullOutputStream extends OutputStream {

	public NullOutputStream() {
		// Do nothing.
	}

	@Override
	public void write(int b) throws IOException {
		// Do nothing.
	}
	
	@Override
	public void write(byte [] b) throws IOException {
		// Do nothing.
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		// Do nothing.
	}

}
