package test.ccn.security.access;


import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.BeforeClass;
import org.junit.Test;


import test.ccn.data.util.Flosser;

public class AccessControlManagerTest {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@Test
	public void testPartialComponentMatch() {
		try {
		ContentName testPrefix = ContentName.fromNative("/parc/test/content/");
		Flosser flosser = new Flosser(testPrefix);
		
		ContentName versionPrefix = VersioningProfile.addVersion(testPrefix);
		ContentName aname = ContentName.fromNative(versionPrefix, "aaaaa");
		ContentName bname = ContentName.fromNative(versionPrefix, "bbbbb");
		ContentName abname = ContentName.fromNative(versionPrefix, "aaaaa:bbbbb");
		
		CCNWriter writer = new CCNWriter(versionPrefix, CCNHandle.open());
		writer.put(bname, "Some b's.".getBytes());
		writer.put(abname, "Some a's and b's.".getBytes());
		
		CCNHandle library = CCNHandle.open();
		ContentObject bobject = library.get(bname, 1000);
		if (bobject != null) {
			System.out.println("Queried for bname, got back: " + bobject.name());
		}
		ContentObject aobject = library.get(aname, 1000);
		if (aobject != null) {
			System.out.println("Queried for aname, got back: " + aobject.name());
		} else {
			System.out.println("Queried for aname, got back nothing.");
		}
		writer.put(aname, "Some a's.".getBytes());
		ContentObject aobject2 = library.get(versionPrefix, 1000);
		if (aobject2 != null) {
			System.out.println("Queried for aname, again got back: " + aobject2.name());
		}
		flosser.stop();
		} catch (Exception e) {
			System.out.println("Exception : " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
		}
	}
}
