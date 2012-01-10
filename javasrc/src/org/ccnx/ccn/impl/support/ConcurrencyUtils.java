/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.impl.support;

import org.ccnx.ccn.config.SystemConfiguration;

public class ConcurrencyUtils {
	
	/**
	 * A generic waiter implemented as an abstract class
	 * 
	 * The generic waiter handles timeouts and deciding whether to continue waiting
	 * on spurious wakeup based on a condition. You can hand it an Object to synchronize on
	 * (syncValue) and an Object to check. Both are handed back to the abstract check method as
	 * the parameters "syncValue" and "checkValue".  The wait ends when either check(syncValue, checkValue)
	 * returns true or the timeout expires.
	 * 
	 * A convenient way to use the waiter is to instantiate the abstract method in an anonymous class.
	 * So for example, if you wanted to wait 500 ms for a static Boolean foo to become true and 
	 * synchronize on an Object named lock you could do it like this, instantiating "check" in the 
	 * anonymous class to check the value of foo:
	 * 
	 * \verbatim
	 * Object lock = new Object();
	 * new Waiter(500) {
	 * 		@Override
	 *		protected boolean check(Object syncValue, Object checkValue) throws Exception {
	 *			return (Boolean)checkValue;
	 *		}
	 *	}.wait(lock, foo);
	 * \endverbatim
	 */
	public static abstract class Waiter {
		protected long timeout;
		protected Waiter(long timeout) {
			this.timeout = timeout;
		}
		
		/**
		 * Wait until "check" returns true, or timeout is elapsed. Handles spurious wakeups
		 * by calling check in a loop.
		 * 
		 * @param syncValue - wait under this lock, also passed to check routine
		 * @param checkValue - value to pass to check to allow subclass to test condition
		 * @throws Exception
		 */
		public void wait(Object syncValue, Object checkValue) throws Exception {
			synchronized (syncValue) {
				long origTimeout = timeout;		
				long startTime = System.currentTimeMillis();
				while (!check(syncValue, checkValue) && (timeout > 0 || origTimeout == SystemConfiguration.NO_TIMEOUT)) {
					if (origTimeout == SystemConfiguration.NO_TIMEOUT)
						syncValue.wait();
					else
						syncValue.wait(timeout);
					timeout -= (System.currentTimeMillis() - startTime);
				}
			}
		}
		
		/**
		 * Check to see if condition is met
		 * 
		 * @param syncValue the lock object which could be part of the condition check
		 * @param checkValue value to check for end condition
		 * @return true if condition is met (and we should stop waiting)
		 * @throws Exception
		 */
		protected abstract boolean check(Object syncValue, Object checkValue) throws Exception;
	}
}
