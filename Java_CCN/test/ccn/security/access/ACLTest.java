package test.ccn.security.access;


import java.util.ArrayList;
import java.util.LinkedList;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.security.access.ACL;

/**
 * Tests functionality of ACL class.
 * 
 * @author pgolle
 *
 */


public class ACLTest {
	
	static LinkReference lr1 = null;
	static LinkReference lr2 = null;
	static LinkReference lr3 = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		lr1 = new LinkReference(ContentName.fromNative("/parc/sds/pgolle"));
		lr2 = new LinkReference(ContentName.fromNative("/parc/sds/eshi"));
		lr3 = new LinkReference(ContentName.fromNative("/parc/sds/smetters"));		
	}
	
	@Test
	public void testACLCreation() throws Exception {
		ACL testACL = new ACL();
		
		testACL.addReader(lr1);
		testACL.addReader(lr1);
		testACL.addWriter(lr1);
		testACL.addManager(lr1);
		testACL.addWriter(lr2);
		testACL.addManager(lr3);
		
		Assert.assertTrue(testACL.validate());
	}

	@Test
	public void testACLCreationFromArrayList() throws Exception {
		ArrayList<LinkReference> alr = new ArrayList<LinkReference>();
		alr.add(new LinkReference(ContentName.fromNative("/parc/sds/pgolle"), "r", null));
		alr.add(new LinkReference(ContentName.fromNative("/parc/sds/eshi"), "w", null));
		ACL testACL = new ACL(alr);
		Assert.assertTrue(testACL.validate());
	}
	
	@Test
	public void testUpdate() throws Exception {
		ACL testACL = new ACL();
		ArrayList<LinkReference> userList = new ArrayList<LinkReference>();
		ArrayList<LinkReference> emptyList = new ArrayList<LinkReference>();
		
		// add lr1 and lr2 as readers (2 new readers)
		userList.add(lr1);
		userList.add(lr2);
		LinkedList<LinkReference> result = 
			testACL.update(userList, emptyList, emptyList, emptyList, emptyList, emptyList);
		Assert.assertEquals(2, result.size());

		// add the same 2 readers again (0 new reader)
		result = testACL.update(userList, emptyList, emptyList, emptyList, emptyList, emptyList);
		Assert.assertEquals(0, result.size());
		
		// delete reader lr1 and add reader lr3
		// (null result indicates some read privileges lost)
		userList.remove(0);
		ArrayList<LinkReference> otherUserList = new ArrayList<LinkReference>();
		otherUserList.add(lr3);		
		result = testACL.update(otherUserList, userList, emptyList, emptyList, emptyList, emptyList);
		Assert.assertEquals(null, result);
		
		// add readers lr1 and lr2 (only lr1 is new)
		userList.add(lr1);		
		result = testACL.update(userList, emptyList, emptyList, emptyList, emptyList, emptyList);
		Assert.assertEquals(1, result.size());
		
	}
}