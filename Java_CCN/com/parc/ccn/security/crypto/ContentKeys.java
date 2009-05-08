package com.parc.ccn.security.crypto;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.parc.ccn.Library;

/**
 * Specifies encryption algorithm and keys to use for encrypting content.
 */
public class ContentKeys {
	/**
	 * The core encryption algorithms supported. Any native encryption
	 * mode supported by Java *should* work, but these are compactly
	 * encodable.
	 */
	public static final String AES_CTR_MODE = "AES/CTR/NoPadding";
	public static final String AES_CBC_MODE = "AES/CBC/PKCS5Padding";
	public static final String DEFAULT_CIPHER_ALGORITHM = AES_CTR_MODE;
	public static final String DEFAULT_KEY_ALGORITHM = "AES";
	public static final int DEFAULT_KEY_LENGTH = 16;
	
	public static final int DEFAULT_AES_KEY_LENGTH = 16; // bytes, 128 bits
	public static final int IV_MASTER_LENGTH = 8; // bytes
	public static final int SEGMENT_NUMBER_LENGTH = 6; // bytes
	public static final int BLOCK_COUNTER_LENGTH = 2; // bytes
	private static final byte [] INITIAL_BLOCK_COUNTER_VALUE = new byte[]{0x00, 0x01};

	public String encryptionAlgorithm;
	public SecretKeySpec encryptionKey;
	public IvParameterSpec masterIV;

	public ContentKeys(String encryptionAlgorithm, SecretKeySpec encryptionKey,
			IvParameterSpec masterIV) throws NoSuchAlgorithmException, NoSuchPaddingException {
		// ensure NoSuchPaddingException cannot be thrown later when a Cipher is made
		Cipher.getInstance(encryptionAlgorithm);
		// TODO check secret key/iv not empty?
		this.encryptionAlgorithm = encryptionAlgorithm;
		this.encryptionKey = encryptionKey;
		this.masterIV = masterIV;
	}

	@SuppressWarnings("unused")
	private ContentKeys() {
	}
	
	/**
	 * A number of users of ContentKeys only support using the default algorithm.
	 * @throws UnsupportedOperationException if the algorithm is not the default.
	 */
	public void OnlySupportDefaultAlg() {
		// For now we only support the default algorithm.
		if (!encryptionAlgorithm.equals(ContentKeys.DEFAULT_CIPHER_ALGORITHM)) {
			String err = "Right now the only encryption algorithm we support is: " + 
			ContentKeys.DEFAULT_CIPHER_ALGORITHM + ", " + encryptionAlgorithm + 
			" will come later.";
			Library.logger().severe(err);
			throw new UnsupportedOperationException(err);
		}
	}
	
	public Cipher getCipher() {
		// We have tried a dummy call to Cipher.getInstance on construction of this ContentKeys - so
		// further "NoSuch" exceptions should not happen here.
		try {
			return Cipher.getInstance(encryptionAlgorithm);
		} catch (NoSuchAlgorithmException e) {
			String err = "Unexpected NoSuchAlgorithmException for an algorithm we have already used!";
			Library.logger().severe(err);
			throw new RuntimeException(err, e);
		} catch (NoSuchPaddingException e) {
			String err = "Unexpected NoSuchPaddingException for an algorithm we have already used!";
			Library.logger().severe(err);
			throw new RuntimeException(err, e);
		}
	}
	
