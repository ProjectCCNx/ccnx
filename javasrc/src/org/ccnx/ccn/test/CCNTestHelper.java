package org.ccnx.ccn.test;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * Utility class to provide facilities to be used by all of
 * the CCN tests, most importantly a standardized namespace for
 * them to write their data into.
 * 
 * Given a unit test class named UnitTestClass, we name all the data generated
 * by that test class as 
 *  /ccnx.org/test/UnitTestClass-TIMESTAMP
 *  
 * for a unit test UnitTest in that class, ideally the data for that specific unit test
 * will be stored under 
 *  /ccnx.org/test/UnitTestClass-TIMESTAMP/UnitTest
 */
public class CCNTestHelper {

	protected static final String TEST_PREFIX_STRING = "/ccnx.org/test";
	protected static ContentName TEST_PREFIX;
	
	ContentName _testNamespace;
	String _testName;
	CCNTime _testTime;
	
	static {
		try {
			TEST_PREFIX = ContentName.fromNative(TEST_PREFIX_STRING);
		} catch (MalformedContentNameStringException e) {
			Log.warning("Cannot parse default test namespace name {1}!", TEST_PREFIX_STRING);
			throw new RuntimeException("Cannot parse default test namespace name: " + TEST_PREFIX_STRING +".");
		}
	}
	
	public CCNTestHelper(String testClassName) {
		_testName = testClassName;
		if (_testName.contains(".")) {
			_testName = testClassName.substring(testClassName.lastIndexOf(".") + 1);
		}
		_testTime = new CCNTime();
		_testNamespace = ContentName.fromNative(TEST_PREFIX, _testName + "-" + _testTime.toString());
		Log.info("Initializing test {0}, data can be found under {1}.", testClassName, _testNamespace);
	}
	
	public CCNTestHelper(Class<?> unitTestClass) {
		this(unitTestClass.getName().toString());
	}
	
	public ContentName getClassNamespace() { return _testNamespace; }
	
	public ContentName getTestNamespace(String testName) { return ContentName.fromNative(_testNamespace, testName); }
	
	public CCNTime getTestTime() { return _testTime; }
}
