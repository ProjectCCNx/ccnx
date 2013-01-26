/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;


/**
 * Deprecated - use named exclude elements instead.
 * 
 * Implement bloom filter operations
 * 
 * Bloom filters are used to exclude keys that are inserted into the filter
 */
@Deprecated
public class BloomFilter extends Exclude.Filler implements Comparable<BloomFilter> {

	private int _lgBits;
	private int _nHash;
	
	// I am using a short for seed internally - even though it's
	// supposed to be a byte array - to get around unsigned arithmetic 
	// issues.
	private short [] _seed;
	private byte [] _bloom = new byte[1024];
	private int _size = 0;
	
	/**
	 * Constructor
	 * @param estimatedMembers The performance of the bloom filter can be improved by accurately
	 * 			estimating the number of members that will be inserted into it. Too low a number
	 * 			will increase the likelihood of false positives. Too high a number will cause the
	 * 			filter to be larger than necessary, impacting performance. It is better for this
	 * 			number to be too low than too high.
	 * @param seed Random seed data must be of length 4
	 */
	public BloomFilter(int estimatedMembers, byte [] seed) {
		if (seed.length != 4)
			throw new IllegalArgumentException("Bloom seed length must be 4"); // for now
		_seed = new short[seed.length];
		for (int i = 0; i < seed.length; i++)
			_seed[i] = (short)((seed[i]) & 0xff);
		
		 // Michael's comment: try for about m = 12*n (m = bits in Bloom filter)
		_lgBits = 13;
		while (_lgBits > 3 && (1 << _lgBits) > estimatedMembers * 12)
           _lgBits--;
		 // Michael's comment: optimum number of hash functions is ln(2)*(m/n); use ln(2) ~= 9/13
		_nHash = (9 << _lgBits) / (13 * estimatedMembers + 1);
        if (_nHash < 2)
            _nHash = 2;           
        if (_nHash > 32)
            _nHash = 32;
	}
	
	/**
	 * Create a seed from random values
	 * @return the seed
	 */
	public static byte[] createSeed() {
		byte[] seed = new byte[4];
		Random rand = new Random();
		rand.nextBytes(seed);
		return seed;
	}
	
	/**
	 * For decoding
	 */
	public BloomFilter() {} 
	
	/**
	 * Insert a key 
	 * @param key a key to exclude
	 */
	public void insert(byte [] key) {
		if (_size < 0)
			throw new IllegalArgumentException("Can't reuse bloomfilter from the network");
		long s = computeSeed();
		for (int i = 0; i < key.length; i++) 
			s = nextHash(s, key[i] + 1);
		long m = (8*_bloom.length - 1) & ((1 << _lgBits) - 1);
		for (int i = 0; i < _nHash; i++) {
			 s = nextHash(s, 0);
		     long h = s & m;
		     if ((_bloom[(int)(h >> 3)] & (1 << (h & 7))) == 0) {
		    	 _bloom[(int)(h >> 3)] |= (1 << (h & 7));
		     }
		}
		_size++;
	}
	
	/**
	 * Test if the bloom filter matches a particular key.
	 * Note - a negative result means the key was definitely not set, but a positive result only means the
	 * key was likely set.
	 * @param key key to test
	 * @return false if not set
	 */
	public boolean match(byte [] key) {
		int m = ((8*_bloom.length) - 1) & ((1 << _lgBits) - 1);
		long s = computeSeed();
		for (int k = 0; k < key.length; k++)
			s = nextHash(s, key[k] + 1);
		for (int i = 0; i < _nHash; i++) {
			s = nextHash(s, 0);
			long h = s & m;
			if (0 == (_bloom[(int)h >> 3] & (1 << (h & 7))))
				return false;
		}
		return true;
	}
	
	/**
	 * Returns the value given on creation by estimatedMembers 
	 * @see BloomFilter.BloomFilter
	 * @return the estimated members of this filter
	 */
	public int size() {
		return _size;
	}
	
	/**
	 * Get a copy of the seed
	 * @return copy of seed
	 */
	public byte[] seed() {
		byte [] outSeed = new byte[_seed.length];
		System.arraycopy(_seed, 0, outSeed, 0, _seed.length);
		return outSeed;
	}
	
	private long nextHash(long s, int u) {
		long k = 13; // Michael's comment: use this many bits of feedback shift output
	    long b = s & ((1 << k) - 1);
	    // Michael's comment: fsr primitive polynomial (modulo 2) x**31 + x**13 + 1
	    s = ((s >> k) ^ (b << (31 - k)) ^ (b << (13 - k))) + u;
	    return(s & 0x7FFFFFFF);
	}
	
	private int usedBits() {
		return 1 << (_lgBits - 3);
	}
	
	/**
	 * Gets the type of element this is within an exclude filter
	 */
	@Override
	public long getElementLabel() { return CCNProtocolDTags.Bloom; }
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		ByteArrayInputStream bais = new ByteArrayInputStream(decoder.readBinaryElement(getElementLabel()));
		_lgBits = bais.read();
		_nHash = bais.read();
		bais.skip(2); // method & reserved - ignored for now
		_seed = new short[4];
		for (int i = 0; i < _seed.length; i++)
			_seed[i] = (byte)bais.read();
		for (int i = 0; i < _seed.length; i++)
			_seed[i] = (short)((_seed[i]) & 0xff);
		int i = 0;
		while (bais.available() > 0) 
			_bloom[i++] = (byte)bais.read();
		// DKS decoding check
		if (i != usedBits()) {
			Log.warning("Unexpected result in decoding BloomFilter: expecting " + usedBits() + " bytes, got " + i);
		}
		_size = -1;
	}
	
	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write((byte)_lgBits);
		baos.write((byte)_nHash);
		baos.write('A');	// "method" - must be 'A' for now
		baos.write(0);		// "reserved" - must be 0 for now
		for (int i = 0; i < _seed.length; i++)
			baos.write((byte)_seed[i]);
		int size = usedBits();
		for (int i = 0; i < size; i++)
			baos.write(_bloom[i]);
		encoder.writeElement(getElementLabel(), baos.toByteArray());
	}

	public int compareTo(BloomFilter o) {
		return DataUtils.compare(_bloom, o._bloom);
	}
	
	private long computeSeed() {
		long u = ((_seed[0]) << 24) |((_seed[1]) << 16) |((_seed[2]) << 8) | (_seed[3]);
		return u & 0x7FFFFFFF;
	}
	
	public BloomFilter clone() throws CloneNotSupportedException {
		BloomFilter result = (BloomFilter)super.clone();
		result._seed = _seed.clone();
		result._bloom = _bloom.clone();
		return result;
	}

	@Override
	public boolean validate() {
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(_bloom);
		result = prime * result + _lgBits;
		result = prime * result + _nHash;
		result = prime * result + Arrays.hashCode(_seed);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		BloomFilter other = (BloomFilter) obj;
		if (_lgBits != other._lgBits)
			return false;
		if (_nHash != other._nHash)
			return false;
		// Only compare the number of bytes of _bloom in use. Decoder
		// may make _bloom array a different length than was set.
		if (0 != DataUtils.bytencmp(_bloom, other._bloom, usedBits())) {
			return false;
		}
		if (!Arrays.equals(_seed, other._seed))
			return false;
		return true;
	}

}
