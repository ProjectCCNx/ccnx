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

package org.ccnx.ccn.impl.security.crypto.jce;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.Wrapper;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.ccnx.ccn.impl.support.Log;


/**
 * RFC3394 requires that the key to be wrapped be a multiple of 8 bytes
 * in length. This poses challenges when wrapping private or public keys.
 * draft-housley-aes-key-wrap-with-pad-02.txt modifies RFC3394 to add
 * padding bytes, as supported in RFC3394 to remove this restriction.
 * This is an implementation of that Internet-Draft, which is not yet
 * supported by BouncyCastle. 
 * 
 * Code relies on BouncyCastle library for most of its infrastructure.
 */
public class RFC3394WrapWithPadEngine implements Wrapper {


	private BlockCipher _engine;
	private KeyParameter _parameters;
	private boolean _forWrapping;
	
	// Alternative IV is 64 bits, but changed from RFC3394. Top 8 bytes are
	// these, bottom 8 bytes are big-endian representation of length of key to be
	// wrapped.
	private int FIXED_IV = 4;
	private byte _iv[] = {(byte)0xA6, (byte)0x59, (byte)0x59, (byte)0xA6,
			              (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};

	public RFC3394WrapWithPadEngine(BlockCipher blockcipher) {
		_engine = blockcipher;
	}

	public void init(boolean flag, CipherParameters cipherparameters)
	{
		_forWrapping = flag;
		if (cipherparameters instanceof ParametersWithRandom) {
			cipherparameters = ((ParametersWithRandom)cipherparameters).getParameters();
		}
		if (cipherparameters instanceof KeyParameter) {
			_parameters = (KeyParameter)cipherparameters;
		} else if (cipherparameters instanceof ParametersWithIV) {
			_iv = ((ParametersWithIV)cipherparameters).getIV();
			_parameters = (KeyParameter)((ParametersWithIV)cipherparameters).getParameters();
			if (_iv.length != 8) {
				throw new IllegalArgumentException("IV length not equal to 8");
			}
		}
	}

	public String getAlgorithmName() {
		return _engine.getAlgorithmName();
	}

	public byte[] wrap(byte[] input, int offset, int length) {

		if (!_forWrapping) {
			throw new IllegalStateException("Not initialized for wrapping!");
		}
		
		byte [] lengthbytes = new byte[] { (byte)(length>>24), (byte)(length>>16), (byte)(length>>8), (byte)length};
		System.arraycopy(lengthbytes, 0, _iv, FIXED_IV, lengthbytes.length);
		
		int n = length / 8;

		Log.info("wrap: wrapping key of length " + length + ", "+ n + " blocks.");
		
		if ((n * 8) != length) {
			// pad up to a multiple of 8 bytes
			n++;
			byte [] paddedinput = new byte[n*8];
			System.arraycopy(input, offset, paddedinput, 0, length);
			Log.info("RFC3394WrapWithPadEngine: adding padding of " + (paddedinput.length - input.length) + " bytes.");
			// this leaves the last bytes of padded input containing sufficient 0 bytes to pad to 
			// a multiple of 64 bits
			input = paddedinput;
			length = paddedinput.length;
		}

		byte[]  block = new byte[length + _iv.length];
		byte[]  buf = new byte[8 + _iv.length];

		System.arraycopy(_iv, 0, block, 0, _iv.length);
		System.arraycopy(input, 0, block, _iv.length, length);

		_engine.init(true, _parameters);

		for (int j = 0; j != 6; j++) {
			for (int i = 1; i <= n; i++) {
				System.arraycopy(block, 0, buf, 0, _iv.length);
				System.arraycopy(block, 8 * i, buf, _iv.length, 8);
				_engine.processBlock(buf, 0, buf, 0);

				int t = n * j + i;
				for (int k = 1; t != 0; k++) {
					byte v = (byte)t;

					buf[_iv.length - k] ^= v;

					t >>>= 8;
				}

				System.arraycopy(buf, 0, block, 0, 8);
				System.arraycopy(buf, 8, block, 8 * i, 8);
			}
		}

		return block;
	}

	public byte[] unwrap(byte [] input, int offset, int length)
			throws InvalidCipherTextException {

		if (_forWrapping) {
			throw new IllegalStateException("Not initialized for unwrapping!");
		}

		int n = length / 8;

		if ((n * 8) != length) {
			throw new InvalidCipherTextException("unwrap data must be a multiple of 8 bytes");
		}

		byte[]  block = new byte[length - _iv.length];
		byte[]  a = new byte[_iv.length];
		byte[]  buf = new byte[8 + _iv.length];

		System.arraycopy(input, 0, a, 0, _iv.length);
		System.arraycopy(input, _iv.length, block, 0, length - _iv.length);

		_engine.init(false, _parameters);

		n = n - 1;

		for (int j = 5; j >= 0; j--) {
			for (int i = n; i >= 1; i--) {
				System.arraycopy(a, 0, buf, 0, _iv.length);
				System.arraycopy(block, 8 * (i - 1), buf, _iv.length, 8);

				int t = n * j + i;
				for (int k = 1; t != 0; k++) {
					byte v = (byte)t;

					buf[_iv.length - k] ^= v;

					t >>>= 8;
				}

				_engine.processBlock(buf, 0, buf, 0);
				System.arraycopy(buf, 0, a, 0, 8);
				System.arraycopy(buf, 8, block, 8 * (i - 1), 8);
			}
		}

		for (int i = 0; i < FIXED_IV; i++) {
			if (a[i] != _iv[i]) {
				throw new InvalidCipherTextException("Checksum failed to verify!");
			}
		}
		int expectedLength = (a[FIXED_IV] << 24) + ((a[FIXED_IV+1] & 0xFF) << 16)
							+ ((a[FIXED_IV+2] & 0xFF) << 8) + (a[FIXED_IV+3] & 0xFF);

		int maxBlockLength = 8*n;
		if ((expectedLength < (maxBlockLength-8)) || (expectedLength > maxBlockLength)) {
			throw new InvalidCipherTextException("Invalid checksum length: got: " + block.length + " expected: " + 
					expectedLength + " max: " + maxBlockLength + " n: " + n);
		}
		int b = (maxBlockLength - expectedLength) % 8;
		if (block.length != (expectedLength + b)) {
			throw new InvalidCipherTextException("Invalid checksum length: got: " + block.length + " expected: " + 
					expectedLength + " b: " + b + " max: " + maxBlockLength + " n: " + n);
		}		
		for (int i=expectedLength; i < block.length; ++i) {
			if (block[i] != 0) {
				throw new InvalidCipherTextException("Invalid padding: byte " + i + " is " + Integer.toHexString(0x000000FF&block[i]));				
			}
		}
		
		// Strip padding
		if (b > 0) {
			byte [] trimmedBlock = new byte[expectedLength];
			System.arraycopy(block, 0, trimmedBlock, 0, expectedLength);
			block = trimmedBlock;
		}
		return block;
	}
}

