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

package org.ccnx.ccn.test.profiles.versioning;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.versioning.InterestData;
import org.ccnx.ccn.profiles.versioning.VersioningInterestManager;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.junit.Assert;

public class VersioningHelper {

	public static void compareReceived(CCNHandle handle, ArrayList<CCNStringObject> sent, MyListener listener) throws Exception {
		Assert.assertEquals(sent.size(), listener.received.size());

		// make a usable data structure from the ContentObjects
		ArrayList<String> recv = new ArrayList<String>();
		for( ReceivedData datum : listener.received) {
			CCNStringObject so = new CCNStringObject(datum.object, handle);
			recv.add(so.string());
		}

		for( int i = 0; i < sent.size(); i++ ) {
			// now make sure what we got is what we expected
			String string_sent = sent.get(i).string();

			// make sure it exists in received.  does not need
			// to be in the same order as sent
			Assert.assertTrue(recv.contains(string_sent));
		}
	}
	
	public static ArrayList<CCNStringObject> sendEventStream(CCNHandle handle, ContentName basename, int tosend) throws Exception {

		// Send a stream of string objects
		ArrayList<CCNStringObject> sent = new ArrayList<CCNStringObject>();

		for(int i = 0; i < tosend; i++) {
			// Save content
			try {
				CCNStringObject so = new CCNStringObject(basename,
						String.format("string object %d", i), 
						SaveType.LOCALREPOSITORY, handle);
				so.save();
				so.close();
				sent.add(so);
				
//				System.out.println("Sent version " + VersioningProfile.printAsVersionComponent(so.getVersion()));
				
				// Flow controller just cannot go this fast, so need to slow it down.
				Thread.sleep(10);
				
			} catch(Exception e) {
				e.printStackTrace();
				throw e;
			}
//			System.out.println(i);
		}
		
		return sent;
	}
	
	public static class ConditionLong {
		protected Lock lock = new ReentrantLock();
		protected Condition cond  = lock.newCondition();

		public long value = 0;

		public ConditionLong(long value) {
			this.value = value;
		}

		public long setValue(long value) throws InterruptedException {
			lock.lock();
			try {
				long oldValue = this.value;
				this.value = value;
				cond.signal();
				return oldValue;
			} finally {
				lock.unlock();
			}
		}

		public long getValue() {
			lock.lock();
			try {
				return value;
			} finally {
				lock.unlock();
			}
		}

		public long increment() {
			lock.lock();
			try {
				value++;
				cond.signal();
				return value;
			} finally {
				lock.unlock();
			}
		}

		/**
		 * @param value = to wait for
		 * @param timeout = in milliseconds
		 * @return true if value found, false if timeout
		 * @throws InterruptedException
		 */
		public boolean waitForValue(long value, long timeout) throws InterruptedException {
			boolean rtn = true;
			lock.lock();
			try {
				while ( rtn && this.value != value ) {
					rtn = cond.await(timeout, TimeUnit.MILLISECONDS);
				}

			} finally {
				lock.unlock();
			}
			return rtn;
		}
	}

	public static class ReceivedData {
		public final ContentObject object;
		public final Interest interest;
		public ReceivedData(ContentObject object, Interest interest) {
			this.object = object;
			this.interest = interest;
		}
	}

	public static class MyListener implements CCNInterestListener {
		public final ConditionLong cl = new ConditionLong(0);
		public final ArrayList<ReceivedData> received = new ArrayList<ReceivedData>();
		public InterestData id = null;
		public int runCount = 0;

		@Override
		public synchronized Interest handleContent(ContentObject data, Interest interest) {
			received.add(new ReceivedData(data, interest));
			cl.increment();

			if( null != id ) {
				try {
					CCNTime version = VersioningProfile.getLastVersionAsTimestamp(data.name());
					id.addExclude(version);
				} catch(Exception e) {
					e.printStackTrace();
				}

				if( cl.getValue() < runCount )
					return id.buildInterest();
			}
			return null;
		}

		public void setInterestData(InterestData id) {
			this.id = id;
		}

		/**
		 * Sends interests, blocks until done or timeout
		 * @param handle
		 * @param count
		 * @param timeout
		 * @return true if count received, false otherwise
		 * @throws IOException
		 * @throws InterruptedException
		 */
		public boolean run(CCNHandle handle, int count, long timeout) throws IOException, InterruptedException {
			runCount = count;
			if( count > 0 ) {
				Interest interest = id.buildInterest();
				handle.expressInterest(interest, this);
			}

			// wait for it to be done
			boolean rtn = cl.waitForValue(count, timeout);
			System.out.println(String.format("run received %d objects", cl.getValue()));
			return rtn;
		}
	}

	public static class TestVIM extends VersioningInterestManager {

		public TestVIM(CCNHandle handle, ContentName name, int retrySeconds,
				Set<CCNTime> exclusions, long startingVersion,
				CCNInterestListener listener) {
			super(handle, name, retrySeconds, exclusions, startingVersion, listener);
		}


	}
}
