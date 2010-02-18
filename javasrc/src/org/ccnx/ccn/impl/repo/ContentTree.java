/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.repo;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Level;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.CommandMarkers;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;

/**
 * Creates a tree structure to track the data stored within a LogStructRepoStore RepositoryStore.
 * 
 * Implements binary tree based algorithms to store and retrieve data based on interests and
 * NameEnumeration
 *
 */
public class ContentTree {
	
	public interface ContentGetter {
		public ContentObject get(ContentRef ref);
	}
	
	/**
	 * TreeNode is the data structure representing one
	 * node of a tree which may have children and/or content.
	 * Every child has a distinct name (it's component) but 
	 * there may be multiple content objects ending with the 
	 * same component (i.e. having same content digest at end
	 * but presumably different publisher etc. that is not 
	 * visible in this tree)
	 */
	public class TreeNode implements Comparable<TreeNode>{
		byte[] component; // name of this node in the tree, null for root only
		// oneChild is special case when there is only 
		// a single child (to save obj overhead).
		// either oneChild or children should be null
		TreeNode oneChild;
		SortedMap<TreeNode, TreeNode> children;
		// oneContent is special case when there is only 
		// a single content object here (to save obj overhead).
		// either oneContent or content should be null
		ContentRef oneContent;
		List<ContentRef> content;
		long timestamp;
		boolean interestFlag = false;
		
		public boolean compEquals(byte[] other) {
			return DataUtils.compare(other, this.component) == 0;
		}
		public TreeNode getChild(byte[] component) {
			if (null != oneChild) {
				if (oneChild.compEquals(component)) {
					return oneChild;
				}
			} else if (null != children) {
				TreeNode child = new TreeNode();
				child.component = component;
				return children.get(child);
			}
			return null;
		}
		
		public String toString(){
			String s = "";

			if(component==null)
				s = "root";
			else{
				s = ContentName.componentPrintURI(component);				
			}
			if(oneChild!=null){
				//there is only one child
				s+= " oneChild: "+ContentName.componentPrintURI(component);
			}
			else if(children!=null){
				s+= " children: ";
				int i = 0;
				for(TreeNode c: children.keySet()){
					//append each child to string
					s+=" "+ContentName.componentPrintURI(c.component);
					//s+=new String(t.component)+" ";
					if (++i > 50) {
						s+= "...";
						break;
					}
				}
			}
			else
				s+=" oneChild and children were null";

			return s;
		}
		
		public int compareTo(TreeNode o1) {
			return DataUtils.compare(component, o1.component);
		}
	}
	
	/**
	 * Prescreen candidates against elements of an interest that we can so
	 * we don't need to consider candidates that have no chance of matching.
	 * Currently we prescreen for matching the exclude filter if there is one
	 * and that the candidate has the correct number of components.
	 */
	protected class InterestPreScreener {
		private int _minComponents = 0;
		private int _maxComponents = 32767;
		private Exclude _exclude;
		protected InterestPreScreener(Interest interest) {
			if (null != interest.minSuffixComponents())
				_minComponents = interest.minSuffixComponents();
			if (null != interest.maxSuffixComponents())
				_maxComponents = interest.maxSuffixComponents() + 1;
			_exclude = interest.exclude();
		}
		
		/**
		 * Run the prescreen
		 * @param level
		 * @return -1 => reject all entries below this
		 * 			0 => reject this entry but keep searching
		 * 			1 => keep this entry
		 */
		protected int preScreen(TreeNode node, int level) {
			if (level > _maxComponents)
				return -1;
			if (level == 1 && null != _exclude) {
				if (_exclude.match(node.component))
					return -1;
			}
			return (level < _minComponents) ? 0 : 1;
		}
	}
	
	protected TreeNode _root;
	
	public ContentTree() {
		_root = new TreeNode();
		_root.component = null; // Only the root has a null value
	}
	
	/**
	 * Insert entry for the given ContentObject.
	 * 
	 * Inserts at a parent with the interest flag set create a NameEnumerationResponse object
	 * to send out in response to a name enumeration request that was not answered do to no new
	 * information existing.  If the interest flag is not set at the parent, a name enumeration
	 * response is not written.
	 * 
	 * @param content the data to insert
	 * @param ref pointer to position of data in the file storage
	 * @param ts last modification time of the data
	 * @param getter to retrieve previous content to check for duplication
	 * @param ner NameEnumerationResponse object to populate if the insert occurs at a parent
	 *   with the interest flag set
	 * @return - true if content is not exact duplicate of existing content.
	 */
	public boolean insert(ContentObject content, ContentRef ref, long ts, ContentGetter getter, NameEnumerationResponse ner) {
		final ContentName name = content.fullName();
		if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
			Log.fine("inserting content: {0}", name);
		}
		TreeNode node = _root; // starting point
		assert(null != _root);
		boolean added = false;
		
