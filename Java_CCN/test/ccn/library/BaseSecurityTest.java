package test.ccn.library;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentObject;

public class BaseSecurityTest extends BaseLibraryTest {
	
	protected static final String testName = "/test/smetters/signTestContent.txt";
	protected static final String testContent = "Mary had a little lamb. Its fleece was reasonably white for a sheep who'd been through that sort of thing.";
	
	
	public void checkGetResults(ArrayList<ContentObject> getResults) {
		boolean verifySig = false;
		for (int i=0; i < getResults.size(); ++i) {
			try {
				verifySig = getResults.get(i).verify(null);
				Library.logger().info("Get signature verified? " + verifySig);
				assertTrue(verifySig);
			} catch (Exception e) {
				Library.logger().info("Exception in checkGetResults for name: " + getResults.get(i).name() +": " + e.getClass().getName() + " " + e.getMessage());
				Library.infoStackTrace(e);
				fail();			
			}
		} 
	}
	
	public void checkPutResults(CompleteName putResult) {
		try {
		} catch (Exception e) {
			Library.logger().info("Exception in checkPutResults for name: " + putResult.name() +": " + e.getClass().getName() + " " + e.getMessage());
			Library.infoStackTrace(e);
			fail();			
		}
	}

}
