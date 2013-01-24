/*
 * A CCNx command line utility.
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


package org.ccnx.ccn.utils.explorer;

import java.util.ArrayList;

import javax.swing.tree.DefaultMutableTreeNode;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;


/**
 * Runnable class that allows the ContentExplorer to add children in the name tree.
 * 
 *
 */
public class AddChildren implements Runnable {

	private ContentExplorer app = null;
	private ArrayList<ContentName> names = null;
	private ContentName prefix = null;
	private CCNNameEnumerator ne = null;
	
	/**
	 * Constructor for the AddChildren class
	 * 
	 * @param ce The instance of the ContentExplorer wanting to add children
	 * @param n The ContentName ArrayList of the children to add
	 * @param p The ContentName for the prefix of the children
	 */
	public AddChildren(ContentExplorer ce, ArrayList<ContentName> n, ContentName p) {
		app = ce;
		names = n;
		prefix = p;
		ne = app.getNameEnumerator();
	}
	
	/**
	 * run() method for the thread adding children
	 */
	public void run() {

		Log.finer("addTreeNodes: prefix = " + prefix + " names: " + names.toString());
		
		DefaultMutableTreeNode parentNode = app.getTreeNode(prefix);

		if (parentNode == null) {
			Log.finer("PARENT NODE IS NULL!!!" + prefix.toString());
			Log.finer("can't add anything to a null parent...  cancel prefix and return");
			ne.cancelPrefix(prefix);
			return;
		}
		
		synchronized (parentNode) {

			int numChildren = parentNode.getChildCount();
			Log.finer("the parent has " + numChildren + " children: ");

			DefaultMutableTreeNode temp = null;
			for (int i = 0; i < numChildren; i++) {
				temp = (DefaultMutableTreeNode) parentNode.getChildAt(i);
				if (temp.getUserObject() instanceof IconData) {
					IconData id = (IconData) temp.getUserObject();
					ContentName childName = new ContentName(((Name) id.m_data).name);
					Log.finer(" " + childName);
				}
			}
			Log.finer("");

			// while we are getting things, wait for stuff to happen
			Log.finer("Getting Content Names");

			boolean addToParent = true;
			DefaultMutableTreeNode toRemove = null;
			for (ContentName cn : names) {
				addToParent = true;

				// check if a version marker
				//if (VersioningProfile.containsVersion(cn)) {
				if (VersioningProfile.hasTerminalVersion(cn)) {
					if (!ContentExplorer.showVersions && !ContentExplorer.debugMode)
						addToParent = false;

					// this name is a version, that means the parent is
					// something we can grab...
					// we should change the icon for the parent to be a file and
					// not a folder

					Name parentNameNode = app.getNameNode(parentNode);
					if (parentNameNode.isDirectory) {
						if (!ContentExplorer.showVersions && !ContentExplorer.debugMode)
							parentNameNode.setIsDirectory(false);
						((IconData) parentNode.getUserObject()).setIcon(ContentExplorer.ICON_DOCUMENT);
						app.m_model.nodeChanged(parentNode);
					}
					
					//we want to store the versions to display them later
					parentNameNode.addVersion(cn);

				}
				// check if a segment marker
				if (SegmentationProfile.isSegment(cn)) {
					if (!ContentExplorer.showVersions && !ContentExplorer.debugMode)
						addToParent = false;
					
					Name parentNameNode = app.getNameNode(parentNode);
					if (parentNameNode.isDirectory) {
						if (!ContentExplorer.showVersions && !ContentExplorer.debugMode)
							parentNameNode.setIsDirectory(false);
						((IconData) parentNode.getUserObject()).setIcon(ContentExplorer.ICON_DOCUMENT);
						app.m_model.nodeChanged(parentNode);
					}
				}

				if (addToParent && parentNode.getChildCount() > 0) {
					numChildren = parentNode.getChildCount();

					for (int i = 0; i < numChildren; i++) {
						temp = (DefaultMutableTreeNode) parentNode.getChildAt(i);
						// check if this name is already in there!
						if (temp.getUserObject() instanceof Boolean) {
							toRemove = temp;
						} else {
							if (temp.getUserObject() instanceof IconData) {
								IconData id = (IconData) temp.getUserObject();
								ContentName nodeName = new ContentName(((Name) id.m_data).name);

								// check if already there...
								if (cn.compareTo(nodeName) == 0) {
									addToParent = false;
								}
							}
						}
					}
					if (toRemove != null) {
						app.m_model.removeNodeFromParent(toRemove);
						toRemove = null;
					}
				}
				final DefaultMutableTreeNode node;
				if (addToParent) {
					// name wasn't there, go ahead and add to the parent
					if (cn.toString().toLowerCase().endsWith(".txt") || cn.toString().toLowerCase().endsWith(".text")) {
						node = new DefaultMutableTreeNode(new IconData(ContentExplorer.ICON_DOCUMENT, null, new Name(cn.component(0), prefix, false)));
					} else {
						node = new DefaultMutableTreeNode(new IconData(ContentExplorer.ICON_FOLDER, null, new Name(cn.component(0), prefix, true)));
					}

					app.m_model.insertNodeInto(node, parentNode, parentNode.getChildCount());
					Log.fine("inserted node...  parent now has " + parentNode.getChildCount());
				}
			}
			Log.finer("the parent node now has " + parentNode.getChildCount()+ " children: ");

			numChildren = parentNode.getChildCount();
			for (int i = 0; i < numChildren; i++) {
				temp = (DefaultMutableTreeNode) parentNode.getChildAt(i);
				if (temp.getUserObject() instanceof IconData) {
					IconData id = (IconData) temp.getUserObject();
					ContentName childName = new ContentName(((Name) id.m_data).name);
					Log.finer(" " + childName);
				}
			}
		}
		Log.finer("");
		Log.finer("Done Getting Content Names");
	}

}
