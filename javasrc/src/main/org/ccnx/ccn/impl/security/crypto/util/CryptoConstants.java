/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010, 2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.security.crypto.util;

public class CryptoConstants {

	/*
	 * The core encryption algorithms supported. Any native encryption
	 * mode supported by Java *should* work, but these are compactly
	 * encodable.
	 */
	public static final String CBC_MODE = "CBC";
	public static final String CTR_MODE = "CTR";
	public static final String CTR_POSTFIX = "/CTR/NoPadding";
	public static final String CBC_POSTFIX = "/CBC/PKCS5Padding";
	public static final String AES_ALGORITHM = "AES";
	public static final String AES_CTR_MODE = AES_ALGORITHM + CTR_POSTFIX;
	public static final String AES_CBC_MODE = AES_ALGORITHM + CBC_POSTFIX;
	public static final String HMAC = "HMAC";
}
