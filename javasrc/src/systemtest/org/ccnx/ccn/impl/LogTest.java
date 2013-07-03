/*
 * A CCNx library test.
 *
 * Copyright (C) 2010-2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl;

import java.io.ByteArrayOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class LogTest {
	protected static ByteArrayOutputStream _baos = new ByteArrayOutputStream();
	protected static StreamHandler _sh = new StreamHandler(_baos, new SimpleFormatter());
	protected static Level[] prevLevels;
	protected int _lastStart;
	protected int _lastFinish;
	protected static Object _testLock = new Object();

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		prevLevels = Log.getLevels();
		String app = Log.getApplicationClass();
		Logger logger = Logger.getLogger(app);
		logger.addHandler(_sh);
	}

	@AfterClass
	public static void tearDownAfterClass() {
		Log.setLevels(prevLevels);
	}

	/**
	 * Write to the log and return number of bytes that show up
	 * in the log
	 */
	protected int writeLog(int facility, Level level, String msg) {
		_sh.flush();
		_lastStart = _baos.toByteArray().length;
		Log.log(facility, level, msg);
		_sh.flush();
		_lastFinish = _baos.toByteArray().length;
		Assert.assertTrue("Finished before start!", _lastFinish >= _lastStart);
		return _lastFinish - _lastStart;
	}

	protected void doTest(int facility, Level level) {
		synchronized (_testLock) {
			// set level off and make sure no logging
			Log.setLevel(facility, Level.OFF);
			int result = writeLog(facility, level, "test me off");
			if (result > 0) {
				byte[] contents = _baos.toByteArray();
				byte[] b;
				if (!(contents.length < _lastFinish)) {
					b = new byte[_lastFinish - _lastStart];
					int j = 0;
					for (int i = _lastStart; i < _lastFinish; i++) {
						b[j] = contents[i];
						j++;
					}
				} else if (contents.length > _lastStart) {
					b = new byte[contents.length - _lastStart];
					int j = 0;
					for (int i = _lastStart; i < contents.length; i++) {
						b[j] = contents[i];
						j++;
					}
				} else {
					Log.setLevel(Level.ALL);
					Log.severe("Logging problem: we saw - too few bytes: {0}, {1}", _lastStart, contents.length);
					throw new AssertionError("Too few bytes");
				}
				System.out.println("Logging problem - we saw: \"" + DataUtils.printBytes(b) + "\" when there should have been no log");
				Log.setLevel(Level.ALL);
				Log.severe("Logging problem: we saw: \"{0}\" when there should have been no log", DataUtils.printBytes(b));
			}
			Assert.assertEquals(0, result);
	
			// set to ALL and make sure it does log
			Log.setLevel(facility, Level.ALL);
			result = writeLog(facility, level, "test me all");
			Assert.assertTrue(result > 0);
	
			// set to level and make sure it does log
			Log.setLevel(facility, level);
			result = writeLog(facility, level, "test me level");
			Assert.assertTrue(result > 0);
		}
	}

	@Test
	public void testInfoStringObjectArray() {
		doTest(Log.FAC_DEFAULT, Level.INFO);
	}

	@Test
	public void testInfoIntStringObjectArray() {
		doTest(Log.FAC_DEFAULT, Level.INFO);
		doTest(Log.FAC_NETMANAGER, Level.INFO);
	}

	@Test
	public void testWarningIntStringObjectArray() {
		doTest(Log.FAC_DEFAULT, Level.WARNING);
		doTest(Log.FAC_NETMANAGER, Level.WARNING);
	}

	@Test
	public void testSevereIntStringObjectArray() {
		doTest(Log.FAC_DEFAULT, Level.SEVERE);
		doTest(Log.FAC_NETMANAGER, Level.SEVERE);
	}

	@Test
	public void testGetLevelInt() {
		synchronized (_testLock) {
			Log.setLevel(Level.OFF);
			Assert.assertEquals(Level.OFF.intValue(), Log.getLevel().intValue());
			Assert.assertEquals(Level.OFF.intValue(), Log.getLevel(Log.FAC_DEFAULT).intValue());
	
			Log.setLevel(Log.FAC_NETMANAGER, Level.SEVERE);
			Assert.assertEquals(Level.SEVERE.intValue(), Log.getLevel(Log.FAC_NETMANAGER).intValue());
		}
	}

	@Test
	public void testGetSetLevels() {
		synchronized (_testLock) {
			Log.setLevel(Log.FAC_ALL, Level.WARNING);
			Log.setLevel(Log.FAC_NETMANAGER, Level.INFO);
			Log.setLevel(Log.FAC_IO, Level.FINE);
	
			Level [] save = Log.getLevels();
			Log.setLevel(Log.FAC_ALL, Level.FINER);
			Assert.assertEquals(Level.FINER, Log.getLevel(Log.FAC_PIPELINE));
			Assert.assertEquals(Level.FINER, Log.getLevel(Log.FAC_NETMANAGER));
			Assert.assertEquals(Level.FINER, Log.getLevel(Log.FAC_IO));
	
			Log.setLevels(save);
			Assert.assertEquals(Level.WARNING, Log.getLevel(Log.FAC_PIPELINE));
			Assert.assertEquals(Level.INFO, Log.getLevel(Log.FAC_NETMANAGER));
			Assert.assertEquals(Level.FINE, Log.getLevel(Log.FAC_IO) );
		}
	}

}
