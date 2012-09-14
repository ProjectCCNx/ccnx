/*
 * CCNx Android Lib
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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
package org.ccnx.android.ccnlib;

public class CCNxLibraryCheck {
	public static void checkBCP() {
		// We'll need a way to cleanly check if BCP is present on the Android Device as long as there are some devices that 
		// have it and some that don't have a complete implementation
		try {
			Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider", true, Thread.currentThread().getContextClassLoader());
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
 
}