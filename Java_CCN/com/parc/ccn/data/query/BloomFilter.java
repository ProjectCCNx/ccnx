package com.parc.ccn.data.query;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * 
 * @author rasmusse
 * Implement bloom filter operations based on Michael Plass' C side implementation
 */
public class BloomFilter implements Comparable<BloomFilter> {
	private int _lgBits;
	private int _nHash;
	
	/*
	 * I am using a short for seed internally - even though it's
	 * supposed to be a byte array - to get around unsigned arithmetic 
	 * issues. Is there a better way to handle this?
	 */
	private short [] _seed;
	private byte [] _bloom = new byte[1024];
	private int _size = 0;
	
	public BloomFilter(int size, byte [] seed) {
		_seed = new short[seed.length];
		for (int i = 0; i < seed.length; i++)
			_seed[i] = (short)((seed[i]) & 0xff);
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
	 */
	public BloomFilter() {}
	
	public void insert(byte [] key) {
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
		byte [] outSeed = new byte[_seed.length];
		for (int i = 0; i < _seed.length; i++)
			outSeed[i] = (byte) _seed[i];
		return outSeed;
	}
	
	private long nextHash(long s, int u) {
		long k = 13; // Michael's comment: use this many bits of feedback shift output
	    long b = s & ((1 << k) - 1);
	    // Michael's comment: fsr primitive polynomial (modulo 2) x**31 + x**13 + 1
	    s = ((s >> k) ^ (b << (31 - k)) ^ (b << (13 - k))) + u;
	    return(s & 0x7FFFFFFF);
	}
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		ByteArrayInputStream bais = new ByteArrayInputStream(decoder.readBinaryElement(ExcludeElement.BLOOM));
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
	}
	
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write((byte)_lgBits);
		baos.write((byte)_nHash);
		baos.write('A');	// "method" - must be 'A' for now
		baos.write(0);		// "reserved" - must be 0 for now
		for (int i = 0; i < _seed.length; i++)
			baos.write((byte)_seed[i]);
		int size = 1 << (_lgBits - 3);
		for (int i = 0; i < size; i++)
			baos.write(_bloom[i]);
		encoder.writeElement(ExcludeElement.BLOOM, baos.toByteArray());
	}

	public int compareTo(BloomFilter o) {
		return DataUtils.compare(bloom(), o.bloom());
	}
	
	public boolean equals(BloomFilter o) {
		return Arrays.equals(bloom(), o.bloom());
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

}
