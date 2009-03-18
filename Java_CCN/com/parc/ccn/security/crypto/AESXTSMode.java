package com.parc.ccn.security.crypto;

import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;

/** 
 * Based on OpenBSD's implementation of AES XTS mode.
 **/
public class AESXTSMode extends CipherSpi {

    protected static final int XTS_BLOCKSIZE = 16;
    protected static final int TWEAK_LEN = 8;
    protected static final int XTS_ALPHA = 0x87; /* GF(2^128) generator polynomial value */

    protected int _opmode; // ENCRYPT_MODE, DECRYPT_MODE, WRAP_MODE or UNWRAP_MODE
    protected byte [] _tweak;
    protected Cipher _aesEngine;
    
	public AESXTSMode() {
		// TODO Auto-generated constructor stub
	}

	/**
	 * @returns new buffer with result
	 */
	@Override
	protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
			throws IllegalBlockSizeException, BadPaddingException {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @returns number of bytes stored in output
	 */
	@Override
	protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output,
			int outputOffset) throws ShortBufferException, IllegalBlockSizeException,
			BadPaddingException {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * @return the block size in bytes
	 */
	@Override
	protected int engineGetBlockSize() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * @return IV in a new buffer
	 */
	@Override
	protected byte[] engineGetIV() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @result length of buffer necessary to hold output of next update or final operation,
	 * given this input length
	 */
	@Override
	protected int engineGetOutputSize(int inputLen) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	protected AlgorithmParameters engineGetParameters() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected void engineInit(int opmode, Key key, SecureRandom random)
			throws InvalidKeyException {
		// can't do without a tweak... make a random one, and return it in iv
		
	}

	@Override
	protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params,
			SecureRandom random) throws InvalidKeyException,
			InvalidAlgorithmParameterException {
		// TODO Auto-generated method stub

	}

	@Override
	protected void engineInit(int opmode, Key key, AlgorithmParameters params,
			SecureRandom random) throws InvalidKeyException,
			InvalidAlgorithmParameterException {
		// TODO Auto-generated method stub

	}
	
	protected void engineInit(int opmode, Key key, long tweak, SecureRandom random) throws
		InvalidKeyException, InvalidAlgorithmParameterException {
		// Divide key into two
		if (!(key instanceof XTSKey))
			throw new InvalidKeyException("Must use an XTS key with AES/XTS.");
		
		XTSKey xtsKey = (XTSKey) key;
		intializeTweak(key.k2(), tweak);
	}

	@Override
	protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
		// TODO Auto-generated method stub

	}

	@Override
	protected void engineSetPadding(String padding) throws NoSuchPaddingException {
		// TODO Auto-generated method stub

	}

	@Override
	protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output,
			int outputOffset) throws ShortBufferException {
		// TODO Auto-generated method stub
		return 0;
	}

    /**
     * Tweak is E_k2(tweakVal). tweakVal is a little-endian representation
     * of a 64-bit value, for example the block number or an IV, followed by
     * 64 bits of 0.
     * Reusing K, tweakVal pairs is insecure.
     **/
    protected void initializeTweak(Key k2, Long tweakVal) {
        // convert BE tweakVal into LE byte array
        byte [] tweakBytes = new byte[TWEAK_LEN * 2];
        // last 64 bits of tweakBytes are always 0

        // TODO
        aesEncrypt(k2, tweakVal, _tweak);
    }

    protected void xtsCryptBlock(byte [] inputBlock, byte [] outputBlock) {

        // Limit protection on this operation, only internal. Otherwise
        // check input values.

        byte [] block = new byte[XTS_BLOCKSIZE];
        int carry_in, carry_out;
        int i;

        for (i=0; i < XTS_BLOCKSIZE; ++i) {
            block[i] = (byte)(inputBlock[i] ^ _tweak[i]);
        }

        if ((_opmode == Cipher.ENCRYPT_MODE) || (_opmode == Cipher.WRAP_MODE)) {
            aesEncrypt(_k1, block, outputBlock);
        } else {
            aesDecrypt(_k1, block, outputBlock);
        }

        for (i=0; i < XTS_BLOCKSIZE; ++i) {
            outputBlock[i] ^= _tweak[i];
        }

        /* Update tweak */
        carry_in = 0;
        for (i = 0; i < XTS_BLOCKSIZE; ++i) {
            carry_out = _tweak[i] & 0x80;
            _tweak[i] = (byte)((_tweak[i] << 1) | (byte)((0 != carry_in) ? 1 : 0));
            carry_in = carry_out;
        }

        if (0 != carry_in) {
            _tweak[0] ^= XTS_ALPHA; 
        }
         
        for (i=0; i < XTS_BLOCKSIZE; ++i) {
            block[i] = 0;
        }
    }
    
    protected Cipher 
}
