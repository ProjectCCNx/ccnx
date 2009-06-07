package com.parc.ccn.network.daemons.repo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.DataUtils;

public class ContentTree {

	/**
	 * ContentFileRef
	 * @author jthornto
	 *
	 */
	public class ContentFileRef {
		int id;
		int offset;
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
	public void insert(ContentObject content, ContentFileRef ref) {
		final ContentName name = new ContentName(content.name(), content.contentDigest());
		TreeNode node = root; // starting point
		assert(null != root);
		
		for (byte[] component : name.components()) {
			synchronized(node) {
				TreeNode child = node.getChild(component);
				if (null == child) {
					// add it
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
				}
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
	public final List<ContentFileRef> lookup(ContentName name) {
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
		// Check content if this node could be content match and there is any content at this node
		if ( (nodeName.count() >= 0) && (matchlen == -1 || matchlen == depth)) {
			if (null != node.oneContent || null != node.content) {
				// Since the name INCLUDES digest component and the Interest.matches() convention for name
				// matching is that the name DOES NOT include digest component (conforming to the convention 
				// for ContentObject.name() that the digest is not present) we must REMOVE the content 
				// digest first or this test will not always be correct
				ContentName digestFreeName = new ContentName(nodeName.count()-1, nodeName.components());
				if (interest.matches(digestFreeName, null)) {
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
		// Content at exactly this node is not a match (if any)
		// Now search children if applicable and if any
		if (matchlen != -1 && matchlen <= depth) {
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
			byte[] interestComp = interest.name().component(depth+1);
			for (TreeNode child : children) {
				if (null == interestComp || DataUtils.compare(child.component, interestComp) >= 0) {
					// This child subtree is possible match
					ContentObject result = leftSearch(interest, matchlen, child, 
							new ContentName(nodeName, child.component), depth+1, getter);
					if (null != result) {
						return result;
					}

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
					if (interest.matches(cand)) {
						return cand;
					}
				}
			}
		}
		return null;
	}
	
	public final ContentObject lookup(Interest interest, ContentGetter getter) {
		Integer addl = interest.additionalNameComponents();
		if (null != addl && addl.intValue() == 0) {
			// Query is for exact match to full name with digest, no additional components
			List<ContentFileRef> found = lookup(interest.name());
			for (ContentFileRef ref : found) {
				ContentObject cand = getter.get(ref);
				if (null != cand) {
					if (interest.matches(cand)) {
						return cand;
					}
				}
			}
		} else {
			TreeNode prefixRoot = lookupNode(interest.name(), interest.nameComponentCount());
			// Now we need to iterate over content at or below this node to find best match
			if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME))
					== (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME)) {
				// Traverse to find earliest match
				leftSearch(interest, (null == addl) ? -1 : addl + interest.nameComponentCount(), 
						prefixRoot, new ContentName(interest.nameComponentCount(), interest.name().components()), 
						interest.nameComponentCount(), getter);
			} else {
				// Traverse to find latest match
				rightSearch(interest, (null == addl) ? -1 : addl + interest.nameComponentCount(), 
						prefixRoot, new ContentName(interest.nameComponentCount(), interest.name().components()), 
						interest.nameComponentCount(), getter);
			}
		}
		return null;
	}

}
