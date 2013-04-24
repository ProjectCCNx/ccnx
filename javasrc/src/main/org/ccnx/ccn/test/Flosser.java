/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 *  A class to help write tests without a repository or setting up a separate
 *  thread to read data. A Flosser tries to pull all content written under
 *  a set of specified namespaces; essentially loading that content into ccnd. 
 *  Based on ccnslurp, uses excludes to not get the same data back
 *  
 *  By default does not floss namespaces below segments. This is because most
 *  commonly we will receive a file with a segmented namespace and no hierarchy below
 *  that. Trying to floss below this will lead to large numbers of unsatisfied interests 
 *  being expressed, adversely affecting performance.
 *  
 *  Call stopMonitoringNamespace as soon as you are done with a namespace to improve
 *  performance. 
 *  
 *  See CCNVersionedInputStream for related stream-based flossing code (basically
 *  a precursor to the full Flosser).
 *  
 *  The "floss" term refers to "mental floss" -- think a picture of
 *  someone running dental floss in and out through their ears; here we are
 *  running content from an app, in through ccnd, to the flosser, so other
 *  parts of the (test) app can pull it back from ccnd later.
 */
public class Flosser implements CCNContentHandler {
	
	CCNHandle _handle;
	Map<ContentName, Interest> _interests = new HashMap<ContentName, Interest>();
	Map<ContentName, Set<ContentName>> _subInterests = new HashMap<ContentName, Set<ContentName>>();
	HashSet<ContentObject> _processedObjects = new HashSet<ContentObject>();
	boolean _flossSubNamespaces = false;
	boolean _shutdown = false;
	
	/**
	 * Constructors that called handleNamespace() now throwing NullPointerException as this doesn't exist yet.
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public Flosser() throws ConfigurationException, IOException {
		_handle = CCNHandle.open();
	}
	
	public Flosser(ContentName namespace) throws ConfigurationException, IOException {
		this();
		handleNamespace(namespace);
	}
	
	public void stopMonitoringNamespace(String namespace) throws MalformedContentNameStringException {
		stopMonitoringNamespace(ContentName.fromNative(namespace));
	}
	
	public void stopMonitoringNamespace(ContentName namespace) {
		synchronized(_interests) {
			if (!_interests.containsKey(namespace)) {
				return;
			}
			Set<ContentName> subInterests = _subInterests.get(namespace);
			if (null != subInterests) {
				Iterator<ContentName> it = subInterests.iterator();
				while (it.hasNext()) {
					ContentName subNamespace = it.next();
					removeInterest(subNamespace);
					it.remove();
				}
			}
			// Remove the top-level interest.
			removeInterest(namespace);
			_subInterests.remove(namespace);
			Log.info(Log.FAC_TEST, "FLOSSER: no longer monitoring namespace: {0}", namespace);
		}
	}
	
	public void stopMonitoringNamespaces() {
		synchronized(_interests) {
			Set<ContentName> namespaceSet = getNamespaces();
			// Go through a few hoops to avoid a ConcurrentModificationException, as code
			// other than us is going to remove the things we're iterating over from the Set.
			ContentName [] namespaces = new ContentName[namespaceSet.size()];
			namespaces = namespaceSet.toArray(namespaces);
			for (int i=0; i < namespaces.length; ++i) {
				stopMonitoringNamespace(namespaces[i]);
			}
		}
	}
	
	public void setFlossSubNamespaces(boolean flag) {
		_flossSubNamespaces = flag;
	}
	
	protected void removeInterest(ContentName namespace) {
		synchronized(_interests) {
			if (!_interests.containsKey(namespace)) {
				return;
			}
			Interest interest = _interests.get(namespace);
			_handle.cancelInterest(interest, this);
			_interests.remove(namespace);
			Log.fine(Log.FAC_TEST, "Cancelled interest in {0}", namespace);
		}
	}
	
	public void handleNamespace(String namespace) throws MalformedContentNameStringException, IOException {
		handleNamespace(ContentName.fromNative(namespace));
	}
	
	/**
	 * Handle a top-level namespace.
	 * @param namespace
	 * @throws IOException
	 */
	public void handleNamespace(ContentName namespace) throws IOException {
		synchronized(_interests) {
			if (_shutdown) {
				Log.info(Log.FAC_TEST, "FLOSSER: in the process of shutting down. Not handling new namespace {0}.", namespace);
				return;
			}
			if (_interests.containsKey(namespace)) {
				Log.fine(Log.FAC_TEST, "FLOSSER: Already handling namespace: {0}", namespace);
				return;
			}
			Log.info(Log.FAC_TEST, "FLOSSER: handling namespace: {0}", namespace);
			Interest namespaceInterest = new Interest(namespace);
			_interests.put(namespace, namespaceInterest);
			_handle.expressInterest(namespaceInterest, this);
			Set<ContentName> subNamespaces = _subInterests.get(namespace);
			if (null == subNamespaces) {
				subNamespaces = new HashSet<ContentName>();
				_subInterests.put(namespace, subNamespaces);
				Log.info(Log.FAC_TEST, "FLOSSER: setup parent namespace: {0}", namespace);
			}			
		}
	}
	
