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

import java.util.Map;
import java.util.Map.Entry;

import org.ccnx.ccn.config.PlatformConfiguration;
import org.junit.Test;

/**
 * Test the automatic platform detection in PlatformConfiguration
 */
public class PlatformTest {

	@Test
	public void testNeedSignatureLock() throws Exception {
		System.out.println("need signatures: " + PlatformConfiguration.needSignatureLock());
	}
	
//	@Test
//	public void testEnvironment() throws Exception {
//		Map<String, String> env = System.getenv();
//		
//		for( Entry<String, String> entry : env.entrySet() )
//			System.out.println(String.format("%s = %s", entry.getKey(), entry.getValue()));
//
//	}
}