	/**
	 * Create a set of encryption/decryption keys using the default algorithm.
	 */
	public static ContentKeys generateRandomKeys() {
		try {
			return new ContentKeys(
					DEFAULT_CIPHER_ALGORITHM,
					new SecretKeySpec(SecureRandom.getSeed(DEFAULT_KEY_LENGTH), DEFAULT_KEY_ALGORITHM),
					new IvParameterSpec(SecureRandom.getSeed(IV_MASTER_LENGTH)));
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		} catch (NoSuchPaddingException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Make an encrypting Cipher to be used in making a CipherOutputStream to
	 * wrap outgoing CCN data.
	 * 
	 * This will use the CCN defaults for IV handling, to ensure that segments
	 * of a given larger piece of content do not have overlapping key streams.
	 * Higher-level functionality embodied in the library (or application-specific
	 * code) should be used to make sure that the key, masterIV pair used for a 
	 * given multi-block piece of content is unique for that content.
	 * 
	 * CCN encryption algorithms assume deterministic IV generation (e.g. from 
	 * cryptographic MAC or ciphers themselves), and therefore do not transport
	 * the IV explicitly. Applications that wish to do so need to arrange
	 * IV transport.
	 * 
	 * We assume this stream starts on the first block of a multi-block segement,
	 * so for CTR mode, the initial block counter is 1 (block ==  encryption
	 * block). (Conventions for counter start them at 1, not 0.) The cipher
	 * will automatically increment the counter; if it overflows the two bytes
	 * we've given to it it will start to increment into the segment number.
	 * This runs the risk of potentially using up some of the IV space of
	 * other segments. 
	 * 
	 * CTR_init = IV_master || segment_number || block_counter
	 * CBC_iv = E_Ko(IV_master || segment_number || 0x0001)
	 * 		(just to make it easier, use the same feed value)
	 * 
	 * CTR value is 16 bytes.
	 * 		8 bytes are the IV.
	 * 		6 bytes are the segment number.
	 * 		last 2 bytes are the block number (for 16 byte blocks); if you 
	 * 	    have more space, use it for the block counter.
	 * IV value is the block width of the cipher.
	 * 
	 * 
	 * @throws InvalidAlgorithmParameterException 
	 * @throws InvalidKeyException 
	 */
	public Cipher getSegmentEncryptionCipher(
			Cipher optionalExistingCipherToInitialize,
			long segmentNumber)
	throws InvalidKeyException, InvalidAlgorithmParameterException {

		// Should we handle autogenerated IVs? (If you pass in null to the cipher, if it
		// needs an IV it will make one, which you can ask it for and transfer to the
		// other end somehow.
		// DKS -- TODO eventually.
		if (((null == encryptionAlgorithm) && (null == optionalExistingCipherToInitialize)) || (null == encryptionKey) || (null == masterIV)) {
			throw new InvalidAlgorithmParameterException("Algorithm (or cipher), key and IV cannot be null!");
		}

		Cipher cipher = null;
		if ((null != optionalExistingCipherToInitialize) &&
					(optionalExistingCipherToInitialize.getAlgorithm().equals(encryptionAlgorithm))) {
			cipher = optionalExistingCipherToInitialize;
		} else {
			cipher = getCipher();
		}

		// Construct the IV/initial counter.
		if (0 == cipher.getBlockSize()) {
			Library.logger().warning(encryptionAlgorithm + " is not a block cipher!");
			throw new InvalidAlgorithmParameterException(encryptionAlgorithm + " is not a block cipher!");
		}

		if (masterIV.getIV().length < IV_MASTER_LENGTH) {
			throw new InvalidAlgorithmParameterException("Master IV length must be at least " + IV_MASTER_LENGTH + " bytes, it is: " + masterIV.getIV().length);
		}

		IvParameterSpec iv_ctrSpec = buildIVCtr(masterIV, segmentNumber, cipher.getBlockSize());
		cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, iv_ctrSpec);

		return cipher;
	}

	public Cipher getSegmentDecryptionCipher(
			Cipher optionalExistingCipherToInitialize,
			long segmentNumber)
	throws InvalidKeyException, InvalidAlgorithmParameterException {

		// Should we handle autogenerated IVs? (If you pass in null to the cipher, if it
		// needs an IV it will make one, which you can ask it for and transfer to the
		// other end somehow.
		// DKS -- TODO eventually.
		if (((null == encryptionAlgorithm) &&
				(null == optionalExistingCipherToInitialize)) ||(null == encryptionKey) || (null == masterIV)) {
			throw new InvalidAlgorithmParameterException(
					"Algorithm (or cipher), key and IV cannot be null!");
		}

		Cipher cipher = null;
		if ((null != optionalExistingCipherToInitialize) &&
					(optionalExistingCipherToInitialize.getAlgorithm().equals(encryptionAlgorithm))) {
			cipher = optionalExistingCipherToInitialize;
		} else {
			cipher = getCipher();
		}

		// Construct the IV/initial counter.
		if (0 == cipher.getBlockSize()) {
			Library.logger().warning(encryptionAlgorithm + " is not a block cipher!");
			throw new InvalidAlgorithmParameterException(encryptionAlgorithm + " is not a block cipher!");
		}

		if (masterIV.getIV().length < IV_MASTER_LENGTH) {
			throw new InvalidAlgorithmParameterException("Master IV length must be at least " +
					IV_MASTER_LENGTH + " bytes, it is: " + masterIV.getIV().length);
		}

		IvParameterSpec iv_ctrSpec = buildIVCtr(masterIV, segmentNumber, cipher.getBlockSize());
		cipher.init(Cipher.DECRYPT_MODE, encryptionKey, iv_ctrSpec);

		return cipher;
	}

	public static IvParameterSpec buildIVCtr(IvParameterSpec masterIV, long segmentNumber, int ivLen) {
		
		byte [] iv_ctr = new byte[ivLen];
		
		System.arraycopy(masterIV.getIV(), 0, iv_ctr, 0, IV_MASTER_LENGTH);
		byte [] byteSegNum = segmentNumberToByteArray(segmentNumber);
		System.arraycopy(byteSegNum, 0, iv_ctr, IV_MASTER_LENGTH, byteSegNum.length);
		System.arraycopy(INITIAL_BLOCK_COUNTER_VALUE, 0, iv_ctr,
				iv_ctr.length - BLOCK_COUNTER_LENGTH, BLOCK_COUNTER_LENGTH);
		
		IvParameterSpec iv_ctrSpec = new IvParameterSpec(iv_ctr);
		return iv_ctrSpec;
	}
	
	public static byte [] segmentNumberToByteArray(long segmentNumber) {
		byte [] ba = new byte[SEGMENT_NUMBER_LENGTH];
		// Is this the fastest way to do this?
		byte [] bv = BigInteger.valueOf(SEGMENT_NUMBER_LENGTH).toByteArray();
		System.arraycopy(bv, 0, ba, SEGMENT_NUMBER_LENGTH-bv.length, bv.length);
		return ba;
	}
}