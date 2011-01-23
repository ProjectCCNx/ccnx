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

import org.ccnx.ccn.impl.CCNStats;
import org.ccnx.ccn.impl.CCNStats.ExampleClassWithStatistics;
import org.junit.Assert;
import org.junit.Test;

public class CCNStatTest {

	@Test
	public void testExample() throws Exception {
		ExampleClassWithStatistics ecws = new ExampleClassWithStatistics();

		int sends = 20;
		int recvs = 15;

		for(int i = 0; i < sends; i++ )
			ecws.send(null);

		for(int i = 0; i < recvs; i++ )
			ecws.recv(null);

		System.out.println(ecws.getStats().toString());

		CCNStats stats = ecws.getStats();

		long test_sends = stats.getCounter("SendRequests");
		long test_recvs = stats.getCounter(stats.getCounterNames()[1]);

		Assert.assertEquals(sends, test_sends);
		Assert.assertEquals(recvs, test_recvs);
	}

	@Test
	public void testPerformance() throws Exception {
		System.out.println("===========================================");
		System.out.println("Testing performance of counters");
		System.out.println();
		
		ExampleClassWithStatistics ecws = new ExampleClassWithStatistics();

		int sends = 10000000;
		int repeats = 10;

		long sum_nanos = 0;
		long sum2_nanos = 0;

		// throw out the first run
		for(int i = 0; i < repeats + 1; i++) {
			long t0_nanos = System.nanoTime();

			for(int j = 0; j < sends; j++ )
				ecws.send(null);

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

	}


}
