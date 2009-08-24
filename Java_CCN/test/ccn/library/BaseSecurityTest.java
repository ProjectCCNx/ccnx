package test.ccn.library;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;

import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

import com.parc.ccn.config.SystemConfiguration;

public class BaseSecurityTest extends BasePutGetTest {
	
	protected static final String testName = "/test/smetters/signTestContent.txt";
	protected static final String testContent = "Mary had a little lamb. Its fleece was reasonably white for a sheep who'd been through that sort of thing.";
	
	
	public void checkGetResults(ArrayList<ContentObject> getResults) {
		boolean verifySig = false;
		for (int i=0; i < getResults.size(); ++i) {
			try {
				verifySig = getResults.get(i).verify(null);
				if (!verifySig) {
					SystemConfiguration.logObject("checkGetResults: verification failed", getResults.get(i));
				} else {
					SystemConfiguration.logObject("checkGetResults: verification succeeded", getResults.get(i));
				}
				assertTrue(verifySig);
			} catch (Exception e) {
				Library.logger().info("Exception in checkGetResults for name: " + getResults.get(i).name() +": " + e.getClass().getName() + " " + e.getMessage());
				Library.infoStackTrace(e);
				fail();			
			}
		} 
	}
	
	public void checkPutResults(ContentObject putResult) {
		try {
			// Check content and verify signature.
			// If we have a static fragmentation marker, remove it.
			ContentName baseName = SegmentationProfile.segmentRoot(putResult.name());
			int val = Integer.parseInt(new String(baseName.component(baseName.count()-1)));
			ContentObject co = new ContentObject(putResult.name(), putResult.signedInfo(), Integer.toString(val).getBytes(), putResult.signature());
			boolean b = co.verify(null);
			if (!b) {
				SystemConfiguration.logObject("checkPutResults: verification failed", co);
			} else {
				SystemConfiguration.logObject("checkPutResults: verification succeeded", co);
			}
		} catch (Exception e) {
			Library.logger().info("Exception in checkPutResults for name: " + putResult.name() +": " + e.getClass().getName() + " " + e.getMessage());
			Library.infoStackTrace(e);
			fail();			
		}
	}

}
