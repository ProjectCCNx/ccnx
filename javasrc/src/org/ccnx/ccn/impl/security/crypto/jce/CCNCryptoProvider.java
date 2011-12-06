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

import java.security.Provider;

import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;

/**
 * NOT CURRENTLY USED, may be used in future releases.
 * Create a Provider in order to host algorithms internal to us, or not yet supported
 * by either Sun Java or BouncyCastle. For the most part, we expect the
 * latter set to eventually be supported in a future BouncyCastle version. This allows
 * us to use such algorithms within the Java Cryptography Extension (JCE) infrastructure.
 * We have a Provider signing key, but have not had time to move over to either using our
 * own Provider or seeing if we can get our changes incorporated by BouncyCastle.
 */
public class CCNCryptoProvider extends Provider {

 	private static final long serialVersionUID = -5805180476448219009L;
 	
	private static String info = "PARC CCN internal cryptographic provider v0.01";
    public static String PROVIDER_NAME = "CCN";

    static final DERObjectIdentifier id_aes128_wrapWithPad = new DERObjectIdentifier(NISTObjectIdentifiers.aes + ".8");
    static final DERObjectIdentifier id_aes192_wrapWithPad = new DERObjectIdentifier(NISTObjectIdentifiers.aes + ".28");
    static final DERObjectIdentifier id_aes256_wrapWithPad = new DERObjectIdentifier(NISTObjectIdentifiers.aes + ".48");

    public CCNCryptoProvider() {
        super(PROVIDER_NAME, 0.01, info);
        addAlgorithms();
	}

    private void addAlgorithms() {
        put("Cipher.AESWRAPWITHPAD", "com.parc.ccn.security.crypto.jce.AESWrapWithPad");
         put("Alg.Alias.Cipher." + id_aes128_wrapWithPad, "AESWRAPWITHPAD");
        put("Alg.Alias.Cipher." + id_aes192_wrapWithPad, "AESWRAPWITHPAD");
        put("Alg.Alias.Cipher." + id_aes256_wrapWithPad, "AESWRAPWITHPAD");
    }
}
