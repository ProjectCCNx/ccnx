/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.TreeSet;
import java.util.logging.Level;

import org.ccnx.ccn.CCNSync;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeElement;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeType;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;

/**
 * Code to build Sync nodes
 */
public class NodeBuilder {
	
	public abstract class NodeCommon<X> {
		
		public SyncTreeEntry createNodeCommon(Collection<X> objects, int depth, SyncHashCache shc, SyncNodeCache cache) {
			ArrayList<SyncNodeComposite.SyncNodeElement> refs = new ArrayList<SyncNodeComposite.SyncNodeElement>();
			int total = 0;
			int limit = CCNSync.NODE_SPLIT_TRIGGER - CCNSync.NODE_SPLIT_TRIGGER/8;
			int minLen = CCNSync.NODE_SPLIT_TRIGGER/2;
			int maxLen = 0;
			int prevTotal = 0;
			int split = 0;
			X tobj = null;
			for (X ne : objects) {
				XMLEncodable nextE = (XMLEncodable)ne;
				if (null != tobj) {
					byte[] lengthTest;
					try {
						lengthTest = nextE.encode();
						int nameLen = lengthTest.length + 8;
						if (nameLen > maxLen) maxLen = nameLen;
						total += (nameLen + ((maxLen - nameLen) * 2));
					} catch (ContentEncodingException e) {} // Shouldn't happen because we built the data
					prevTotal = extraSplit(ne, tobj, total, minLen, prevTotal);
					if (prevTotal < 0)
						break;
					if (total > limit)
						break;
				}
				tobj = ne;
				split = split + 1;
			}
			int i = 0;
			ArrayList<X> removes = new ArrayList<X>();
			for (X thisOne : objects) {
				SyncNodeElement sne = newElement(thisOne);
				refs.add(sne);
				removes.add(thisOne);
				if (++i >= split)
					break;
			}
			objects.removeAll(removes);
			SyncNodeComposite snc = newNode(refs, depth);
			if (null == snc) {
				Log.warning(Log.FAC_SYNC, "Couldn't build node - shouldn't happen");
				return null;
			}
			SyncTreeEntry ste = new SyncTreeEntry(snc.getHash(), cache);
			shc.putHashEntry(ste);
			ste.setLocal(true);
			ste.setNode(snc);
			return ste;
		}
		
		public abstract int extraSplit(X ne, X te, int total, int minLen, int prevTotal);
		public abstract SyncNodeElement newElement(X xe);
		public abstract SyncNodeComposite newNode(ArrayList<SyncNodeComposite.SyncNodeElement> refs, int depth);
	}
	
	/**
	 * Create a new leaf node from the names entered. We use only as many names as will fit into
	 * the node, and remove those names from the input list so that after this has completed, the
	 * list contains the remaining names which are not yet in a node.
	 * 
	 * @param names - the set of names in canonical order
	 * @return
	 */
	public SyncTreeEntry newLeafNode(TreeSet<ContentName> names, SyncHashCache shc, SyncNodeCache cache) {
		SyncTreeEntry ste = new NodeCommon<ContentName>() {
			
			public int extraSplit(ContentName nextName, ContentName tname, int total, int minLen, int prevMatch) {
				int match = tname.matchLength(nextName);
				if (total > minLen) {
					if (match < prevMatch || match >  + 1) {
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINE)) {
							Log.fine(Log.FAC_SYNC, "Node split due to level change - nbytes {0}, match {1}, prev {2}", 
									total, match, prevMatch);
						}
						return -1;
					}
					byte[] lc = tname.lastComponent();
					if (lc.length > 8) {
						int c = (int)(lc[lc.length - 7] & 255);
						if (c < CCNSync.HASH_SPLIT_TRIGGER) {
							if (Log.isLoggable(Log.FAC_SYNC, Level.FINE)) {
								Log.fine(Log.FAC_SYNC, "Node split due to hash split - nbytes {0}, val {1}", total, c);
							}
							return -1;
						}
					}
				}
				return match;
			}

			public SyncNodeElement newElement(ContentName name) {
				return new SyncNodeComposite.SyncNodeElement(name);
			}

