/*
 * A CCNx library test.
 *
 * Copyright (C) 2011, 2013 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test;

/**
 * Junit cannot catch exceptions from spawned threads.  This wrapper class
 * will catch assertion failures in a Runnable and report them to the caller.
 * 
 * Junit Assert.X method throw an AssertionError, which is a Throwable -> Error -> AssertionError.
 * So, we capture both an Error and an Exception.  Don't use Throwable, as those get
 * messy to handle in the caller.
 * 
 * How to use:
 * 1) Create a Runnable class (could even be anonymous inner class), and wrap it in a
 *    ThreadAssertionRunner:
 *    
 *    ThreadAssertionRunner tar = new ThreadAssertionRunner(new MyRunner(some_parameter));
 * 
 * 2) Start the Runner like a thread:
 * 
 *    tar.start();
 *    
 * 3) Do normal processing, then stop your runnable.  You must join the Runner to wait for exit.
 *    THIS STEP IS REQUIRED (it's the join that reports any exceptions or errors from the run()
 *    method of your runnable):
 * 
 *    tar.join();
 * 			
 */
public class ThreadAssertionRunner {
	public ThreadAssertionRunner(final Runnable runner) {
		// Create our own thread to run runner in, and just call its run() method
		_thd = new Thread(new Runnable() {
			public void run() {
				try {
					runner.run();
				} catch(Error error) {
					_error = error;
				} catch(Exception exception) {
					_exception = exception;
				}
			}
		});
	}
	
	public void start() {
		_thd.start();
	}
	
	public void join() throws InterruptedException, Error, Exception {
		_thd.join();
		if( _error != null )
			throw _error;
		if( _exception != null )
			throw _exception;
	}
	
	public void join(long millis) throws InterruptedException, Error, Exception {
		_thd.join(millis);

		if( _error != null )
			throw _error;
		if( _exception != null )
			throw _exception;
	}
	
	public boolean isAlive() {
		return _thd.isAlive();
	}
	
	// =============
	private Thread _thd = null;
	private volatile Error _error = null;
	private volatile Exception _exception = null;
	
}