		for (byte[] component : name.components()) {
			synchronized(node) {
				//Library.finest("getting node for component: "+new String(component));
				TreeNode child = node.getChild(component);
				if (null == child) {
					if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
						Log.finest("child was null: adding here");
					}
					// add it
					added = true;
					child = new TreeNode();
					child.component = component;
					if (null == node.oneChild && null == node.children) {
						// This is first and only child of current node
						node.oneChild = child;
					} else if (null == node.oneChild) {
						// Multiple children already, just add this one to current node
						node.children.put(child, child);
					} else {
						// Second child in current node, need to switch to list
						node.children = new TreeMap<TreeNode, TreeNode>();
						node.children.put(node.oneChild, node.oneChild);
						node.children.put(child, child);
						node.oneChild = null;
					}
					node.timestamp = ts;
					
					if (node.interestFlag && (ner==null || ner.getPrefix()==null)){
						//we have added something to this node and someone was interested
						//we need to get the child names and the prefix to send back
						if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
							Log.info("we added at least one child, need to send a name enumeration response");
						}
						ContentName prefix = name.cut(component);
	
						prefix = new ContentName(prefix, CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION);
						//prefix = VersioningProfile.addVersion(prefix, new CCNTime(node.timestamp));
						if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
							Log.info("prefix for FastNEResponse: {0}", prefix);
							Log.info("response name will be: {0}", VersioningProfile.addVersion(new ContentName(prefix, CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION), new CCNTime(node.timestamp)));
						}
	
						ArrayList<ContentName> names = new ArrayList<ContentName>();
						// the parent has children we need to return
						ContentName c = new ContentName();
						if (node.oneChild != null) {
							names.add(new ContentName(c,
									node.oneChild.component));
						} else {
							if (node.children != null) {
								for (TreeNode ch : node.children.keySet())
									names.add(new ContentName(c, ch.component));
							}
						}
						ner.setPrefix(prefix);
						ner.setNameList(names);
						ner.setTimestamp(new CCNTime(node.timestamp));
						if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
							Log.info("resetting interestFlag to false");
						}
						node.interestFlag = false;
					}
				}
				
				//Library.finest("child was not null: moving down the tree");
				node = child;
			}
		}
		
		// Check for duplicate content
		if (!added) {
			if (null != node.oneContent) {
				ContentObject prev = getter.get(node.oneContent);
				if (null != prev && content.equals(prev))
					return false;
			} else if (null != node.content) {
				for (ContentRef oldRef : node.content) {
					ContentObject prev = getter.get(oldRef);
					if (null != prev && content.equals(prev))
						return false;
				}
			}
		}

		// At conclusion of this loop, node must be holding the last node for this name
		// so we insert the ref there
		if (null == node.oneContent && null == node.content) {
			// This is first and only content at this leaf
			node.oneContent = ref;
		} else if (null == node.oneContent) {
			// Multiple content already at this node, add this one
			node.content.add(ref);
		} else {
			// Second content at current node, need to switch to list
			node.content = new ArrayList<ContentRef>();
			node.content.add(node.oneContent);
			node.content.add(ref);
			node.oneContent = null;
		}
		if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
			Log.fine("Inserted: {0}", content.name());
		}
		return true;
	}

	/**
	 * Find the node for the given name
	 * 
	 * @param name ContentName to search for
	 * @param count depth to search
	 * @return node containing the name
	 */
	protected TreeNode lookupNode(ContentName name, int count) {
		TreeNode node = _root; // starting point
		
		assert(null != _root);
		if (count < 1) {
			return node;
		}
		
		for (byte[] component : name.components()) {
			synchronized(node) {
				TreeNode child = node.getChild(component);
				if (null == child) {
					// Mismatch, no child for the given component so nothing under this name
					return null;
				}
				node = child;
				count--;
				if (count < 1) {
					break;
				}
			}
		}
		return node;
	}
	
	/**
	 * Return the content objects with exactly the given name
	 * 
	 * @param name ContentName to lookup
	 * @return node containing the name
	 */
	protected final List<ContentRef> lookup(ContentName name) {
		TreeNode node = lookupNode(name, name.count());
		if (null != node) {
			if (null != node.oneContent) {
				ArrayList<ContentRef> result = new ArrayList<ContentRef>();
				result.add(node.oneContent);
				return result;
			} else {
				return node.content;
			}
		} else {
			return null;	
		}
	}
	
	/**
	 * Dump current names to an output file for debugging
	 * 
	 * @param output the output file
	 * @param maxNodeLen max characters to include in a component name in the output
	 */
	public void dumpNamesTree(PrintStream output, int maxNodeLen) {		
		assert(null != _root);
		assert(null != output);
		
		output.println("Dumping tree of names of indexed content at " + new Date().toString());
		if (maxNodeLen > 0) {
			output.println("Node names truncated to max " + maxNodeLen + " characters");
		}
		dumpRecurse(output, _root, "", maxNodeLen);
	}
	
	// Note: this is not thread-safe against everything else going on.
	protected void dumpRecurse(PrintStream output, TreeNode node, String indent, int maxNodeLen) {
		String myname = null;
		if (null == node.component) {
			// Special case of root
			myname = "/";
		} else {
			myname = ContentName.componentPrintURI(node.component);
			if (maxNodeLen > 0 && myname.length() > (maxNodeLen - 3)) {
				myname = "<" + myname.substring(0,maxNodeLen-4) + "...>";
			}
		}
		int mylen = myname.length();
		output.print(myname);
		if (null != node.oneChild) {
			output.print("---");
			dumpRecurse(output, node.oneChild, String.format("%s%" + mylen + "s   ", indent, ""), maxNodeLen);
		} else if (null != node.children) {
			int count = 1; int last = node.children.size();
			for (TreeNode child : node.children.values()) {
				if (1 == count) {
					// First child
					output.print("-+-");
					dumpRecurse(output, child, String.format("%s%" + mylen + "s | ", indent, ""), maxNodeLen);
				} else if (last == count) {
					// Last child
					output.println();
					output.printf("%s%" + mylen + "s +-", indent, "");
					dumpRecurse(output, child, String.format("%s%" + mylen + "s   ", indent, ""), maxNodeLen);
				} else {
					// Interior child delimiter
					output.println();
					output.printf("%s%" + mylen + "s |-", indent, "");
					dumpRecurse(output, child, String.format("%s%" + mylen + "s | ", indent, ""), maxNodeLen);
				}
				count++;
			}
		}
	}

	/**
	 * Search for data matching an interest that specifies either the leftmost (canonically smallest) match or
	 * doesn't specify a specific way to match data within several pieces of matching data.
	 * 
	 * @param interest the interest to match
	 * @param matchmin minimum number of components required in final answer or -1 if not specified
	 * @param matchmax maximum number of components allowed in final answer or -1 if not specified
	 * @param node the node rooting a subtree to search
	 * @param nodeName the full name of this node from the root up to and its component
	 * @param depth the length of name of node including its component (number of components)
	 * @param anyOK true if we aren't required to go left at this level
	 * @param getter a handler to pull actual ContentObjects for final match testing
	 * @return ContentObject matching the interest or null if not found
	 */
	protected final ContentObject leftSearch(Interest interest, int matchmin, int matchmax, TreeNode node, ContentName nodeName, int depth, boolean anyOK, ContentGetter getter) {
		if ( (nodeName.count() >= 0) && ((matchmin == -1 && matchmax == -1) || (matchmin == -1 && depth <= matchmax))
																		  || (matchmax == -1 && depth >= matchmin)
																		  || (matchmin != -1 && matchmax != -1 && (depth <= matchmax && depth >= matchmin))) {
			if (null != node.oneContent || null != node.content) {
				// Since the name INCLUDES digest component and the Interest.matches() convention for name
				// matching is that the name DOES NOT include digest component (conforming to the convention 
				// for ContentObject.name() that the digest is not present) we must REMOVE the content 
				// digest first or this test will not always be correct
				//
				// That is unless we are specifically trying to exclude a digest...
				// The test below which uses the name with the digest for the match only if there is an exclude request
				// and the exclude is at the final (digest) level of the name does that. Since we are prechecking without
				// the actual ContentObject there may be no cleaner way to do this.
				ContentName digestFreeName = new ContentName(nodeName.count()-1, nodeName.components());
				Interest publisherFreeInterest = interest.clone();
				publisherFreeInterest.publisherID(null);
				boolean initialMatch = (null != interest.exclude() && interest.name().count() == nodeName.count() - 1) 
							? publisherFreeInterest.matches(nodeName, null)
							: publisherFreeInterest.matches(digestFreeName, null); 
				if (initialMatch) {
					List<ContentRef> content = null;
					synchronized(node) {
						if (null != node.oneContent) {
							content = new ArrayList<ContentRef>();
							content.add(node.oneContent);
						} else {
							assert(null != node.content);
							content = new ArrayList<ContentRef>(node.content);
						}
					}
					for (ContentRef ref : content) {
						ContentObject cand = getter.get(ref);
						if (interest.matches(cand)) {
							return cand;
						}
					}
				}
			}
		}
		// Now search children if applicable and if any
		if (matchmax != -1 && matchmax <= depth || (node.children==null && node.oneChild==null)) {
			// Any child would make the total name longer than requested so no point in 
			// checking children
			return null;
		}
		SortedMap<TreeNode, TreeNode> children = null;
		synchronized(node) {
			if (null != node.oneChild) {
				children = new TreeMap<TreeNode, TreeNode>(); // Don't bother with comparator, will only hold one element
				children.put(node.oneChild, node.oneChild);
			} else {
				children = node.children;
			}
		}
		if (null != children) {
			byte[] interestComp = interest.name().component(depth);
			TreeNode testNode = new TreeNode();
			testNode.component = interestComp;
			SortedMap<TreeNode, TreeNode> set = anyOK || null == interestComp ? children : children.tailMap(testNode);
			for (TreeNode child : set.keySet()) {
				int comp = DataUtils.compare(child.component, interestComp);
				//if (null == interestComp || DataUtils.compare(child.component, interestComp) >= 0) {
				if (anyOK || comp >= 0) {
					ContentObject result = null;
					result = leftSearch(interest, matchmin, matchmax, child, 
							new ContentName(nodeName, child.component), depth+1, comp > 0, getter);
	
					if (null != result) {
						return result;
					}
				}
			}
		}
		// No match found
		return null;
	}
	
	/**
	 * Get all nodes below the given one
	 *
	 * @param node the starting node
	 * @param result list of nodes we are looking for
	 * @param minComponents minimum depth below here
	 * @param maxComponents maximum depth below here
	 */
	protected boolean getSubtreeNodes(TreeNode node, List<TreeNode> result, int level, InterestPreScreener ips) {
		boolean found = false;
		int preScreen = ips.preScreen(node, level);
		if (preScreen < 0)
			return false;
		synchronized(node) {
			if (null != node.oneChild) {
				found = getSubtreeNodes(node.oneChild, result, ++level, ips);
			} else if (null != node.children) {
				for (TreeNode child : node.children.values()) {
					boolean tmpFound = getSubtreeNodes(child, result, ++level, ips);
					if (!found && tmpFound)
						found = tmpFound;
				}
			} else {
				found = preScreen > 0;
			}
		}
		if (found)
			result.add(node);
		if (Log.isLoggable(Level.FINEST))
			Log.finest("getSubtreeNodes - found was {0}", found);
		return found;
	}
	
	/**
	 * Search for data matching an interest in which the rightmost (canonically largest) data among several
	 * matching pieces should be returned.
	 * 
	 * @param interest
	 * @param matchlen
	 * @param node
	 * @param nodeName
	 * @param depth
	 * @param getter
	 * @return
	 */
	protected final ContentObject rightSearch(Interest interest, int matchlen, TreeNode node, ContentName nodeName, int depth, ContentGetter getter) {
		// A shortcut compared to leftSearch() for moment, just accumulate all options in forward order
		// and then go through them in reverse direction and do full test
		// TODO This is very inefficient for all but the most optimal case where the last thing in the
		// subtree happens to be a perfect match
		ArrayList<TreeNode> options = new ArrayList<TreeNode>();
		InterestPreScreener ips = new InterestPreScreener(interest);
		getSubtreeNodes(node, options, 0, ips);
		return rightCheck(options, interest, node, getter);
	}
	
	private ContentObject rightCheck(ArrayList<TreeNode> options, Interest interest, TreeNode node, ContentGetter getter) {
		for (int i = options.size()-1; i >= 0 ; i--) {
			TreeNode candidate = options.get(i);
			if (null != candidate.oneContent || null != candidate.content) {
				List<ContentRef> content = null;
				synchronized(candidate) {
					if (null != candidate.oneContent) {
						content = new ArrayList<ContentRef>();
						content.add(candidate.oneContent);
					} else {
						assert(null != node.content);
						content = new ArrayList<ContentRef>(candidate.content);
					}
				}
				for (ContentRef ref : content) {
					ContentObject cand = getter.get(ref);
					if (cand!=null && interest.matches(cand)) {
						return cand;
					}
				}
			}
		}
		return null;
	}
	
	/**
	 * Return all names with a prefix matching the name within the interest for name enumeration.
	 * 
	 * The current implementation of name enumeration in the repository uses the object save dates
	 * to determine whether there is new information to send back.  If the name matches the incoming
	 * interest, the child names are collected from the tree and sent back in a NameEnumerationResponse
	 * object.  If there is not any new information under the prefix, an interest flag is set.  This will
	 * trigger a NameEnumerationResponse when a new child is added to the prefix.  Interests attempting
	 * to enumerate under a prefix that does not exist on the repo are dropped.
	 * 
	 * @param interest the interest to base the enumeration on using the rules of name enumeration
	 * @return the name enumeration response containing the list of matching names
	 */
	public final NameEnumerationResponse getNamesWithPrefix(Interest interest, ContentName responseName) {
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		//first chop off NE marker
		ContentName prefix = interest.name().cut(CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION);

		if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
			Log.fine("checking for content names under: {0}", prefix);
		}
		
		TreeNode parent = lookupNode(prefix, prefix.count());
		if (parent!=null) {
			//first add the NE marker
		    ContentName potentialCollectionName = new ContentName(prefix, CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION);
		    //now add the response id
		    potentialCollectionName = new ContentName(potentialCollectionName, responseName.components());
		    //now finish up with version and segment
		    potentialCollectionName = VersioningProfile.addVersion(potentialCollectionName, new CCNTime(parent.timestamp));
		    potentialCollectionName = SegmentationProfile.segmentName(potentialCollectionName, SegmentationProfile.baseSegment());
			//check if we should respond...
			if (interest.matches(potentialCollectionName, null)) {
				if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
					Log.info("the new version is a match with the interest!  we should respond: interest = {0} potentialCollectionName = {1}", interest, potentialCollectionName);
				}
			} else {
				if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
					Log.finer("the new version doesn't match, no response needed: interest = {0} would be collection name: {1}", interest, potentialCollectionName);
				}
				parent.interestFlag = true;
				return null;
			}

			//the parent has children we need to return
			if (parent.oneChild!=null) {
				names.add(new ContentName(ContentName.ROOT, parent.oneChild.component));
			} else {
				if (parent.children!=null) {
					for (TreeNode ch:parent.children.keySet())
						names.add(new ContentName(ContentName.ROOT, ch.component));
				}
			}
			
			if (names.size()>0) {
				if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
					Log.finer("sending back {0} names in the enumeration response for prefix {1}", names.size(), prefix);
				}
			}
			parent.interestFlag = false;
			
			return new NameEnumerationResponse(new ContentName(prefix, CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION), names, new CCNTime(parent.timestamp));
		}
		return null;
	}
	
	/**
	 * Retrieve the data from the store that best matches the given interest
	 * 
	 * @param interest the interest to match
	 * @param getter used to read a possible match for final matching
	 * @return the matching ContentObject or null if none
	 */
	public final ContentObject get(Interest interest, ContentGetter getter) {
		Integer addl = interest.maxSuffixComponents();
		int ncc = interest.name().count();
		if (null != addl && addl.intValue() == 0) {
			// Query is for exact match to full name with digest, no additional components
			List<ContentRef> found = lookup(interest.name());
			if (found!=null) {
				for (ContentRef ref : found) {
					ContentObject cand = getter.get(ref);
					if (null != cand) {
						if (interest.matches(cand)) {
							return cand;
						}
					}
				}
			}
		} else {
			TreeNode prefixRoot = lookupNode(interest.name(), ncc);
			if (prefixRoot == null) {
				return null;
			}
			
			if (null != interest.childSelector() && ((interest.childSelector() & (Interest.CHILD_SELECTOR_RIGHT))
					== (Interest.CHILD_SELECTOR_RIGHT))) {
				// Traverse to find latest match
				return rightSearch(interest, (null == addl) ? -1 : addl + ncc, 
						prefixRoot, new ContentName(ncc, interest.name().components()), 
						ncc, getter);
			}
			else{
				int min = null == interest.minSuffixComponents() ? -1 : interest.minSuffixComponents();
				return leftSearch(interest, min + ncc, (null == addl) ? -1 : addl + ncc,
						prefixRoot, new ContentName(ncc, interest.name().components()), 
						ncc, false, getter);
			}
		}
		return null;
	}
}