			public SyncNodeComposite newNode(ArrayList<SyncNodeElement> refs,int depth) {
				return new SyncNodeComposite(refs, refs.get(0), refs.get(refs.size() - 1), refs.size(), depth);
			}
		}.createNodeCommon(names, 1, shc, cache);
		
		SyncNodeComposite theNode = ste.getNode();
		if (null != theNode) {
			theNode.setLeafCount(theNode.getRefs().size());
		}
		return ste;
	}
	
	/**
	 * Create a node of nodes from the input map. We use only as many nodes as will fit into the
	 * new node and remove those from the list.
	 * 
	 * @param nodes
	 * @return
	 */
	public SyncTreeEntry newNodeOfNodes(Collection<SyncNodeElement> nodes, final SyncHashCache shc, SyncNodeCache cache, int depth) {
		SyncTreeEntry ste = new NodeCommon<SyncNodeElement>() {		
			public int extraSplit(SyncNodeElement n, SyncNodeElement tname, int total, int minLen, int prevMatch) {
				return 0;
			}

			public SyncNodeElement newElement(SyncNodeElement element) {
				return new SyncNodeElement(element.getData());
			}

			public SyncNodeComposite newNode(ArrayList<SyncNodeElement> refs, int depth) {
				SyncNodeElement first = findit(refs, shc, true);
				if (null == first) {
					Log.warning(Log.FAC_SYNC, "Can't get hash or node for {0} in newNode - shouldn't happen", 
							Component.printURI(refs.get(0).getData()));
					return null;
				}
				SyncNodeElement last = findit(refs, shc, false);
				if (null == last) {
					Log.warning(Log.FAC_SYNC, "Can't get hash or node for {0} in newNode - shouldn't happen", 
							Component.printURI(refs.get(refs.size() - 1).getData()));
					return null;
				}
				return new SyncNodeComposite(refs, first, last, refs.size(), depth);
			}
		}.createNodeCommon(nodes, depth, shc, cache);
		
		SyncNodeComposite theNode = ste.getNode();
		int leafCount = 0;
		if (null != theNode) {
			for (SyncNodeElement sne : theNode.getRefs()) {
				if (sne.getType() == SyncNodeType.HASH) {
					SyncTreeEntry tste = shc.getHash(sne.getData());
					if (null != tste && null != tste.getNode())
						leafCount += tste.getNode().getLeafCount();
				}
			}
			theNode.setLeafCount(leafCount);
		}
		return ste;
	}
	
	/**
	 * Create a sync tree from the input collection of elements
	 * @param nodeElements the elements
	 * @param shc hash cache
	 * @param cache node cache
	 * @param depth starting depth (but when called nonrecursively I think it will always be 2).
	 * @return
	 */
	public SyncTreeEntry createHeadRecursive(Collection<SyncNodeElement> nodeElements, final SyncHashCache shc, SyncNodeCache cache, int depth) {
		SyncTreeEntry ste = null;
		ArrayList<SyncNodeElement> nextElements = new ArrayList<SyncNodeElement>();
		do {
			ste = newNodeOfNodes(nodeElements, shc, cache, depth);
			nextElements.add(new SyncNodeElement(ste.getHash()));
		} while (nodeElements.size() > 0);
		if (nextElements.size() > 1)
			return createHeadRecursive(nextElements, shc, cache, depth+1);
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST) && ste.getNode().getRefs().get(0).getType() == SyncNodeType.HASH) {
			Log.finest(Log.FAC_SYNC, "Creating new compound node - with first element {0} and last element {1}",
							Component.printURI(ste.getNode().getRefs().get(0).getData()), Component.printURI(ste.getNode().getRefs().get(ste.getNode().getRefs().size() - 1).getData()));
		}
		return ste;
	}
	
	/**
	 * Used to find the "first" or "last" LEAF element within the refs
	 * @param refs
	 * @param shc
	 * @param start true if looking for the first element - otherwise look for last
	 * @return
	 */
	private SyncNodeElement findit(ArrayList<SyncNodeElement> refs, SyncHashCache shc, boolean start) {
		int position = start ? 0 : refs.size() - 1;
		SyncNodeElement sne = refs.get(position);
		while (sne.getType() != SyncNodeType.LEAF) {
			SyncTreeEntry ste = shc.getHash(sne.getData());
			if (null == ste || null == ste.getNode()) {
				return null;
			}
			if (!start)
				position = ste.getNode().getRefs().size() - 1;
			sne = ste.getNode().getElement(position);
		}
		return sne;
	}
	
	/**
	 * Create a new composite node based on the input set of content names in
	 * canonical order.
	 * 
	 * @param names
	 * @return entry for the new node or null if none
	 */
	public SyncTreeEntry newNode(TreeSet<ContentName> names, SyncHashCache shc, SyncNodeCache cache) {
		ArrayList<SyncNodeElement> snes = new ArrayList<SyncNodeElement>();
		SyncTreeEntry ourEntry = null;
		while (names.size() > 0) {
			ourEntry = newLeafNode(names, shc, cache);
			SyncNodeElement sne = new SyncNodeElement(ourEntry.getHash());
			if (names.size() > 0 || snes.size() > 0) {
				snes.add(sne);
			}
		}
		if (snes.size() > 0) {
			ourEntry = createHeadRecursive(snes, shc, cache, 2);
		}
		return ourEntry;
	}
	
	/**
	 * Get the first or last leaf element of an arbitrary node given a cache. If we can't trace all the
	 * way back to the leaf, return null.
	 * @param snc the beginning node
	 * @param cache
	 * @param first if true looking for first
	 * @return
	 */
	public static SyncNodeElement getFirstOrLast(SyncNodeComposite snc, SyncNodeCache cache, boolean first) {
		int pos = first ? 0 : snc.getRefs().size() - 1;
		SyncNodeElement sne = snc.getElement(pos);
		if (sne.getType() == SyncNodeType.LEAF)
			return sne;
		snc = cache.getNode(sne.getData());
		if (null == snc)
			return null;
		return getFirstOrLast(snc, cache, first);
	}
}
