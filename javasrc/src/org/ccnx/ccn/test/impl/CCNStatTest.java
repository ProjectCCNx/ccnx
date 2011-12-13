/*
 * A CCNx library test.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.impl;

import java.util.Random;

import org.ccnx.ccn.impl.CCNStats;
import org.ccnx.ccn.impl.CCNStats.ExampleClassWithStatistics;
import org.ccnx.ccn.impl.support.Log;
import org.junit.Assert;
import org.junit.Test;

public class CCNStatTest {
	final String obj = "Hello, world!";
	final Random rnd = new Random();
	
	@Test
	public void testExample() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testExample");

		ExampleClassWithStatistics ecws = new ExampleClassWithStatistics();
		
		int sends = 20;
		int recvs = 15;

		for(int i = 0; i < sends; i++ ) {
			ecws.send(obj, 10);
		
			// add some arbitrary delay to simulate doing something
			try {
				Thread.sleep(rnd.nextInt(200) + 50);
			} catch (InterruptedException e) {
			}
		}

		for(int i = 0; i < recvs; i++ )
			ecws.recv(obj);

		System.out.println(ecws.getStats().toString());

		CCNStats stats = ecws.getStats();

		long test_sends = stats.getCounter("SendRequests");
		long test_recvs = stats.getCounter(stats.getCounterNames()[1]);

		Assert.assertEquals(sends, test_sends);
		Assert.assertEquals(recvs, test_recvs);
		
		Log.info(Log.FAC_TEST, "Completed testExample");
	}

	@Test
	public void testPerformance() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testPerformance");
		
		ExampleClassWithStatistics ecws = new ExampleClassWithStatistics();

		int sends = 10000000;
		int repeats = 10;

		long sum_nanos = 0;
		long sum2_nanos = 0;

		// throw out the first run
		for(int i = 0; i < repeats + 1; i++) {
			long t0_nanos = System.nanoTime();

			for(int j = 0; j < sends; j++ )
				ecws.send(obj, 10);

			long t1_nanos = System.nanoTime();

			if( i > 0 ) {
				System.out.println(
						String.format("Wrote %d counters in %d nanos = %f nanos/increment",
								sends, 
								t1_nanos - t0_nanos,
								(double) (t1_nanos - t0_nanos) / (double) sends));

				long delta =  t1_nanos - t0_nanos;
				sum_nanos += delta;
				sum2_nanos += delta * delta;
			}
		}

		double avg_delta = sum_nanos / repeats;
		double std_delta = 1.0 / repeats * Math.sqrt( repeats * sum2_nanos - sum_nanos * sum_nanos );

		System.out.println(
				String.format("average %f std %f nanos/increment",
						avg_delta / sends, std_delta / sends));
		Log.info(Log.FAC_TEST, "Completed testPerformance");

	}	
}
