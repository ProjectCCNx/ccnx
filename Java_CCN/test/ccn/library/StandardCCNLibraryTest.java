/**
 * 
 */
package test.ccn.library;

import java.io.UnsupportedEncodingException;

import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.library.StandardCCNLibrary;
import com.parc.ccn.security.keys.KeyManager;


/**
 * @author briggs
 *
 */
public class StandardCCNLibraryTest {

	@Test
	public void testPut() {
		KeyManager keyManager = new  KeyManager();
		StandardCCNLibrary library = new StandardCCNLibrary(keyManager);
		ContentName name = null;
		String contentString = "This is a very small amount of content";
		byte[] content = null;
//		ContentAuthenticator.ContentType type = ContentAuthenticator.ContentType.LEAF;
		PublisherID publisher = null;
		
		try {
			content = contentString.getBytes("UTF-8");	
		} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
		}
		
		try {
			name = new ContentName("/test/briggs/foo.txt");
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		library.put(name, content, publisher);
	}
	
}
