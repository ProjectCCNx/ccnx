package com.parc.ccn.security.crypto.jce;

import org.bouncycastle.jce.provider.WrapCipherSpi;


public class AESWrapWithPad extends WrapCipherSpi {
	
	public AESWrapWithPad() {
		super(new AESWrapWithPadEngine());
	}

}
