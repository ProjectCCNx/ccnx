package org.ccnx.ccn.security.crypto;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({ CCNMerkleTreeTest.class, KeyDerivationFunctionTest.class,
		MerkleTreeTest.class })
public class AllTests {

}