	public void handleNamespace(ContentName namespace, ContentName parent) throws IOException {
		synchronized(_interests) {
			if (_shutdown) {
				Log.info(Log.FAC_TEST, "FLOSSER: in the process of shutting down. Not handling new subnamespace {0} under parent {1}.", 
						namespace, parent);
				return;
			}
			if (_interests.containsKey(namespace)) {
				Log.fine(Log.FAC_TEST, "Already handling child namespace: {0}", namespace);
				return;
			}
			Log.info(Log.FAC_TEST, "FLOSSER: handling child namespace: {0} expected parent: {1}", namespace, parent);
			Interest namespaceInterest = new Interest(namespace);
			namespaceInterest.minSuffixComponents(2);	// Don't reget the parent
			_interests.put(namespace, namespaceInterest);
			_handle.expressInterest(namespaceInterest, this);
			
			// Now we need to find a parent in the subInterest map, and reflect this namespace underneath it.
			ContentName parentNamespace = parent;
			Set<ContentName> subNamespace = _subInterests.get(parentNamespace);
			while ((subNamespace == null) && (!parentNamespace.equals(ContentName.ROOT))) {
				parentNamespace = parentNamespace.parent();
				subNamespace = _subInterests.get(parentNamespace);
				Log.info(Log.FAC_TEST, "FLOSSER: initial parent not found in map, looked up {0} found in map? {1}", parentNamespace, ((null == subNamespace) ? "no" : "yes"));
			}
			if (null != subNamespace) {
				Log.info(Log.FAC_TEST, "FLOSSER: Adding subnamespace: {0} to ancestor {1}", namespace, parentNamespace);
				subNamespace.add(namespace);
			} else {
				Log.info(Log.FAC_TEST, "FLOSSER: Cannot find ancestor namespace for {0}", namespace);
				for (ContentName n : _subInterests.keySet()) {
					Log.info(Log.FAC_TEST, "FLOSSER: 		available ancestor: {0}", n);
				}
			}
		}
	}

