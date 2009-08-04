package com.parc.ccn.network.daemons.repo;

import java.io.PrintStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNNameEnumerator;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.network.daemons.repo.Repository.NameEnumerationResponse;

public class ContentTree {

	/**
	 * ContentFileRef
	 * @author jthornto, rbraynar, rasmusse
	 *
	 */
	public class ContentFileRef {
		int id;
		long offset;
	}
	
	public interface ContentGetter {
		public ContentObject get(ContentFileRef ref);
	}
	
	/**
	 * TreeNode is the data structure representing one
	 * node of a tree which may have children and/or content.
	 * Every child has a distinct name (it's component) but 
	 * there may be multiple content objects ending with the 
	 * same component (i.e. having same content digest at end
	 * but presumably different publisher etc. that is not 
	 * visible in this tree)
	 * @author jthornto
	 *
	 */
	public class TreeNode {
		byte[] component; // name of this node in the tree, null for root only
		// oneChild is special case when there is only 
		// a single child (to save obj overhead).
		// either oneChild or children should be null
		TreeNode oneChild;
		SortedSet<TreeNode> children;
		// oneContent is special case when there is only 
		// a single content object here (to save obj overhead).
		// either oneContent or content should be null
		ContentFileRef oneContent;
		List<ContentFileRef> content;
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
				TreeNode testNode = new TreeNode();
				testNode.component = component;
				SortedSet<TreeNode> tailSet = children.tailSet(testNode);
				if (tailSet.size() > 0) {
					if (tailSet.first().compEquals(component))
						return tailSet.first();
				}
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
				for(TreeNode t: children){
					//append each child to string
					s+=" "+ContentName.componentPrintURI(t.component);
					//s+=new String(t.component)+" ";
				}
			}
			else
				s+=" oneChild and children were null";

