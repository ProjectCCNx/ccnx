package org.ccnx.ccn.utils.explorer;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.SortedSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.profiles.security.access.group.MembershipList;
import org.ccnx.ccn.protocol.ContentName;

public class PrincipalEnumerator {

	private static long TIMEOUT = 1000;
	
	GroupManager gm;
	ContentName userStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Groups");
	
	public PrincipalEnumerator(GroupManager gm) {
		this.gm = gm;
	}
	
	public ArrayList<ContentName> enumerateUsers() {
		return listPrincipals(userStorage);
	}
	
	public ArrayList<ContentName> enumerateGroups() {
		return listPrincipals(groupStorage);
	}
	
	public ArrayList<ContentName> enumerateGroupMembers(String groupFriendlyName) {
		ArrayList<ContentName> members = new ArrayList<ContentName>();
		if (groupFriendlyName != null) {
			try{
				Group g = gm.getGroup(groupFriendlyName);
				MembershipList ml = g.membershipList();
				LinkedList<Link> lll = ml.contents();
				for (Link l: lll) {
					members.add(l.targetName());
				}
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
		return members;
	}
	
	private ArrayList<ContentName> listPrincipals(ContentName path) {
		ArrayList<ContentName> principalList = new ArrayList<ContentName>();
		
		try {
			EnumeratedNameList userDirectory = new EnumeratedNameList(path, CCNHandle.open());
			userDirectory.waitForChildren(TIMEOUT);
			Thread.sleep(TIMEOUT);
			
			SortedSet<ContentName> availableChildren = userDirectory.getChildren();
			if ((null == availableChildren) || (availableChildren.size() == 0)) {
				System.out.println("No available keystore data in directory " + path + ", giving up.");
			}
			else {
				for (ContentName child : availableChildren) {
					ContentName fullName = new ContentName(path, child.components());
					principalList.add(fullName);
				}
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		
		return principalList;
	}
	
}
