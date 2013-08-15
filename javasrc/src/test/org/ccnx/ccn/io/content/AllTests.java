package org.ccnx.ccn.io.content;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({ CollectionTest.class, EncodableObjectTest.class,
		HeaderTest.class, KeyValueSetTest.class, LinkTest.class,
		SerializableObjectTest.class, WrappedKeyUnitTest.class })
public class AllTests {

}
