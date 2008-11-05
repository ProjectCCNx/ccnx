package com.parc.ccn.data.query;

/**
 * 
 * @author rasmusse
 * Implement bloom filter operations based on Michael Plass' C side implementation
 *
 */
public class BloomFilter implements Comparable<BloomFilter> {
	private int lgBits;
	private int nHash;
	private byte [] seed;
	private byte [] bloom = new byte[1024];
	private int size = 0;
	
	public BloomFilter(int size, byte [] seed) {
		this.seed = seed;
		/*
		 * Michael's comment: try for about m = 12*n (m = bits in Bloom filter)
		 */
		lgBits = 13;
		while (lgBits > 3 && (1 << lgBits) > size * 12)
           lgBits--;
		/*
		 * Michael's comment: optimum number of hash functions is ln(2)*(m/n); use ln(2) ~= 9/13
		 */
		nHash = (9 << lgBits) / (13 * size + 1);
        if (nHash < 2)
            nHash = 2;           
        if (nHash > 32)
            nHash = 32;
	}
	
	public void insert(byte [] key) {
		int s = 0;
		int shift = 24;
		for (int i = 0; i < seed.length && i < 4; i++) {
			s |= seed[i] << shift;
			shift -= 8;
		}
		for (int i = 0; i < key.length; i++) 
			s = nextHash(s, key[i] + 1);
		int m = (8*bloom.length - 1) & ((1 << lgBits) - 1);
		for (int i = 0; i < nHash; i++) {
			 s = nextHash(s, 0);
		     int h = s & m;
		     if ((bloom[h >> 3] & (1 << (h & 7))) == 0) {
		    	 bloom[h >> 3] |= (1 << (h & 7));
		     }
		}
		size++;
	}
	
	public int size() {
		return size;
	}
	
	public byte[] bloom() {
		return bloom;
	}
	
	public byte[] seed() {
		return seed;
	}
	
	private int nextHash(int s, int u) {
		int k = 13; /* Michael's comment: use this many bits of feedback shift output */
	    int b = s & ((1 << k) - 1);
	    /* Michael's comment: fsr primitive polynomial (modulo 2) x**31 + x**13 + 1 */
	    s = ((s >> k) ^ (b << (31 - k)) ^ (b << (13 - k))) + u;
	    return(s & 0x7FFFFFFF);
	}

	public int compareTo(BloomFilter o) {
		// TODO Auto-generated method stub
		return 0;
	}

}
