/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.security.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.security.crypto.ContentKeys.ContentInfo;
import org.ccnx.ccn.impl.security.crypto.ContentKeys.KeyAndIV;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;


/**
 * This class takes a master symmetric key, and derives from it a key
 * and initialization vector to be used to encrypt a specific content object.
 * 
 * This simplifies key management by allowing the same master key to be
 * used for families of related content (e.g. all the versions of a file,
 * all of the intrinsic metadata associated with that file, etc.), without
 * having to manage an additional separately wrapped master key. It allows
 * hierarchically delegated access control (i.e. when the children of a node
 * inherit the permissions associated with that node) to proceed without 
 * requiring additional keys. It also acts to prevent errors, by limiting
 * the risk of IV/counter reuse, both by distributing encryption across
 * many derived keys, and deriving the IV/counter seeds automatically for
 * programmers.
 * 
 * NOTE: This is a low-level cryptographic API.
 * This class is used internally by CCN's built in access control prototype,
 * and may be used for key derivation in general, for people building other
 * encryption models for CCN. However, if you don't
 * already know what "key derivation" means, you should NOT be using it --
 * there is probably a different API that more closely fits your needs.
 * 
 * For each block, we compute the PRF using key K over
 * 		(counter || label || 0x00 || context || L)
 * where L is the desired output length in bits. 
 * 
 * We encode the counter and L both in 32 bit fields. The counter is initialized
 * to 1.
 * 
 * The KDF implemented herein is from NIST special publication 108-008,
 * and is described in more detail in the CCN documentation.
 */
public class KeyDerivationFunction {

	/**
	 * MAC algorithm used as a PRF.
	 * We use HMAC-SHA256 as our primary PRF, because it is the most commonly
	 * implemented acceptable option. CMAC would be preferable,
	 * but is not universally available yet.
	 */
	protected static final String MAC_ALGORITHM = "HmacSHA256";
	
	/**
	 * Default parameterization of the KDF for standard algorithm type. This is the
	 * routine that will be typically used by code that does not want to override
	 * default algorithms.
	 * @param masterKeyBytes the source key from which to derive a subkey
	 * @param label a text label for additional parameterization, if desired
	 * @param contentName name of the specific object to derive a key for, including the version (but not including
	 *     segment information).
	 * @param publisher for this particular set of objects
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 */
	public static final KeyAndIV DeriveKeysForObject(
			String keyAlgorithm,
			byte [] masterKeyBytes, 
			ContentInfo contentInfo) throws InvalidKeyException, ContentEncodingException {
		KeyAndIV keyAndIV = DeriveKeysForObject(keyAlgorithm, masterKeyBytes, 
											  ContentKeys.DEFAULT_KEY_LENGTH*DataUtils.BITS_PER_BYTE, 
											  StaticContentKeys.IV_MASTER_LENGTH*DataUtils.BITS_PER_BYTE,
											  contentInfo);
		return keyAndIV;
	}
	
	/**
	 * Derive a key and IV for a particular object. Requested bit lengths must
	 * be divisible by BITS_PER_BYTE.
	 * @param masterKeyBytes master key to derive a new key from
	 * @param keyBitLength bit length of key to derive
	 * @param ivBitLength bit length of iv to derive
	 * @param label a text label to allow derivation of multiple key types from a single
	 * 	source key/path pair
	 * @param contentName name to derive a key for
	 * @param publisher publisher whose version of contentName we want to derive for
	 * @return returns an {key, iv/counter seed} pair suitable for use in segment encryption
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 */
	public static final KeyAndIV DeriveKeysForObject(
			String keyAlgorithm,
			byte [] masterKeyBytes, 
			int keyBitLength, int ivBitLength,
			ContentInfo contentInfo) throws InvalidKeyException, ContentEncodingException {
		byte [] key = new byte[keyBitLength/DataUtils.BITS_PER_BYTE];
		byte [] iv = new byte[ivBitLength/DataUtils.BITS_PER_BYTE];

		byte [] keyandiv = DeriveKeyForObjectOrNode(masterKeyBytes, 
				keyBitLength + ivBitLength,
				contentInfo);

		System.arraycopy(keyandiv, 0, key, 0, keyBitLength/DataUtils.BITS_PER_BYTE);
		System.arraycopy(keyandiv, keyBitLength/DataUtils.BITS_PER_BYTE, iv, 0, ivBitLength/DataUtils.BITS_PER_BYTE);
		return new KeyAndIV(keyAlgorithm, key, iv);
	}

