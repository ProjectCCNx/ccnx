/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import static org.ccnx.ccn.profiles.CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.Component;
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
		boolean neSent = false;		// NE response sent since last insert
		
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
				s = Component.printURI(component);				
			}
			if(oneChild!=null){
				//there is only one child
				s+= " oneChild: "+Component.printURI(component);
			}
			else if(children!=null){
				s+= " children: ";
				int i = 0;
				for(TreeNode c: children.keySet()){
					//append each child to string
					s+=" "+Component.printURI(c.component);
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
	protected static class InterestPreScreener {
		protected int _minComponents = 0;
		protected int _maxComponents = 32767;
		protected Exclude _exclude;
		protected int _excludeLevel;
		
		protected InterestPreScreener(Interest interest, int excludeLevel, int startLevel) {
			if (null != interest.minSuffixComponents())
				_minComponents = interest.minSuffixComponents() + startLevel;
			if (null != interest.maxSuffixComponents())
				_maxComponents = interest.maxSuffixComponents() + startLevel;
			_exclude = interest.exclude();
			_excludeLevel = excludeLevel;
		}
		
		/**
		 * Run the prescreen
		 * @param level the level within the hierarchy in which this prescreen was called. Used to
		 *        decide when to run the exclude test.
		 * @return -1 => reject all entries below this
		 * 			0 => reject this entry but keep searching
		 * 			1 => keep this entry
		 */
		protected int preScreen(TreeNode node, int level) {
			if (level > _maxComponents)
				return -1;
			if (level == _excludeLevel && null != _exclude) {
				if (_exclude.match(node.component))
					return -1;
			}
			return (level < _minComponents) ? 0 : 1;
		}
	}
	
	/**
	 * Implements the generic pieces of both left and right searches
	 */
	protected abstract class Search {
		protected Interest _interest;
		protected InterestPreScreener _ips;
		protected SortedMap<TreeNode, TreeNode> _children = null;
		
		protected Search(Interest interest, InterestPreScreener ips) {
			_interest = interest;
			_ips = ips;
		}
		
		/**
		 * Do the actual search. Use abstract method to decide how to traverse the tree
		 * 
		 * @param node the node rooting a subtree to search
		 * @param nodeName the full name of this node from the root up to and its component
		 * @param getter a handler to pull actual ContentObjects for final match testing
		 * @param depth the length of name of node including its component (number of components)
		 * @param leftSearch true if we should search down the left side of the tree at this level
		 * @return ContentObject matching the interest or null if not found
		 */
		protected ContentObject search(TreeNode node, ContentName nodeName, ContentGetter getter, 
						int depth, boolean leftSearch) {
			if( Log.isLoggable(Log.FAC_REPO, Level.FINE) )
				Log.fine(Log.FAC_REPO, "Searching for: {0}", nodeName);
			int res = _ips.preScreen(node, depth);
			if (res < 0)
				return null;
			if (res > 0) {
				if (null != node.oneContent || null != node.content) {
					ContentObject result = getContent(_interest, node, nodeName, getter);
					if (null != result)
						return result;
				}
			}
		
			synchronized(node) {
				if (null != node.oneChild) {
					_children = new TreeMap<TreeNode, TreeNode>(); // Don't bother with comparator, will only hold one element
					_children.put(node.oneChild, node.oneChild);
				} else {
					_children = node.children;
				}
			}
			if (null != _children) {
				byte[] interestComp = _interest.name().component(depth);
				Iterator<TreeNode>it = initIterator(leftSearch, interestComp);
				while(it.hasNext()) {
					TreeNode child = it.next();
					int comp = DataUtils.compare(child.component, interestComp);
					if (leftSearch || comp >= 0) {
						ContentObject result = null;
						result = search(child, new ContentName(nodeName, child.component), getter, depth + 1, true);
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
		 * Return an iterator through children at this level.
		 * 
		 * @param anyOK leftSearch only - if false must go "left by one" at this level
		 * @param interestComp component to start search with
		 * @return the iterator
		 */
		protected abstract Iterator<TreeNode> initIterator(boolean leftSearch, byte[] interestComp);
		
		/**
		 * 
		 */
		protected abstract boolean continueSearch(boolean leftSearch, TreeNode child, byte[] component);
	}
	
	/**
	 * Search for data matching an interest that specifies either the leftmost (canonically smallest) match or
	 * doesn't specify a specific way to match data within several pieces of matching data.
	 */
	protected class LeftSearch extends Search {

		protected LeftSearch(Interest interest, InterestPreScreener ips) {
			super(interest, ips);
		}

		@Override
		protected Iterator<TreeNode> initIterator(boolean leftSearch, byte[] interestComp) {
			TreeNode testNode = new TreeNode();
			testNode.component = interestComp;
			SortedMap<TreeNode, TreeNode> map = leftSearch || null == interestComp ? _children : _children.tailMap(testNode);
			return map.keySet().iterator();
		}

		@Override
		protected boolean continueSearch(boolean leftSearch, TreeNode child,
				byte[] component) {
			int comp = DataUtils.compare(child.component, component);
			return (leftSearch || comp >= 0);
		}
	}
	
	/**
	 * Search for data matching an interest in which the rightmost (canonically largest) data among several
	 * matching pieces should be returned.
	 */
	protected class RightSearch extends Search {

		protected RightSearch(Interest interest, InterestPreScreener ips) {
			super(interest, ips);
		}

		@Override
		protected Iterator<TreeNode> initIterator(boolean leftSearch, byte[] interestComp) {
			if (leftSearch)
				return _children.keySet().iterator();
			return new RightIterator(_children);
		}

		@Override
		protected boolean continueSearch(boolean leftSearch, TreeNode child,
				byte[] component) {
			return true;
		}
	}
	
	/**
	 * Create an iterator that goes backwards through the candidates for right search
	 */
	protected static class RightIterator implements Iterator<TreeNode> {
		protected SortedMap<TreeNode, TreeNode> _map;
		
		protected RightIterator(SortedMap<TreeNode, TreeNode> map) {
			_map = map;
		}

		public boolean hasNext() {
			return _map.size() > 0;
		}

		public TreeNode next() {
			TreeNode node = _map.lastKey();
			_map = _map.subMap(_map.firstKey(), _map.lastKey());
			return node;
		}

		public void remove() {}
		
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
		if (Log.isLoggable(Log.FAC_REPO, Level.FINE)) {
			Log.fine(Log.FAC_REPO, "inserting content: {0}", name);
		}
		TreeNode node = _root; // starting point
		assert(null != _root);
		boolean added = false;
		
		for (byte[] component : name) {
			synchronized(node) {
				//Library.finest("getting node for component: "+new String(component));
				TreeNode child = node.getChild(component);
				if (null == child) {
					if (Log.isLoggable(Log.FAC_REPO, Level.FINEST)) {
						Log.finest(Log.FAC_REPO, "child was null: adding here");
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
					if (node.neSent && (node.timestamp == ts)) {
						if (Log.isLoggable(Log.FAC_REPO, Level.WARNING)) {
							Log.warning(Log.FAC_REPO, "WARNING - info inserted at {0} since last NE without timestamp update - could cause NE miss", 
									name);
						}
					}
					node.neSent = false;
					node.timestamp = ts;
					
					if (node.interestFlag && (ner != null && ner.getPrefix()==null)){
						//we have added something to this node and someone was interested
						//we need to get the child names and the prefix to send back
						if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
							Log.info(Log.FAC_REPO, "we added at least one child, need to send a name enumeration response");
						}
						ContentName prefix = name.cut(component);
	
						prefix = new ContentName(prefix, COMMAND_MARKER_BASIC_ENUMERATION);
						if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
							Log.info(Log.FAC_REPO, "prefix for FastNEResponse: {0}", prefix);
							Log.info(Log.FAC_REPO, "response name will be: {0}",
									new ContentName(prefix, COMMAND_MARKER_BASIC_ENUMERATION, new CCNTime(node.timestamp)));
						}
	
						ArrayList<ContentName> names = new ArrayList<ContentName>();
						// the parent has children we need to return
						if (node.oneChild != null) {
							names.add(new ContentName(node.oneChild.component));
						} else {
							if (node.children != null) {
								for (TreeNode ch : node.children.keySet())
									names.add(new ContentName(ch.component));
							}
						}
						ner.setPrefix(prefix);
						ner.setNameList(names);
						ner.setTimestamp(new CCNTime(node.timestamp));
						if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
							Log.info(Log.FAC_REPO, "resetting interestFlag to false");
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
		if (Log.isLoggable(Log.FAC_REPO, Level.FINE)) {
			Log.fine(Log.FAC_REPO, "Inserted: {0}", content.name());
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
		
		for (byte[] component : name) {
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
			myname = Component.printURI(node.component);
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
	 * Return content at this level if there is matching content
	 * 
	 * @param interest - interest to match against
	 * @param node	   - the node
	 * @param nodeName - name of node as a ContentName
	 * @param getter   - getter to get actual data for final match and return if matches
	 * @return matching ContentObject if matches, null otherwise
	 */
	private ContentObject getContent(Interest interest, TreeNode node, ContentName nodeName, ContentGetter getter) {
		// Since the name INCLUDES digest component and the Interest.matches() convention for name
		// matching is that the name DOES NOT include digest component (conforming to the convention 
		// for ContentObject.name() that the digest is not present) we must REMOVE the content 
		// digest first or this test will not always be correct
		ContentName digestFreeName = nodeName.parent();
		Interest publisherFreeInterest = interest.clone();
		publisherFreeInterest.publisherID(null);

		boolean initialMatch = publisherFreeInterest.matches(digestFreeName, null); 

		if (initialMatch) {
			synchronized(node) {
				if (null != node.oneContent) {
					ContentObject cand = getter.get(node.oneContent);
					if (interest.matches(cand)) {
						return cand;
					}
				} else {
					assert(null != node.content);
					for (ContentRef ref : node.content) {
						ContentObject cand = getter.get(ref);
						if (interest.matches(cand)) {
							return cand;
						}
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
		ContentName prefix = interest.name().cut(COMMAND_MARKER_BASIC_ENUMERATION.getBytes());

		if (Log.isLoggable(Log.FAC_REPO, Level.FINE)) {
			Log.fine(Log.FAC_REPO, "checking for content names under: {0}", prefix);
		}
		
		TreeNode parent = lookupNode(prefix, prefix.count());
		if (parent!=null) {
			//first add the NE marker
			CCNTime timestamp = new CCNTime(parent.timestamp);		// I think we want to use the earliest possible timestamp here - if there are duplicates
																	// NE can straighten it out - worse to miss somethingf
		    ContentName potentialCollectionName = new ContentName(
									    		prefix,
									    		COMMAND_MARKER_BASIC_ENUMERATION,
									    		responseName,
									    		timestamp,
									    		SegmentationProfile.getSegmentNumberNameComponent(SegmentationProfile.baseSegment())
		    								);
			//check if we should respond...
			if (interest.matches(potentialCollectionName, null)) {
				if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
					Log.info(Log.FAC_REPO, "the new version is a match with the interest!  we should respond: interest = {0} potentialCollectionName = {1}", interest, potentialCollectionName);
				}
			} else {
				if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
					Log.finer(Log.FAC_REPO, "the new version doesn't match, no response needed: interest = {0} would be collection name: {1}", interest, potentialCollectionName);
				}

				//I am not supposed to respond...  is that because of the version or because I am specifically excluded?
				 if (responseName.count() > 0 && interest.exclude().match(responseName.component(0))) {
					 Log.finer(Log.FAC_REPO, "my repo is explicitly excluded!  not setting interestFlag to true");
					 //do not set interest flag!  I wasn't supposed to respond
				 } else {
					 if (interest.exclude().match(timestamp.toBinaryTime())) {
						 Log.finer(Log.FAC_REPO, "my version is just excluded, setting interestFlag to true");
						 parent.interestFlag = true;
					 }
				 }
				return null;
			}

			//the parent has children we need to return
			synchronized (parent) {		// Make sure especially that nobody changes from oneChild to children behind our back
				if (parent.oneChild!=null) {
					names.add(new ContentName(parent.oneChild.component));
				} else {
					if (parent.children!=null) {
						for (TreeNode ch:parent.children.keySet())
							names.add(new ContentName(ch.component));
					}
				}
				
				if (names.size()>0) {
					if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
						Log.finer(Log.FAC_REPO, "sending back {0} names in the enumeration response for prefix {1}", names.size(), prefix);
					}
				}
				parent.interestFlag = false;
				parent.neSent = true;
			}
			
			return new NameEnumerationResponse(
					new ContentName(prefix, COMMAND_MARKER_BASIC_ENUMERATION), names, timestamp);
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
			// Traverse to find latest match
			TreeNode prefixRoot = lookupNode(interest.name(), ncc);
			if (prefixRoot == null) {
				return null;
			}
			
			InterestPreScreener ips = new InterestPreScreener(interest, ncc + 1, ncc);
			if (null != interest.childSelector() && ((interest.childSelector() & (Interest.CHILD_SELECTOR_RIGHT))
					== (Interest.CHILD_SELECTOR_RIGHT))) {
				return new RightSearch(interest, ips).search(prefixRoot, interest.name().cut(ncc), 
						getter, ncc, false);
			} else {
				return new LeftSearch(interest, ips).search(prefixRoot, interest.name().cut(ncc), 
						getter, ncc, false);
			}
		}
		return null;
	}
	
	/**
	 * Determine if there is data with exactly the given name.
	 * @param name to match, including explicit digest as final component
	 * @return true if there is data with the given complete name, false otherwise
	 */
	public boolean matchContent(ContentName name) {
		// Query is for exact match to full name with digest, no additional components
		return (lookup(name) != null);
	}
}
