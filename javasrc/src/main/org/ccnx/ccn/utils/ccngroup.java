/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2010, 2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.SortedSet;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.profiles.security.access.group.MembershipListObject;
import org.ccnx.ccn.protocol.ContentName;

public class ccngroup {

	private static long TIMEOUT = 1000;
	private static ContentName userStorage = new ContentName(UserConfiguration.defaultNamespace(), "Users");
	private static ContentName groupStorage = new ContentName(UserConfiguration.defaultNamespace(), "Groups");

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// silence logging
		Log.setDefaultLevel(Level.WARNING);
		String extraUsage = "";

		if ((args == null) || (args.length == 0)) {
			usage(extraUsage);
		}

		int pos = 0;
		if (args[0].startsWith("[")) {
			extraUsage = args[0];
			pos++;
		}
		if (args[pos].equals("-h")) {
			usage(extraUsage);
		}
		if (args[pos].equals("-as")) {
			if (args.length < pos+2) usage(extraUsage);
			pos++;
			setUser(args[pos]);
			pos++;
		}

		if (args[pos].equals("-list")) {
			listGroups();
			System.exit(0);
		}
		else if (args[pos].equals("-listmembers")) {
			if (args.length < pos + 2) usage(extraUsage);
			pos++;
			String groupName = args[pos];
			listMembers(groupName);
			System.exit(0);
		}
		else if (args[pos].equals("-delete")) {
			if (args.length < pos + 2) usage(extraUsage);
			pos ++;
			String groupName = args[pos];
			deleteGroup(groupName);
			System.exit(0);
		}
		else if (args[pos].equals("-create") || args[pos].equals("-add") || args[pos].equals("-remove")) {
			if (args.length < pos + 2) usage(extraUsage);
			String command = args[pos];
			pos++;
			String groupName = args[pos];
			pos++;
			ArrayList<Link> groupMembers = new ArrayList<Link>();
			for (int i=pos; i<args.length; i++) {
				try {
					Link lk = new Link(ContentName.fromNative(args[i]));
					groupMembers.add(lk);
				}
				catch (Exception e) {
					e.printStackTrace();
					System.exit(1);
				}
			}
			if (command.equals("-create")) createGroup(groupName, groupMembers);
			else if (command.equals("-add")) addMember(groupName, groupMembers);
			else if (command.equals("-remove")) removeMember(groupName, groupMembers);
			System.exit(0);
		}
		else {
			usage(extraUsage);
		}

	}

	public static void usage(String extraUsage) {
		System.out.println("usage:");
		System.out.println("ccngroup " + extraUsage + "[-as pathToKeystore] -list");
		System.out.println("ccngroup " + extraUsage + "[-as pathToKeystore] -listmembers groupFriendlyName");
		System.out.println("ccngroup " + extraUsage + "[-as pathToKeystore] [-create | -add | -remove] groupFriendlyName (groupMember)*");
		System.out.println("ccngroup " + extraUsage + "[-as pathToKeystore] -delete groupFriendlyName");
		System.exit(1);
	}

	public static void setUser(String pathToKeystore) {
		File userDirectory = new File(pathToKeystore);
		String userConfigDir = userDirectory.getAbsolutePath();
		System.out.println("Loading keystore from: " + userConfigDir);
		UserConfiguration.setUserConfigurationDirectory(userConfigDir);
		// Assume here that the name of the file is the userName
		String userName = userDirectory.getName();
		if (userName != null) {
			System.out.println("User: " + userName);
			UserConfiguration.setUserName(userName);
		}
	}

	public static void listGroups() {
		try {
			EnumeratedNameList userDirectory = new EnumeratedNameList(groupStorage, CCNHandle.open());
			userDirectory.waitForChildren(TIMEOUT);
			Thread.sleep(TIMEOUT);

			SortedSet<ContentName> availableChildren = userDirectory.getChildren();
			if ((null == availableChildren) || (availableChildren.size() == 0)) {
				System.out.println("No group found in: " + groupStorage);
			}
			else {
				System.out.println(availableChildren.size() + " group(s) found in: " + groupStorage);
				for (ContentName child : availableChildren) {
					ContentName fullName = groupStorage.append(child);
					System.out.println(fullName);
				}
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void listMembers(String groupName) {
		try{
			GroupAccessControlManager acm = new GroupAccessControlManager(null, groupStorage, userStorage, CCNHandle.open());
			GroupManager gm = acm.groupManager();
			Thread.sleep(TIMEOUT);
			Group g = gm.getGroup(groupName, SystemConfiguration.getDefaultTimeout());
			MembershipListObject ml = g.membershipList();
			LinkedList<Link> lll = ml.contents();
			System.out.println("The group " + groupName + " has " + lll.size() + " members:");
			for (Link l: lll) {
				System.out.println(l.targetName());
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}


	public static void createGroup(String groupName, ArrayList<Link> membersToAdd) {
		try {
			ContentName root = ContentName.fromNative("/");
			GroupAccessControlManager acm = new GroupAccessControlManager(root, groupStorage, userStorage, CCNHandle.open());
			GroupManager gm = acm.groupManager();
			gm.createGroup(groupName, membersToAdd, SystemConfiguration.getDefaultTimeout());
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.out.println("Group " + groupName + " created, with members: ");
		for (Link lk: membersToAdd) {
			System.out.println(lk.targetName());
		}
	}

	public static void deleteGroup(String groupName) {
		try {
			GroupAccessControlManager acm = new GroupAccessControlManager(null, groupStorage, userStorage, CCNHandle.open());
			GroupManager gm = acm.groupManager();
			gm.deleteGroup(groupName);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.out.println("Deleted group " + groupName);
	}

	public static void addMember(String groupName, ArrayList<Link> membersToAdd) {
		try {
			GroupAccessControlManager acm = new GroupAccessControlManager(null, groupStorage, userStorage, CCNHandle.open());
			GroupManager gm = acm.groupManager();
			Thread.sleep(TIMEOUT);
			Group g = gm.getGroup(groupName, SystemConfiguration.getDefaultTimeout());
			g.modify(membersToAdd, null);
		}
		catch (AccessDeniedException aed) {
			System.out.println("You do not have the permission to edit the membership of Group " + groupName);
			System.exit(1);
		}
		catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.out.println("Added to group " + groupName + " the following members: ");
		for (Link lk: membersToAdd) {
			System.out.println(lk.targetName());
		}
	}

	public static void removeMember(String groupName, ArrayList<Link> membersToRemove) {
		try {
			GroupAccessControlManager acm = new GroupAccessControlManager(null, groupStorage, userStorage, CCNHandle.open());
			GroupManager gm = acm.groupManager();
			Thread.sleep(TIMEOUT);
			Group g = gm.getGroup(groupName, SystemConfiguration.getDefaultTimeout());
			g.modify(null, membersToRemove);
		}
		catch (AccessDeniedException aed) {
			System.out.println("You do not have the permission to edit the membership of Group " + groupName);
			System.exit(1);
		}
		catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.out.println("Removed from group " + groupName + " the following members: ");
		for (Link lk: membersToRemove) {
			System.out.println(lk.targetName());
		}
	}

}
