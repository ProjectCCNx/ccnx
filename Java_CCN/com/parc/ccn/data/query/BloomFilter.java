package com.parc.ccn.data.query;

import java.util.Arrays;
import java.util.Random;

import com.parc.ccn.data.util.DataUtils;

/**
 * 
 * @author rasmusse
 * Implement bloom filter operations based on Michael Plass' C side implementation
 *
 */
public class BloomFilter implements Comparable<BloomFilter> {
	private int _lgBits;
	private int _nHash;
	private byte [] _seed;
	private byte [] _bloom = new byte[1024];
	private int _size = 0;
	
	public BloomFilter(int size, byte [] seed) {
		_seed = seed;
		_size = size;
		/*
		 * Michael's comment: try for about m = 12*n (m = bits in Bloom filter)
		 */
		_lgBits = 13;
		while (_lgBits > 3 && (1 << _lgBits) > size * 12)
           _lgBits--;
		/*
		 * Michael's comment: optimum number of hash functions is ln(2)*(m/n); use ln(2) ~= 9/13
		 */
		_nHash = (9 << _lgBits) / (13 * size + 1);
        if (_nHash < 2)
            _nHash = 2;           
        if (_nHash > 32)
            _nHash = 32;
	}
	
	/*
	 * Create a seed from random values
	 */
	public static void createSeed(byte[] seed) {
		Random rand = new Random();
		rand.nextBytes(seed);
	}
	
	/**
	 * For decoding
	 * @param bloom
	 */
	public BloomFilter(byte [] bloom) {
		_bloom = bloom;
	}
	
	public void insert(byte [] key) {
		long s = 0;
		int shift = 24;
		for (int i = 0; i < _seed.length && i < 4; i++) {
			s |= _seed[i] << shift;
			shift -= 8;
		}
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
	
	public int size() {
		return _size;
	}
	
	public byte[] bloom() {
		return _bloom;
	}
	
	public byte[] seed() {
		return _seed;
	}
	
	private long nextHash(long s, int u) {
		long k = 13; // Michael's comment: use this many bits of feedback shift output
	    long b = s & ((1 << k) - 1);
	    // Michael's comment: fsr primitive polynomial (modulo 2) x**31 + x**13 + 1
	    s = ((s >> k) ^ (b << (31 - k)) ^ (b << (13 - k))) + u;
	    return(s & 0x7FFFFFFF);
	}

	public int compareTo(BloomFilter o) {
		return DataUtils.compare(bloom(), o.bloom());
	}
	
	public boolean equals(BloomFilter o) {
		return Arrays.equals(bloom(), o.bloom());
	}
	
	private long computeSeed() {
		int u = ((_seed[0]) << 24) |((_seed[1]) << 16) |((_seed[2]) << 8) | (_seed[3]);
		return u & 0x7FFFFFFF;
	}

}
