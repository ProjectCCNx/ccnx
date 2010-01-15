package org.ccnx.ccn.utils;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.SortedSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.profiles.security.access.group.MembershipList;
import org.ccnx.ccn.protocol.ContentName;

public class ccngroup {

	private static long TIMEOUT = 1000;
	private static ContentName userStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Users");
	private static ContentName groupStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Groups");
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if ((args == null) || (args.length == 0)) {
			usage();
		}
		else if (args[0].equals("-list")) {
			listGroups();
			System.exit(0);
		}
		else if (args[0].equals("-listmembers")) {
			if (args.length < 2) {
				usage();
				System.exit(1);
			}
			String groupName = args[1];
			listMembers(groupName);
			System.exit(0);
		}
		else if (args[0].equals("-delete")) {
			if (args.length < 2) {
				usage();
				System.exit(1);
			}
			String groupName = args[1];
			deleteGroup(groupName);
			System.exit(0);
		}
		else if ( args[0].equals("-create")
				|| args[0].equals("-add")
				|| args[0].equals("-remove")) 
		{
			if (args.length < 2) {
				usage();
				System.exit(1);
			}
			String groupName = args[1];
			ArrayList<Link> groupMembers = new ArrayList<Link>();
			for (int i=2; i<args.length; i++) {
				try {
					Link lk = new Link(ContentName.fromNative(args[i]));
					groupMembers.add(lk);
				} 
				catch (Exception e) {
					e.printStackTrace();
					usage();
					System.exit(1);
				}
			}
			if (args[0].equals("-create")) createGroup(groupName, groupMembers);
			else if (args[0].equals("-add")) addMember(groupName, groupMembers);
			else if (args[0].equals("-remove")) removeMember(groupName, groupMembers);
			System.exit(0);
		}
		else {
			usage();
		}

	}

	public static void usage() {
		System.out.println("usage:");
		System.out.println("ccnGroup -list");
		System.out.println("ccnGroup -listmembers groupFriendlyName");
		System.out.println("ccnGroup -create groupFriendlyName (groupMember)*");
		System.out.println("ccnGroup -delete groupFriendlyName");
		System.out.println("ccnGroup -add groupFriendlyName (groupMemberToAdd)*");
		System.out.println("ccnGroup -remove groupFriendlyName (groupMemberToRemove)*");		
		System.exit(1);
	}
	
	public static void listGroups() {
		try {
			EnumeratedNameList userDirectory = new EnumeratedNameList(groupStorage, CCNHandle.open());
			userDirectory.waitForChildren(TIMEOUT);
			Thread.sleep(TIMEOUT);
			
			SortedSet<ContentName> availableChildren = userDirectory.getChildren();
			if ((null == availableChildren) || (availableChildren.size() == 0)) {
				System.out.println("No available keystore data in directory " + groupStorage + ", giving up.");
			}
			else {
				for (ContentName child : availableChildren) {
					ContentName fullName = new ContentName(groupStorage, child.components());
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
			Group g = gm.getGroup(groupName);
			MembershipList ml = g.membershipList();
			LinkedList<Link> lll = ml.contents();
			System.out.println("Membership of group " + groupName + ": ");
			for (Link l: lll) {
				System.out.println(l.targetName());
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			usage();
			System.exit(1);
		}
	}
	
	
	public static void createGroup(String groupName, ArrayList<Link> membersToAdd) {
		try {
			GroupAccessControlManager acm = new GroupAccessControlManager(null, groupStorage, userStorage, CCNHandle.open());
			GroupManager gm = acm.groupManager();
			gm.createGroup(groupName, membersToAdd);
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
			Group g = gm.getGroup(groupName);
			g.modify(membersToAdd, null);
		} 
		catch (AccessDeniedException aed) {
			System.out.println("You do not have the permission to edit the membership of Group " + groupName);
			System.exit(1);
		}
		catch (Exception e) {
			e.printStackTrace();
			usage();
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
			Group g = gm.getGroup(groupName);
			g.modify(null, membersToRemove);
		}
		catch (AccessDeniedException aed) {
			System.out.println("You do not have the permission to edit the membership of Group " + groupName);
			System.exit(1);
		}
		catch (Exception e) {
			e.printStackTrace();
			usage();
			System.exit(1);
		}
		System.out.println("Removed from group " + groupName + " the following members: ");
		for (Link lk: membersToRemove) {
			System.out.println(lk.targetName());
		}
	}
	
}