	/**
	 * Used to derive keys for nodes in a name hierarchy. The key must be independent of
	 * publisher, as it is used to derive keys for intermediate nodes. As this is used as
	 * input to another key derivation call, no IV is derived.
	 * @param parentNodeKeyBytes the initial source key to derive further keys from
	 * @param label a text label to allow derivation of multiple key types from a single
	 * 	source key/path pair
	 * @param nodeName the name of the node to derive a key for
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 */
	public static final byte [] DeriveKeyForNode(
			byte [] parentNodeKeyBytes,
			String label,
			ContentName nodeName) throws InvalidKeyException, ContentEncodingException {
		return DeriveKeyForNode(parentNodeKeyBytes, ContentKeys.DEFAULT_KEY_LENGTH*DataUtils.BITS_PER_BYTE, 
						label, nodeName);
	}
	
	/**
	 * Hierarchically derive keys for a child node, given an ancestor key. Why not do this in 
	 * one step, with no intervening keys? This way we can delegate/install backlinks to keys
	 * in the middle of the hierarchy and things continue to work.
	 * @param ancestorNodeName the node with whom ancestorNodeKey is associated.
	 * @param ancestorNodeKey the key associated with that ancestor node
	 * @param label a text label to allow derivation of multiple key types from a single
	 * 	source key/path pair
	 * @param nodeName the name of the node to derive a key for
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 */
	public static final byte [] DeriveKeyForNode(
			ContentName ancestorNodeName, 
			byte [] ancestorNodeKey,
			String label,
			ContentName nodeName) throws InvalidKeyException, ContentEncodingException {
		
		if ((null == ancestorNodeName) || (null == ancestorNodeKey) || (null == nodeName)) {
			throw new IllegalArgumentException("Names and keys cannot be null!");
		}
		if (!ancestorNodeName.isPrefixOf(nodeName)) {
			throw new IllegalArgumentException("Ancestor node name must be prefix of node name!");
		}
		if (ancestorNodeName.equals(nodeName)) {
			Log.info("We're at the correct node already, will return the original node key.");
		}
		
		ContentName descendantNodeName = ancestorNodeName;
		byte [] descendantNodeKey = ancestorNodeKey;
		while (!descendantNodeName.equals(nodeName)) {
			descendantNodeName = nodeName.cut(descendantNodeName.count() + 1);
			descendantNodeKey = DeriveKeyForNode(descendantNodeKey, label, descendantNodeName);
		}
		return descendantNodeKey;
	}
	
	public static final byte [] DeriveKeyForNode(byte [] parentNodeKeyBytes, int keyLengthInBits, 
						String label, ContentName nodeName) throws InvalidKeyException, ContentEncodingException {
		return DeriveKeyForObjectOrNode(parentNodeKeyBytes, ContentKeys.DEFAULT_KEY_LENGTH*DataUtils.BITS_PER_BYTE, 
				new ContentInfo(nodeName, null, label));
	}

	/**
	 * Derive a key for a particular object. Requested bit lengths must
	 * be divisible by BITS_PER_BYTE.
	 * @param masterKeyBytes master key to derive a new key from
	 * @param outputLengthInBits bit length of key to derive
	 * @param label a text label to allow derivation of multiple key types from a single
	 * 	source key/path pair
	 * @param contentName name to derive a key for
	 * @param publisher publisher whose version of contentName we want to derive for
	 * @return returns a key for this object
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 */
	public static final byte [] DeriveKeyForObjectOrNode(
			byte [] masterKeyBytes, int outputLengthInBits, 
			ContentInfo contentInfo) throws InvalidKeyException, ContentEncodingException {
		
		if (null == contentInfo.getContentName()) {
			throw new IllegalArgumentException("Content name cannot be null!");
		}
		return DeriveKey(masterKeyBytes, outputLengthInBits, contentInfo.getLabel(), 
				((null != contentInfo.getPublisher()) ? 
						new XMLEncodable[]{contentInfo.getContentName(), contentInfo.getPublisher()} : 
						new XMLEncodable[]{contentInfo.getContentName()}));
	}

