package com.parc.ccn.library.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Meant to be an interior output stream for a FilterOutputStream
 * where the filter action is the only thing you care about (e.g.
 * a DigestOutputStream where you just want to do streaming input
 * into a digest with an OutputStream interface).
 * @author smetters
 *
 */
public class NullOutputStream extends OutputStream {

	public NullOutputStream() {
		// Do nothing.
	}

	@Override
	public void write(int b) throws IOException {
		// Do nothing.
	}

}
