package com.parc.ccn.security.crypto.jce;

import java.security.Provider;

import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;

/**
 * Add additional algorithms either internal to us, or not yet supported
 * by either Sun Java or BouncyCastle. For the most part, we expect this
 * latter set to eventually be supported in a future BC version.
 * @author smetters
 *
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
