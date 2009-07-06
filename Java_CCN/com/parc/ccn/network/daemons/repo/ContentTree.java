package com.parc.ccn.network.daemons.repo;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNNameEnumerator;
import com.parc.ccn.library.profiles.VersioningProfile;

public class ContentTree {

	/**
	 * ContentFileRef
	 * @author jthornto
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
		
		public boolean compEquals(byte[] other) {
			return DataUtils.compare(other, this.component) == 0;
		}
		public TreeNode getChild(byte[] component) {
			if (null != oneChild) {
				if (oneChild.compEquals(component)) {
					return oneChild;
				}
			} else if (null != children) {
				for (TreeNode child : children) {
					if (child.compEquals(component)) {
						return child;
					}
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
	 * Insert entry for the given ContentObject .
	 * @param content
	 */
	public void insert(ContentObject content, ContentFileRef ref, long ts) {
		final ContentName name = new ContentName(content.name(), content.contentDigest());
		Library.logger().fine("inserting content: "+name.toString());
		System.out.println("inserting content: "+name.toString());
		TreeNode node = root; // starting point
		assert(null != root);
		
		for (byte[] component : name.components()) {
			synchronized(node) {
				//Library.logger().finest("getting node for component: "+new String(component));
				TreeNode child = node.getChild(component);
				if (null == child) {
					Library.logger().finest("child was null: adding here");
					// add it
					child = new TreeNode();
					child.component = component;
					System.out.println("adding component: "+ContentName.componentPrintNative(child.component));
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
				//Library.logger().finest("child was not null: moving down the tree");
				node = child;
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
		System.out.println("added "+ name.toString()+" at file ref: "+ref.id+" "+ref.offset);
	}

	protected TreeNode lookupNode(ContentName name, int count) {
		TreeNode node = root; // starting point
		
		assert(null != root);
		if (count < 1) {
			return node;
		}
		
		for (byte[] component : name.components()) {
			synchronized(node) {
				//System.out.println("checking component: "+new String(component)+" count = "+count);
				TreeNode child = node.getChild(component);
				if (null == child) {
					// Mismatch, no child for the given component so nothing under this name
					return null;
				}
				node = child;
				count--;
				//System.out.println("count is: "+count);
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
	protected final ContentObject leftSearch(Interest interest, int matchlen, TreeNode node, ContentName nodeName, int depth, ContentGetter getter) {
		
		// Content at exactly this node is not a match (if any)
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
			byte[] interestComp = interest.name().component(depth);  // ??
			System.out.println("interestComp: "+interest.name()+" depth="+depth+" "+interest.name().stringComponent(depth));
			for (TreeNode child : children) {
				System.out.println("child: "+new String(child.component));
				int comp = DataUtils.compare(child.component, interestComp);
				//if (null == interestComp || DataUtils.compare(child.component, interestComp) >= 0) {
				ContentObject result = null;
				if (comp == 0){
					// This child subtree is possible match
					result = leftSearch(interest, matchlen, child, 
							new ContentName(nodeName, child.component), depth+1, getter);

				} else  if (comp > 0)  { 
					result = rightSearch(interest, matchlen, 
							child, new ContentName(matchlen, interest.name().components()), 
							depth, getter);
				}
				if (null != result) {
					return result;
				}
			}
		}
		// No match found
		return null;
	}
	
	protected void getSubtreeNodes(TreeNode node, List<TreeNode> result) {
		result.add(node);
		synchronized(node) {
			if (null != node.oneChild) {
				getSubtreeNodes(node.oneChild, result);
			} else if (null != node.children) {
				for (TreeNode child : node.children) {
					getSubtreeNodes(child, result);
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
		getSubtreeNodes(node, options);
		for (int i = options.size()-1; i >= 0 ; i--) {
			TreeNode candidate = options.get(i);
			System.out.println("treenode candidate: "+candidate.toString());
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
					System.out.println("ref: "+ref.id+" "+ref.offset);
					ContentObject cand = getter.get(ref);
					if (cand!=null && interest.matches(cand)) {
						return cand;
					}
				}
			}
		}
		return null;
	}
	
	public final ArrayList<ContentName> getNamesWithPrefix(Interest interest, ContentGetter getter) {
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		//first chop off NE marker
		ContentName prefix = interest.name().cut(CCNNameEnumerator.NEMARKER);
		System.out.println("looking up matches for interest prefix: "+interest.name().toString());
		
		//does the interest have a timestamp?
		Timestamp interestTS = null;
		Timestamp nodeTS = null;
		
		try{
			interestTS = VersioningProfile.getVersionAsTimestamp(interest.name());
		}
		catch(Exception e){
			interestTS = null;
		}
		
		
		TreeNode parent = lookupNode(prefix, prefix.count());
		if(parent!=null){
			System.out.println("here is the parent match: "+parent.toString());
			
			//we should check the timestamp
			nodeTS = new Timestamp(parent.timestamp);
			if(interestTS==null || nodeTS.after(interestTS)){
				//we have something new to report
			}
			else
				return null;
			
			//the parent has children we need to return
			ContentName c = new ContentName();
			if(parent.oneChild!=null){
				names.add(new ContentName(c, parent.oneChild.component));
				System.out.println("added name: "+ContentName.componentPrintURI(parent.oneChild.component));
			}
			else{
				if(parent.children!=null){
					for(TreeNode ch:parent.children)
						names.add(new ContentName(c, ch.component));
				}
			}
			return names;
			
		}
		
		return null;
	}
	
	
	public final ContentObject get(Interest interest, ContentGetter getter) {
		Integer addl = interest.additionalNameComponents();
		int ncc = (null != interest.nameComponentCount()) ? interest.nameComponentCount() : interest.name().count();
		if (null != addl && addl.intValue() == 0) {
			// Query is for exact match to full name with digest, no additional components
			System.out.println("ContentTree.get: "+interest.name());
			List<ContentFileRef> found = lookup(interest.name());
			if(found!=null){
				System.out.println("found is not null");
				for (ContentFileRef ref : found) {
					ContentObject cand = getter.get(ref);
					if (null != cand) {
						System.out.println("candidate: "+cand.name().toString()+" interest: "+interest.name().toString());
						if (interest.matches(cand)) {
							return cand;
						}
					}
					else
						System.out.println("candidate was null");
				}
			}
			else
				System.out.println("found was null");
		} else {
			//TreeNode prefixRoot = lookupNode(interest.name(), interest.nameComponentCount());
			TreeNode prefixRoot = lookupNode(interest.name(), ncc);
			if(prefixRoot == null){
				Library.logger().info("the prefix root is null...  returning null");
				return null;
			}
			
			if ((null==interest.orderPreference()) || ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME))
					== (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME))) {
				// Traverse to find latest match
				if(interest.orderPreference()!=null)
					System.out.println("going to do rightSearch for latest.  Interest: "+interest.name().toString());
				else
					System.out.println("going to do rightSearch.  Interest: "+interest.name().toString());
				
				return rightSearch(interest, (null == addl) ? -1 : addl + ncc, 
						prefixRoot, new ContentName(ncc, interest.name().components()), 
						ncc, getter);
			}
			else{
				System.out.println("going to do leftSearch for earliest.  Interest: "+interest.toString());
				return leftSearch(interest, (null == addl) ? -1 : addl + ncc,
						prefixRoot, new ContentName(ncc, interest.name().components()), 
						ncc, getter);
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
