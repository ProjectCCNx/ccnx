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
import com.parc.ccn.data.query.ExcludeElement;
import com.parc.ccn.data.query.ExcludeFilter;
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
		if (_interests.containsKey(namespace)) {
			Library.logger().fine("Already handling namespace: " + namespace);
			return;
		}
		Interest namespaceInterest = new Interest(interestNamespace);
		_interests.put(interestNamespace, namespaceInterest);
		_library.expressInterest(namespaceInterest, this);
	}

	public Interest handleContent(ArrayList<ContentObject> results,
								  Interest interest) {
		// Parameterized behavior that subclasses can override.
		for (ContentObject result : results) {
			Library.logger().fine("Got content for interest " + interest);
			processContent(result);
			// update the interest. follow process used by ccnslurp.
            // exclude the next component of this object, and set up a
            // separate interest to explore its children.
            int prefixCount = (null != interest.name().prefixCount()) ? interest.name().prefixCount() :
                interest.name().count();
            // DKS TODO should the count above be count()-1 and this just prefixCount?
            if (prefixCount == result.name().count()) {
            	if (null == interest.excludeFilter()) {
              		ArrayList<ExcludeElement> excludes = new ArrayList<ExcludeElement>();
               		excludes.add(new ExcludeElement(result.contentDigest()));
            		interest.excludeFilter(new ExcludeFilter(excludes));
            		Library.logger().finest("Creating new exclude filter for interest " + interest.name());
            	} else {
            		if (interest.excludeFilter().exclude(result.contentDigest())) {
            			Library.logger().fine("We should have already excluded content digest: " + printBytesAsHex(result.contentDigest()) + " prefix count: " + interest.name().prefixCount());
            		} else {
            			// Has to be in order...
            			Library.logger().finest("Adding child component to exclude.");
            			interest.excludeFilter().values().add(new ExcludeElement(result.contentDigest()));
            		}
            	}
            	Library.logger().finer("Excluding content digest: " + printBytesAsHex(result.contentDigest()) + " onto interest " + interest.name() + " total excluded: " + interest.excludeFilter().values().size());
            } else {
               	if (null == interest.excludeFilter()) {
               		ArrayList<ExcludeElement> excludes = new ArrayList<ExcludeElement>();
               		excludes.add(new ExcludeElement(result.name().component(prefixCount)));
            		interest.excludeFilter(new ExcludeFilter(excludes));
            		Library.logger().finest("Creating new exclude filter for interest " + interest.name());
           	} else {
                    if (interest.excludeFilter().exclude(result.name().component(prefixCount))) {
            			Library.logger().fine("We should have already excluded child component: " + ContentName.componentPrintURI(result.name().component(prefixCount)) + " prefix count: " + interest.name().prefixCount());                   	
                    } else {
                    	// Has to be in order...
                    	Library.logger().finest("Adding child component to exclude.");
            			interest.excludeFilter().values().add(new ExcludeElement(result.name().component(prefixCount)));                    	
                    }
            	}
               	Library.logger().finer("Excluding child " + ContentName.componentPrintURI(result.name().component(prefixCount)) + " total excluded: " + interest.excludeFilter().values().size());
                // DKS TODO might need to split to matchedComponents like ccnslurp
                ContentName newNamespace = null;
                try {
                	if (interest.name().count() == result.name().count()) {
                		newNamespace = new ContentName(interest.name(), result.contentDigest());
                	} else {
                		newNamespace = new ContentName(interest.name(), 
                			result.name().component(interest.name().count()));
                	}
                	Library.logger().info("Adding new namespace: " + newNamespace);
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
