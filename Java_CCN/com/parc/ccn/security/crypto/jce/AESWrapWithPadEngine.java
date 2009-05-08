package com.parc.ccn.security.crypto.jce;

import org.bouncycastle.crypto.engines.AESEngine;


public class AESWrapWithPadEngine extends RFC3394WrapWithPadEngine {

	public AESWrapWithPadEngine() {
		super(new AESEngine());
	}
}
