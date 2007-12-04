/**
 * 
 */
package test.ccn.library;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.SignatureException;

import junit.framework.Assert;

import org.junit.Test;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.library.StandardCCNLibrary;


/**
 * @author briggs
 *
 */
public class StandardCCNLibraryTest {
	static final String contentString = "This is a very small amount of content";
	
	@Test
	public void testPut() {
		StandardCCNLibrary library = null;
		try {
			library = new StandardCCNLibrary();
		} catch (ConfigurationException e1) {
			e1.printStackTrace();
		}
		Assert.assertNotNull(library);
		
		ContentName name = null;
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
		try {
			CompleteName result = library.put(name, content, publisher);
			System.out.println("Resulting CompleteName: " + result);
		} catch (SignatureException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
}
