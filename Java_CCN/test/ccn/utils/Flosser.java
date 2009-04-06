package test.ccn.utils;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;

/**
 *  A class to help write tests without a repo. Pulls things
 *  through ccnd, under a set of namespaces.
 *  Cheesy -- uses excludes to not get the same content back.
 *  Will saturate bloom filter eventually.
 * @author smetters
 *
 */
public class Flosser implements CCNInterestListener {
	
	CCNLibrary _library;
	Map<ContentName, Interest> _interests = new HashMap<ContentName, Interest>();
	
	public Flosser() throws ConfigurationException, IOException {
		this(ContentName.ROOT);
	}
	
	public Flosser(ContentName namespace) throws ConfigurationException, IOException {
		_library = CCNLibrary.open();
		handleNamespace(namespace);
	}
	
	public Flosser(String namespace) throws MalformedContentNameStringException, ConfigurationException, IOException {
		this(ContentName.fromNative(namespace));
	}
	
	public void handleNamespace(ContentName namespace) throws IOException {
		// This is bad -- have to set prefix count on name which might be used
		// other places. Prefix count should be in the interest, not the name.
		ContentName interestNamespace = new ContentName(namespace, namespace.count());
		Interest namespaceInterest = new Interest(interestNamespace);
		_interests.put(interestNamespace, namespaceInterest);
		_library.expressInterest(namespaceInterest, this);
	}

	public Interest handleContent(ArrayList<ContentObject> results,
								  Interest interest) {
		// Parameterized behavior that subclasses can override.
		for (ContentObject result : results) {
			processContent(result);
			// update the interest. follow process used by ccnslurp.
            // exclude the next component of this object, and set up a
            // separate interest to explore its children.
            int prefixCount = (null != interest.name().prefixCount()) ? interest.name().prefixCount() :
                interest.name().count();
            // DKS TODO should the count above be count()-1 and this just prefixCount?
            if (prefixCount == result.name().count()) {
            	if (null == interest.excludeFilter()) {
            		interest.excludeFilter(Interest.constructFilter(new byte[][]{result.contentDigest()}));
            	} else {
            		interest.excludeFilter().exclude(result.contentDigest());
            	}
            } else {
               	if (null == interest.excludeFilter()) {
            		interest.excludeFilter(Interest.constructFilter(new byte[][]{result.name().component(prefixCount-1)}));
            	} else {
                    interest.excludeFilter().exclude(result.name().component(prefixCount-1));
            	}
                // DKS TODO might need to split to matchedComponents like ccnslurp
                ContentName newNamespace = null;
                try {
                	newNamespace = new ContentName(interest.name(), 
                    		result.name().component(interest.name().count()-1));
                handleNamespace(newNamespace);
                } catch (IOException ioex) {
                	Library.logger().warning("IOException picking up namespace: " + newNamespace);
                }
            }
		}
		return interest;
	}
	
	public void logNamespaces() {
		Set<ContentName> namespaces = getNamespaces();
		for (ContentName name : namespaces) {
			Library.logger().info("Flosser: monitoring namespace: " + name);
		}
	}
	public Set<ContentName> getNamespaces() { return _interests.keySet(); }

	protected void processContent(ContentObject result) {
		Library.logger().info("Flosser got: " + result.name() + " Digest: " + printBytesAsHex(result.contentDigest()));
	}
	
	public static String printBytesAsHex(byte [] value) {
		BigInteger bi = new BigInteger(1, value);
		return bi.toString(16);
	}
}
