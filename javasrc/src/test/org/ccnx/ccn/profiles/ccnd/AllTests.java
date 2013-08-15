package org.ccnx.ccn.profiles.ccnd;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({ FaceManagerUnitTest.class,
		PrefixRegistrationManagerUnitTest.class })
public class AllTests {

}