	public Interest handleContent(ContentObject result,
								  Interest interest) {
		Log.finest(Log.FAC_TEST, "Interests registered: " + _interests.size() + " content object returned");
		// Parameterized behavior that subclasses can override.
		ContentName interestName = null;
		if (_processedObjects.contains(result)) {
			Log.fine(Log.FAC_TEST, "FLOSSER: Got repeated content for interest: {0} content: {1}", interest, result.name());
		} else {
			Log.finest(Log.FAC_TEST, "FLOSSER: Got new content for interest {0} content name: {1}", interest, result.name());
			processContent(result);
			// update the interest. follow process used by ccnslurp.
			// exclude the next component of this object, and set up a
			// separate interest to explore its children.
			// first, remove the interest from our list as we aren't going to
			// reexpress it in exactly the same way
			synchronized(_interests) {
				for (Entry<ContentName, Interest> entry : _interests.entrySet()) {
					if (entry.getValue().equals(interest)) {
						interestName = entry.getKey();
						_interests.remove(interestName);
						break;
					}
				}
			}

			int prefixCount = interest.name().count();
			// DKS TODO should the count above be count()-1 and this just prefixCount?
			if (prefixCount == result.name().count()) {
				if (null == interest.exclude()) {
					ArrayList<Exclude.Element> excludes = new ArrayList<Exclude.Element>();
					excludes.add(new ExcludeComponent(result.digest()));
					interest.exclude(new Exclude(excludes));
					Log.finest(Log.FAC_TEST, "Creating new exclude filter for interest {0}", interest.name());
				} else {
					if (interest.exclude().match(result.digest())) {
						Log.fine(Log.FAC_TEST, "We should have already excluded content digest: " + DataUtils.printBytes(result.digest()));
					} else {
						// Has to be in order...
						Log.finest(Log.FAC_TEST, "Adding child component to exclude.");
						interest.exclude().add(new byte [][] { result.digest() });
					}
				}
				Log.finer(Log.FAC_TEST, "Excluding content digest: " + DataUtils.printBytes(result.digest()) + " onto interest {0} total excluded: " + interest.exclude().size(), interest.name());
			} else {
				// Add an exclude for the content we just got
				// DKS TODO might need to split to matchedComponents like ccnslurp
				if (null == interest.exclude()) {
					ArrayList<Exclude.Element> excludes = new ArrayList<Exclude.Element>();
					excludes.add(new ExcludeComponent(result.name().component(prefixCount)));
					interest.exclude(new Exclude(excludes));
					Log.finest(Log.FAC_TEST, "Creating new exclude filter for interest {0}", interest.name());
				} else {
					if (interest.exclude().match(result.name().component(prefixCount))) {
						Log.fine(Log.FAC_TEST, "We should have already excluded child component: {0}", Component.printURI(result.name().component(prefixCount)));                   	
					} else {
						// Has to be in order...
						Log.finest(Log.FAC_TEST, "Adding child component to exclude.");
						interest.exclude().add(
								new byte [][] { result.name().component(prefixCount) });
					}
				}
				Log.finer(Log.FAC_TEST, "Excluding child " + Component.printURI(result.name().component(prefixCount)) + " total excluded: " + interest.exclude().size());

				if (_flossSubNamespaces || SegmentationProfile.isNotSegmentMarker(result.name().component(prefixCount))) {
					ContentName newNamespace = null;
					try {
						if (interest.name().count() == result.name().count()) {
							newNamespace = new ContentName(interest.name(), result.digest());
							Log.info(Log.FAC_TEST, "Not adding content exclusion namespace: {0}", newNamespace);
						} else {
							newNamespace = new ContentName(interest.name(), 
									result.name().component(interest.name().count()));
							Log.info(Log.FAC_TEST, "Adding new namespace: {0}", newNamespace);
							handleNamespace(newNamespace, interest.name());
						}
					} catch (IOException ioex) {
						Log.warning("IOException picking up namespace: {0}", newNamespace);
					}
				}
			}
		}
		if (null != interest)
			synchronized(_interests) {
				_interests.put(interest.name(), interest);
			}
		return interest;
	}
	
	public void stop() {
		Log.info(Log.FAC_TEST, "Stop flossing.");
		synchronized (_interests) {
			_shutdown = true;
			stopMonitoringNamespaces();
			Log.info(Log.FAC_TEST, "Stopped flossing: remaining namespaces {0} (should be 0), subnamespaces {1} (should be 0).",
						_interests.size(), _subInterests.size());
		}
		_handle.close();
	}
	
	public void logNamespaces() {
		
		ContentName [] namespaces = getNamespaces().toArray(new ContentName[getNamespaces().size()]);
		for (ContentName name : namespaces) {
			Log.info("Flosser: monitoring namespace: " + name);
		}
	}
	public Set<ContentName> getNamespaces() {
		synchronized (_interests) {
			return _interests.keySet();
		}
	}

	/**
	 * Override in subclasses that want to do something more interesting than log.
	 * @param result
	 */
	protected void processContent(ContentObject result) {
		Log.info(Log.FAC_TEST, "Flosser got: " + result.fullName());
	}
	
}
