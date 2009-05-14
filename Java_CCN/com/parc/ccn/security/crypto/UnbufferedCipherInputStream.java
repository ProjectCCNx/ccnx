package com.parc.ccn.security.crypto;

import java.io.IOException;
import java.io.InputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;

/**
 * Java's CipherInputStream exposes the buffering done to accommodate a block
 * Cipher, and hence reads/skip/available only return the data available in the current
 * Cipher block, rather than the perhaps larger amount of data expected from the underlying
 * stream. This class wraps CipherInputStream to provide more natural semantics, and
 * avoid having repeated code to handle incomplete reads/etc.
 */
public class UnbufferedCipherInputStream extends CipherInputStream {

	protected int blockSize;

	public UnbufferedCipherInputStream(InputStream in, Cipher c) {
		super(in, c);
		blockSize = c.getBlockSize();
	}

	@Override
	public int read(byte [] data, int off, int len) throws IOException {
		int read = 0;
		int res;
		
		while (len > 0) {
			res = super.read(data, off, len);
			if (res <= 0)
				return read>0?read:res;
			off += res;
			len -= res;
			read += res;
		}
		return read;
	}

	@Override
	public int available() throws IOException {
		return super.available() + in.available();
	}

	@Override
	public long skip(long bytes) throws IOException {
		long skipped = 0;
		long res;

		while (bytes > 0) {
			res = super.skip(bytes);
			bytes -= res;
			skipped += res;
			if (bytes > 0) {
				int c = super.read();
				if (c < 0)
					return skipped>0?skipped:c;
				bytes--;
				skipped++;
			}
		}
		return skipped;
	}
}