	/**
	 * Core key derivation mechanism.
	 * @param masterKeyBytes master key to derive a new key from
	 * @param outputLengthInBits bit length of key to derive
	 * @param label a text label to allow derivation of multiple key types from a single
	 * 	source key/path pair
	 * @param contextObjects objects to add into the KDF as context. Usually at least the name
	 * 	of the node, also possibly the publisher.
	 * @return the derived key
	 * @throws InvalidKeyException
	 * @throws ContentEncodingException
	 */
	public static final byte [] DeriveKey(byte [] masterKeyBytes, int outputLengthInBits, 
			String label, XMLEncodable [] contextObjects) throws InvalidKeyException, ContentEncodingException {

		if ((null == masterKeyBytes) || (masterKeyBytes.length == 0)) {
			throw new IllegalArgumentException("Master key bytes cannot be null or empty!");
		}
		// DKS deep debugging
		boolean allzeros = true;
		for (byte b : masterKeyBytes) {
			if (b != 0) {
				allzeros = false;
				break;
			}
		}
		if (allzeros) {
			Log.warning("Warning: DeriveKey called with all 0's key of length " + masterKeyBytes.length);
		}
		Mac hmac;
		try {
			hmac = Mac.getInstance("HmacSHA256");
		} catch (NoSuchAlgorithmException e1) {
			Log.severe("No HMAC-SHA256 available! Serious configuration issue!");
			throw new RuntimeException("No HMAC-SHA256 available! Serious configuration issue!");
		}
		hmac.init(new SecretKeySpec(masterKeyBytes, hmac.getAlgorithm()));
		
		// Precompute data used from block to block.
		byte [] Lbytes = new byte[] { (byte)(outputLengthInBits>>24), (byte)(outputLengthInBits>>16), 
				(byte)(outputLengthInBits>>DataUtils.BITS_PER_BYTE), (byte)outputLengthInBits };
		byte [][] contextBytes = new byte[((null == contextObjects) ? 0 : contextObjects.length)][];
		for (int j = 0; j < contextBytes.length; ++j) {
			contextBytes[j] = contextObjects[j].encode();
		}
		int outputLengthInBytes = (outputLengthInBits + DataUtils.BITS_PER_BYTE - 1) / DataUtils.BITS_PER_BYTE;
		byte [] outputBytes = new byte[outputLengthInBytes];

		// Number of rounds
		int macLength = hmac.getMacLength();
		int n = (outputLengthInBytes + macLength - 1) / macLength;
		if (n < 1) {
			Log.warning("Unexpected: 0 block key derivation: want " + outputLengthInBits + 
					" bits (" + outputLengthInBytes + " bytes).");
		}

		// Run the HMAC for enough blocks to get the keying material we need.
		for (int i=1; i <= n; ++i) {
			try {
				// 32-bit representation of i
				hmac.update(new byte[] { (byte)(i>>24), (byte)(i>>16), (byte)(i>>8), (byte)i});
				// UTF-8 representation of label, including null terminator, or "" if empty
				if (null == label) {
					label = DataUtils.EMPTY;
				}

				hmac.update(DataUtils.getBytesFromUTF8String(label));

				// a 0 byte
				hmac.update((byte)0x00);
				// encoded context objects
				for (int k=0; k < contextBytes.length; ++k) {
					hmac.update(contextBytes[k]);
				}
				// 32-bit representation of L
				hmac.update(Lbytes);

				if (i < n) {
					hmac.doFinal(outputBytes, (i-1)*macLength);
				} else {
					byte [] finalBlock = hmac.doFinal();
					System.arraycopy(finalBlock, 0, outputBytes, (i-1)*macLength, outputBytes.length - (i-1)*macLength);
				}
			} catch (IllegalStateException ex) {
				Log.severe("Unexpected IllegalStateException in DeriveKey: hmac should have been initialized!");
				Log.warningStackTrace(ex);
				throw new RuntimeException("Unexpected IllegalStateException in DeriveKey: hmac should have been initialized!");
			} catch (ShortBufferException sx) {
				Log.severe("Unexpected ShortBufferException in DeriveKey: buffer should be sufficient!");
				Log.warningStackTrace(sx);
				throw new RuntimeException("Unexpected ShortBufferException in DeriveKey: buffer should be sufficient!");
			}
		}
		return outputBytes;
	}
}
