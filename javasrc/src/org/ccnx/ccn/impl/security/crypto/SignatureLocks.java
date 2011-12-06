/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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

import java.util.concurrent.locks.ReentrantLock;

import org.ccnx.ccn.config.PlatformConfiguration;

/**
 * Some platforms (i.e. Android) do not handle concurrent signature operations.
 * This class wraps all the locking.
 * 
 * It is possible that we need the same lock between signing and verifying.
 * That is how the original code locked, with a note that it could be more
 * granular (like is done here).  The unwrap lock has always been separate
 * from the signing lock.
 */
public class SignatureLocks {
	// ==============================================
	// Public interface
	
	public static void signingLock() {
		if( PlatformConfiguration.needSignatureLock() )
			_signLock.lock();
	}
	
	public static void signingUnock() throws IllegalMonitorStateException {
		if( PlatformConfiguration.needSignatureLock() )
			_signLock.unlock();
	}
	
	public static void unwrapLock() {
		if( PlatformConfiguration.needSignatureLock() )
			_unwrapLock.lock();
	}
	
	public static void unwrapUnock() throws IllegalMonitorStateException {
		if( PlatformConfiguration.needSignatureLock() )
			_unwrapLock.unlock();
	}
	
	// ====================================================
	
	// Signing and verifying use the same lock
	private final static ReentrantLock _signLock = new ReentrantLock();
	
	// Unwrapping uses separate lock
	private final static ReentrantLock _unwrapLock = new ReentrantLock();
	
	
}
