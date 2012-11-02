/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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
import java.util.TreeMap;
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

public class NodeBuilder {
	
	public abstract class NodeCommon<X> {
		
		public SyncTreeEntry createNodeCommon(Collection<X> objects, int depth, SliceComparator sc, SyncNodeCache cache) {
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
			sc.putHashEntry(ste);
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
	public SyncTreeEntry newLeafNode(TreeSet<ContentName> names, SliceComparator sc, SyncNodeCache cache) {
		return new NodeCommon<ContentName>() {
			
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
		}.createNodeCommon(names, 1, sc, cache);
	}
	
	/**
	 * Create a node of nodes from the input map. We use only as many nodes as will fit into the
	 * new node and remove those from the list.
	 * 
	 * @param nodes
	 * @return
	 */
	public SyncTreeEntry newNodeOfNodes(TreeMap<ContentName, SyncNodeElement> nodes, final SliceComparator sc, SyncNodeCache cache, int depth) {
		Collection<SyncNodeElement> values = nodes.values();
		SyncTreeEntry ste = new NodeCommon<SyncNodeElement>() {		
			public int extraSplit(SyncNodeElement n, SyncNodeElement tname, int total, int minLen, int prevMatch) {
				return 0;
			}

			public SyncNodeElement newElement(SyncNodeElement element) {
				return new SyncNodeElement(element.getData());
			}

			public SyncNodeComposite newNode(ArrayList<SyncNodeElement> refs, int depth) {
				SyncNodeElement first = findit(refs, sc, true);
				if (null == first) {
					Log.warning(Log.FAC_SYNC, "Can't get hash or node for {0} in newNode - shouldn't happen", 
							Component.printURI(refs.get(0).getData()));
					return null;
				}
				SyncNodeElement last = findit(refs, sc, false);
				if (null == last) {
					Log.warning(Log.FAC_SYNC, "Can't get hash or node for {0} in newNode - shouldn't happen", 
							Component.printURI(refs.get(refs.size() - 1).getData()));
					return null;
				}
				return new SyncNodeComposite(refs, first, last, refs.size(), depth);
			}
		}.createNodeCommon(values, depth, sc, cache);
		
		// Remove the values that NodeCommon removed from map
		// A little clunky but can't come up with any better way at the moment...
		for (ContentName name : nodes.keySet()) {
			if (!values.contains(nodes.get(name)))
				nodes.remove(name);
		}
		return ste;
	}
	
	public SyncTreeEntry createHeadRecursive(TreeMap<ContentName, SyncNodeElement> nodeElements, final SliceComparator sc, SyncNodeCache cache, int depth) {
		SyncTreeEntry ste = null;
		TreeMap<ContentName, SyncNodeElement> nextElements = new TreeMap<ContentName, SyncNodeElement>();
		do {
			ste = newNodeOfNodes(nodeElements, sc, cache, depth);
			nextElements.put(ste.getNode().getMinName().getName(), new SyncNodeElement(ste.getHash()));
		} while (nodeElements.size() > 0);
		if (nextElements.size() > 1)
			return createHeadRecursive(nextElements, sc, cache, depth+1);
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST) && ste.getNode().getRefs().get(0).getType() == SyncNodeType.HASH) {
			Log.finest(Log.FAC_SYNC, "Creating new compound node - with first element {0} and last element {1}",
							Component.printURI(ste.getNode().getRefs().get(0).getData()), Component.printURI(ste.getNode().getRefs().get(ste.getNode().getRefs().size() - 1).getData()));
		}
		return ste;
	}
	
	private SyncNodeElement findit(ArrayList<SyncNodeElement> refs, SliceComparator sc, boolean start) {
		int position = start ? 0 : refs.size() - 1;
		SyncNodeElement sne = refs.get(position);
		while (sne.getType() != SyncNodeType.LEAF) {
			SyncTreeEntry ste = sc.getHash(sne.getData());
			if (null == ste || null == ste.getNode()) {
				return null;
			}
			if (!start)
				position = ste.getNode().getRefs().size() - 1;
			sne = ste.getNode().getElement(position);
		}
		return sne;
	}
	
}
