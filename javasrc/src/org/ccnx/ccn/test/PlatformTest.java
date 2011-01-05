/*
 * A CCNx library test.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.config.PlatformConfiguration;
import org.junit.Test;

/**
 * Test the automatic platform detection in PlatformConfiguration.
 * 
 * This is really a non-test.  Just an easy place to run the test for visual inspection.
 */
public class PlatformTest {

	@Test
	public void testNeedSignatureLock() throws Exception {
		System.out.println("need signatures: " + PlatformConfiguration.needSignatureLock());
	}
	

}