			return s;
		}
	}
	public class TreeNodeComparator implements Comparator<TreeNode> {
		public int compare(TreeNode o1, TreeNode o2) {
			return DataUtils.compare(o1.component, o2.component);
		}
	}

	protected TreeNode root;
	
	public ContentTree() {
		root = new TreeNode();
		root.component = null; // Only the root has a null value
	}
	
	/**
	 * Insert entry for the given ContentObject.
	 * @param content
	 * @param ref
	 * @param ts
	 * @param getter
	 * @return - true if content is not exact duplicate of existing content.
	 */
	public boolean insert(ContentObject content, ContentFileRef ref, long ts, ContentGetter getter, NameEnumerationResponse ner) {
		final ContentName name = new ContentName(content.name(), content.contentDigest());
		Library.logger().fine("inserting content: "+name.toString());
		TreeNode node = root; // starting point
		assert(null != root);
		boolean added = false;
		
		for (byte[] component : name.components()) {
			synchronized(node) {
				//Library.logger().finest("getting node for component: "+new String(component));
				TreeNode child = node.getChild(component);
				if (null == child) {
					Library.logger().finest("child was null: adding here");
					// add it
					added = true;
					child = new TreeNode();
					child.component = component;
					if (null == node.oneChild && null == node.children) {
						// This is first and only child of current node
						node.oneChild = child;
					} else if (null == node.oneChild) {
						// Multiple children already, just add this one to current node
						node.children.add(child);
					} else {
						// Second child in current node, need to switch to list
						node.children = new TreeSet<TreeNode>(new TreeNodeComparator());
						node.children.add(node.oneChild);
						node.children.add(child);
						node.oneChild = null;
					}
					node.timestamp = ts;
				}
				
				if(node.interestFlag && (ner==null || ner.prefix==null)){
					//we have added something to this node and someone was interested
					//we need to get the child names and the prefix to send back
					Library.logger().info("we added at least one child, need to send a name enumeration response");
					ContentName prefix = name.cut(component);

					prefix = new ContentName(prefix, CCNNameEnumerator.NEMARKER);
					prefix = VersioningProfile.addVersion(prefix, new Timestamp(node.timestamp));
					Library.logger().info("prefix for NEResponse: "+prefix);

					ArrayList<ContentName> names = new ArrayList<ContentName>();
					//the parent has children we need to return
					ContentName c = new ContentName();
					if(node.oneChild!=null){
						names.add(new ContentName(c, node.oneChild.component));
					}
					else{
						if(node.children!=null){
							for(TreeNode ch:node.children)
								names.add(new ContentName(c, ch.component));
						}
					}
					ner.setPrefix(prefix);
					ner.setNameList(names);
					Library.logger().info("resetting interestFlag to false");
					node.interestFlag = false;
					
				}
				
				
				//Library.logger().finest("child was not null: moving down the tree");
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
				for (ContentFileRef oldRef : node.content) {
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
			node.content = new ArrayList<ContentFileRef>();
			node.content.add(node.oneContent);
			node.content.add(ref);
			node.oneContent = null;
		}
		Library.logger().fine("Inserted: " + content.name());
		return true;
	}

	protected TreeNode lookupNode(ContentName name, int count) {
		TreeNode node = root; // starting point
		
		assert(null != root);
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
	 * @param name
	 * @param count
	 * @return
	 */
	protected final List<ContentFileRef> lookup(ContentName name) {
		TreeNode node = lookupNode(name, name.count());
		if (null != node) {
			if (null != node.oneContent) {
				ArrayList<ContentFileRef> result = new ArrayList<ContentFileRef>();
				result.add(node.oneContent);
				return result;
			} else {
				return node.content;
			}
		} else {
			return null;	
		}
	}
	
	public void dumpNamesTree(PrintStream output, int maxNodeLen) {		
		assert(null != root);
		assert(null != output);
		
		output.println("Dumping tree of names of indexed content at " + new Date().toString());
		if (maxNodeLen > 0) {
			output.println("Node names truncated to max " + maxNodeLen + " characters");
		}
		dumpRecurse(output, root, "", maxNodeLen);
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
			for (TreeNode child : node.children) {
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
	 * 
	 * @param interest the interest to match
	 * @param matchlen total number of components required in final answer or -1 if not specified
	 * @param node the node rooting a subtree to search
	 * @param nodeName the full name of this node from the root up to and its component
	 * @param depth the length of name of node including its component (number of components)
	 * @param getter a handler to pull actual ContentObjects for final match testing.
	 * @return
	 */
	protected final ContentObject leftSearch(Interest interest, int matchlen, TreeNode node, ContentName nodeName, int depth, boolean anyOK, ContentGetter getter) {
		if ( (nodeName.count() >= 0) && (matchlen == -1 || matchlen == depth)) {
			if (null != node.oneContent || null != node.content) {
				// Since the name INCLUDES digest component and the Interest.matches() convention for name
				// matching is that the name DOES NOT include digest component (conforming to the convention 
				// for ContentObject.name() that the digest is not present) we must REMOVE the content 
				// digest first or this test will not always be correct
				ContentName digestFreeName = new ContentName(nodeName.count()-1, nodeName.components());
				Interest publisherFreeInterest = interest.clone();
				publisherFreeInterest.publisherID(null);
				if (publisherFreeInterest.matches(digestFreeName, null)) {
					List<ContentFileRef> content = null;
					synchronized(node) {
						if (null != node.oneContent) {
							content = new ArrayList<ContentFileRef>();
							content.add(node.oneContent);
						} else {
							assert(null != node.content);
							content = new ArrayList<ContentFileRef>(node.content);
						}
					}
					for (ContentFileRef ref : content) {
						ContentObject cand = getter.get(ref);
						if (interest.matches(cand)) {
							return cand;
						}
					}
				}
			}
		}
		// Now search children if applicable and if any
		if (matchlen != -1 && matchlen <= depth || (node.children==null && node.oneChild==null)) {
			// Any child would make the total name longer than requested so no point in 
			// checking children
			return null;
		}
		SortedSet<TreeNode> children = null;
		synchronized(node) {
			if (null != node.oneChild) {
				children = new TreeSet<TreeNode>(); // Don't bother with comparator, will only hold one element
				children.add(node.oneChild);
			} else {
				children = new TreeSet<TreeNode>(new TreeNodeComparator());
				children.addAll(node.children);
			}
		}
		if (null != children) {
			byte[] interestComp = interest.name().component(depth);
			for (TreeNode child : children) {
				int comp = DataUtils.compare(child.component, interestComp);
				//if (null == interestComp || DataUtils.compare(child.component, interestComp) >= 0) {
				if (anyOK || comp >= 0) {
					ContentObject result = null;
					result = leftSearch(interest, matchlen, child, 
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
	
	protected void getSubtreeNodes(TreeNode node, List<TreeNode> result, Integer components) {
		result.add(node);
		if (components != null) {
			components--;
			if (components == 0)
				return;
		}
		synchronized(node) {
			if (null != node.oneChild) {
				getSubtreeNodes(node.oneChild, result, components);
			} else if (null != node.children) {
				for (TreeNode child : node.children) {
					getSubtreeNodes(child, result, components);
				}
			}
		}
	}
	
	protected final ContentObject rightSearch(Interest interest, int matchlen, TreeNode node, ContentName nodeName, int depth, ContentGetter getter) {
		// A shortcut compared to leftSearch() for moment, just accumulate all options in forward order
		// and then go through them in reverse direction and do full test
		// ToDo This is very inefficient for all but the most optimal case where the last thing in the
		// subtree happens to be a perfect match
		ArrayList<TreeNode> options = new ArrayList<TreeNode>();
		Integer totalComponents = null;
		if (interest.additionalNameComponents() != null)
			totalComponents = interest.name().count() + interest.additionalNameComponents();
		getSubtreeNodes(node, options, totalComponents);
		for (int i = options.size()-1; i >= 0 ; i--) {
			TreeNode candidate = options.get(i);
			if (null != candidate.oneContent || null != candidate.content) {
				List<ContentFileRef> content = null;
				synchronized(candidate) {
					if (null != candidate.oneContent) {
						content = new ArrayList<ContentFileRef>();
						content.add(candidate.oneContent);
					} else {
						assert(null != node.content);
						content = new ArrayList<ContentFileRef>(candidate.content);
					}
				}
				for (ContentFileRef ref : content) {
					ContentObject cand = getter.get(ref);
					if (cand!=null && interest.matches(cand)) {
						return cand;
					}
				}
			}
		}
		return null;
	}
	
	public final NameEnumerationResponse getNamesWithPrefix(Interest interest, ContentGetter getter) {
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		//first chop off NE marker
		ContentName prefix = interest.name().cut(CCNNameEnumerator.NEMARKER);

		//prefix = VersioningProfile.versionRoot(prefix);
		boolean versionedInterest = false;
		//get the index of the name enumeration marker
		int markerIndex = prefix.count();
		if (interest.name().count() > markerIndex) {
			//we have something longer than just the name enumeration marker
			if (VersioningProfile.findLastVersionComponent(interest.name()) > markerIndex)
				versionedInterest = true;
		}
		
		//does the interest have a timestamp?
		Timestamp interestTS = null;
		Timestamp nodeTS = null;

		if (versionedInterest) {
			// NOTE: should be sure that interest.name() has a version that we're interested in, otherwise
			// this might return an arbitrary version farther up the name...
		
			try {
				byte[] versionComponent = interest.name().component(markerIndex+1);
				interestTS = VersioningProfile.getVersionComponentAsTimestamp(versionComponent);
				//interestTS = VersioningProfile.getLastVersionAsTimestamp(interest.name());
				Library.logger().fine("interestTS: "+interestTS+" "+interestTS.getTime());
			} catch(Exception e) {
				interestTS = null;
			}
		} else {
			Library.logger().finest("no interest in timestamp after the name enumeration marker");
		}
		
		Library.logger().fine("checking for content names under: "+prefix);
		
		TreeNode parent = lookupNode(prefix, prefix.count());
		if (parent!=null) {
			parent.interestFlag = true;
			
			//we should check the timestamp
			try {
				nodeTS = VersioningProfile.getLastVersionAsTimestamp(VersioningProfile.addVersion(new ContentName(), new Timestamp(parent.timestamp)));
			} catch (VersionMissingException e) {
				//should never happen since we are putting the version in in the same line...
				Library.logger().info("missing version in conversion of index node timestamp to version timestamp for comparison to interest timestamp");
				interestTS=null;
			}
			//nodeTS = new Timestamp(parent.timestamp);
			if (interestTS==null) {
				//no version marker...  should respond if we have info
			} else if (nodeTS.after(interestTS) && !nodeTS.equals(interestTS)) {
				//we have something new to report
				//put this time in the last name spot if there are children
			} else {
				Library.logger().info("Nothing new, but the interest flag is set in case new content is added");
				return null;
			}
			
			//the parent has children we need to return
			ContentName c = new ContentName();
			if (parent.oneChild!=null) {
				names.add(new ContentName(c, parent.oneChild.component));
			} else {
				if (parent.children!=null) {
					for (TreeNode ch:parent.children)
						names.add(new ContentName(c, ch.component));
				}
			}
			//add timestamp in last name spot to send back (will be removed)
			if (names!=null && names.size()>0)
				Library.logger().finer("sending back "+names.size()+" names in the enumeration response");
			parent.interestFlag = false;

			return new NameEnumerationResponse(VersioningProfile.addVersion(interest.name(), nodeTS), names);
			
		}
		return null;
	}
	
	
	public final ContentObject get(Interest interest, ContentGetter getter) {
		Integer addl = interest.additionalNameComponents();
		int ncc = (null != interest.nameComponentCount()) ? interest.nameComponentCount() : interest.name().count();
		if (null != addl && addl.intValue() == 0) {
			// Query is for exact match to full name with digest, no additional components
			List<ContentFileRef> found = lookup(interest.name());
			if (found!=null) {
				for (ContentFileRef ref : found) {
					ContentObject cand = getter.get(ref);
					if (null != cand) {
						if (interest.matches(cand)) {
							return cand;
						}
					}
				}
			}
		} else {
			//TreeNode prefixRoot = lookupNode(interest.name(), interest.nameComponentCount());
			TreeNode prefixRoot = lookupNode(interest.name(), ncc);
			if (prefixRoot == null) {
				//Library.logger().info("For: " + interest.name() + " the prefix root is null...  returning null");
				return null;
			}
			
			if (null != interest.orderPreference() && (interest.orderPreference() & (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME))
					== (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME)) {
				// Traverse to find latest match
				return rightSearch(interest, (null == addl) ? -1 : addl + ncc, 
						prefixRoot, new ContentName(ncc, interest.name().components()), 
						ncc, getter);
			}
			else{
				return leftSearch(interest, (null == addl) ? -1 : addl + ncc,
						prefixRoot, new ContentName(ncc, interest.name().components()), 
						ncc, null == interest.orderPreference() || (interest.orderPreference() & (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME))
						!= (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME), getter);
			}
			
			
			/** original version	
			
			
			// Now we need to iterate over content at or below this node to find best match
			//if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME))
				//	== (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME)) {
			if ((null!=interest.orderPreference()) && ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME))
					== (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME))) {
				// Traverse to find earliest match
				//leftSearch(interest, (null == addl) ? -1 : addl + interest.nameComponentCount(),
					//prefixRoot, new ContentName(interest.nameComponentCount(), interest.name().components()), 
					//interest.nameComponentCount(), getter);
				System.out.println("going to do leftSearch for earliest.  Interest: "+interest.toString());
				return leftSearch(interest, (null == addl) ? -1 : addl + ncc,
						prefixRoot, new ContentName(ncc, interest.name().components()), 
						ncc, getter);
			} else {
				// Traverse to find latest match
				//rightSearch(interest, (null == addl) ? -1 : addl + interest.nameComponentCount(), 
				//		prefixRoot, new ContentName(interest.nameComponentCount(), interest.name().components()), 
				//		interest.nameComponentCount(), getter);
				System.out.println("going to do rightSearch for latest.  Interest: "+interest.name().toString());
				
				return rightSearch(interest, (null == addl) ? -1 : addl + ncc, 
						prefixRoot, new ContentName(ncc, interest.name().components()), 
						ncc, getter);
			}
			
			**/
			
		}
		return null;
	}

}
