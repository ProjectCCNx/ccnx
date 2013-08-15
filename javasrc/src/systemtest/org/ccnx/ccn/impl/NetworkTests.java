package org.ccnx.ccn.impl;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({ CCNFlowControlTest.class,
		CCNFlowServerTest.class, CCNNetworkTestRepo.class, CCNStatTest.class,
		DeprecatedInterfaceTest.class, LogTest.class,
		NetworkTest.class})
public class NetworkTests {

}
