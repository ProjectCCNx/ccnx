package test.ccn.data.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.ExcludeComponent;
import com.parc.ccn.data.query.ExcludeElement;
import com.parc.ccn.data.query.ExcludeFilter;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNLibrary;

/**
 *  A class to help write tests without a repo. Pulls things
 *  through ccnd, under a set of namespaces.
 *  Cheesy -- uses excludes to not get the same content back.
 *  
 *  TODO FIX -- not entirely correct. Gets more duplicates than it needs to.
 *  
 *  See CCNVersionedInputStream for related stream-based flossing code.
 *  The "floss" term refers to "mental floss" -- think a picture of
 *  someone running dental floss in and out through their ears.
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
		synchronized(_interests) {
			if (_interests.containsKey(namespace)) {
				Library.logger().fine("Already handling namespace: " + namespace);
				return;
			}
			Library.logger().info("Flosser: handling namespace: " + namespace);
			Interest namespaceInterest = new Interest(namespace);
			_interests.put(namespace, namespaceInterest);
			_library.expressInterest(namespaceInterest, this);
		}
	}

	public Interest handleContent(ArrayList<ContentObject> results,
								  Interest interest) {
		Library.logger().finest("Interests registered: " + _interests.size() + " content objects returned: "+results.size());
		// Parameterized behavior that subclasses can override.
		ContentName interestName = null;
		for (ContentObject result : results) {
			Library.logger().fine("Got content for interest " + interest);
			processContent(result);
			// update the interest. follow process used by ccnslurp.
            // exclude the next component of this object, and set up a
            // separate interest to explore its children.
			// first, remove the interest from our list as we aren't going to
			// reexpress it in exactly the same way
			for (Entry<ContentName, Interest> entry : _interests.entrySet()) {
				if (entry.getValue().equals(interest)) {
					synchronized(_interests) {
						interestName = entry.getKey();
						_interests.remove(interestName);
					}
					break;
				}
			}
			
            int prefixCount = (null != interest.nameComponentCount()) ? interest.nameComponentCount() :
                interest.name().count();
            // DKS TODO should the count above be count()-1 and this just prefixCount?
            if (prefixCount == result.name().count()) {
            	if (null == interest.excludeFilter()) {
              		ArrayList<ExcludeElement> excludes = new ArrayList<ExcludeElement>();
               		excludes.add(new ExcludeComponent(result.contentDigest()));
            		interest.excludeFilter(new ExcludeFilter(excludes));
            		Library.logger().finest("Creating new exclude filter for interest " + interest.name());
            	} else {
            		if (interest.excludeFilter().exclude(result.contentDigest())) {
            			Library.logger().fine("We should have already excluded content digest: " + DataUtils.printBytes(result.contentDigest()) + " prefix count: " + interest.nameComponentCount());
            		} else {
            			// Has to be in order...
            			Library.logger().finest("Adding child component to exclude.");
            			interest.excludeFilter().add(new byte [][] { result.contentDigest() });
            		}
            	}
            	Library.logger().finer("Excluding content digest: " + DataUtils.printBytes(result.contentDigest()) + " onto interest " + interest.name() + " total excluded: " + interest.excludeFilter().size());
            } else {
               	if (null == interest.excludeFilter()) {
               		ArrayList<ExcludeElement> excludes = new ArrayList<ExcludeElement>();
               		excludes.add(new ExcludeComponent(result.name().component(prefixCount)));
            		interest.excludeFilter(new ExcludeFilter(excludes));
            		Library.logger().finest("Creating new exclude filter for interest " + interest.name());
               	} else {
                    if (interest.excludeFilter().exclude(result.name().component(prefixCount))) {
            			Library.logger().fine("We should have already excluded child component: " + ContentName.componentPrintURI(result.name().component(prefixCount)) + " prefix count: " + interest.nameComponentCount());                   	
                    } else {
                    	// Has to be in order...
                    	Library.logger().finest("Adding child component to exclude.");
            			interest.excludeFilter().add(
            					new byte [][] { result.name().component(prefixCount) });
                    }
            	}
               	Library.logger().finer("Excluding child " + ContentName.componentPrintURI(result.name().component(prefixCount)) + " total excluded: " + interest.excludeFilter().size());
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
		if (null != interest)
			_interests.put(interest.name(), interest);
		return interest;
	}
	
	public void stop() {
		Library.logger().info("Stop flossing.");
		synchronized (_interests) {
			for (Interest interest : _interests.values()) {
				Library.logger().info("Cancelling pending interest: " + interest.print());
				_library.cancelInterest(interest, this);
			}
		}
	}
	
	public void logNamespaces() {
		
		ContentName [] namespaces = getNamespaces().toArray(new ContentName[getNamespaces().size()]);
		for (ContentName name : namespaces) {
			Library.logger().info("Flosser: monitoring namespace: " + name);
		}
	}
	public Set<ContentName> getNamespaces() { return _interests.keySet(); }

	/**
	 * Override in subclasses that want to do something more interesting than log.
	 * @param result
	 */
	protected void processContent(ContentObject result) {
		Library.logger().info("Flosser got: " + result.name() + " Digest: " + 
				DataUtils.printBytes(result.contentDigest()));
	}
	
}
