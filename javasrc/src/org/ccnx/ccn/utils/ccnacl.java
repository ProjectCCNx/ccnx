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
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.namespace.ParameterizedName;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLObject;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.protocol.ContentName;

public class ccnacl {

	private static ContentName userStorage = new ContentName(UserConfiguration.defaultNamespace(), "Users");
	private static ContentName groupStorage = new ContentName(UserConfiguration.defaultNamespace(), "Groups");

	/**
	 * @param args
	 */
	public static void main(String[] args) {

		String extraUsage = "";

		// silence logging
		Log.setDefaultLevel(Level.WARNING);

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

		if (args[pos].equals("-show")) {
			if (args.length < pos + 2) {
				usage(extraUsage);
			}
			pos++;
			String nodeName = args[pos];
			showACL(nodeName);
		}
		else if (args[pos].equals("-edit")) {
			if (args.length < pos + 4) {
				usage(extraUsage);
			}
			String nodeName = args[pos + 1];
			String principalName = args[pos + 2];
			String role = args[pos + 3];
			if (! (role.equals("none") || role.equals(ACL.LABEL_READER) || role.equals(ACL.LABEL_WRITER) || role.equals(ACL.LABEL_MANAGER))) {
				usage(extraUsage);
			}
			editACL(nodeName, principalName, role);
		}
		else if (args[pos].equals("-init")) {
			if (args.length < pos + 5) {
				usage(extraUsage);
			}
			String domain = args[pos + 1];
			String userNamespace = args[pos + 2];
			String groupNamespace = args[pos + 3];
			String principalName = args[pos + 4];
			initACL(domain, userNamespace, groupNamespace, principalName);
		} else
			usage(extraUsage);
	}

	public static void usage(String extraUsage) {
		System.out.println("usage:");
		System.out.println("ccnacl " + extraUsage + "[-as pathToKeystore] -show nodeName");
		System.out.println("ccnacl " + extraUsage + "[-as pathToKeystore] -edit nodeName principalName [none|r|rw|rw+]");
		System.out.println("ccnacl " + extraUsage + "[-as pathToKeystore] -init domain userNamespace groupNamespace principalName");
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

	public static void showACL(String nodeName) {
		try{
			ContentName baseNode = ContentName.fromNative("/");
			GroupAccessControlManager acm = new GroupAccessControlManager(baseNode, groupStorage, userStorage, CCNHandle.open());
			ContentName node = ContentName.fromNative(nodeName);
			ACL acl = acm.getEffectiveACLObject(node).acl();
			System.out.println("ACL for node: " + nodeName);
			for (int j=0; j<acl.size(); j++) {
				Link lk = acl.get(j);
				System.out.println(lk.targetName() + " : " + lk.targetLabel());
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.exit(0);
	}

	public static void editACL(String nodeName, String principalName, String role) {
		try{
			ContentName baseNode = ContentName.fromNative("/");
			GroupAccessControlManager acm = new GroupAccessControlManager(baseNode, groupStorage, userStorage, CCNHandle.open());
			ContentName node = ContentName.fromNative(nodeName);

			ACLObject initialACLObject = acm.getEffectiveACLObject(node);
			ACL initialACL = initialACLObject.acl();
			if (! initialACLObject.getBaseName().equals(GroupAccessControlProfile.aclName(node))) {
				// There is no actual ACL at this node.
				// So we copy the effective ACL to this node before updating it.
				acm.setACL(node, initialACL);
			}

			// initial role
			ContentName principal = ContentName.fromNative(principalName);
			Link plk = new Link(principal);
			String initialRole = null;
			for (int j=0; j<initialACL.size(); j++) {
				Link lk = initialACL.get(j);
				if (principal.compareTo(lk.targetName()) == 0) {
					initialRole = lk.targetLabel();
				}
			}

			// update
			ArrayList<ACLOperation> ACLUpdates = new ArrayList<ACLOperation>();
			if (initialRole == null) {
				if (role.equals(ACL.LABEL_READER)) ACLUpdates.add(ACLOperation.addReaderOperation(plk));
				else if (role.equals(ACL.LABEL_WRITER)) ACLUpdates.add(ACLOperation.addWriterOperation(plk));
				else if (role.equals(ACL.LABEL_MANAGER)) ACLUpdates.add(ACLOperation.addManagerOperation(plk));
			}
			else if (initialRole.equals(ACL.LABEL_READER)) {
				if (role.equals("none")) ACLUpdates.add(ACLOperation.removeReaderOperation(plk));
				else if (role.equals(ACL.LABEL_WRITER)) ACLUpdates.add(ACLOperation.addWriterOperation(plk));
				else if (role.equals(ACL.LABEL_MANAGER)) ACLUpdates.add(ACLOperation.addManagerOperation(plk));
			}
			else if (initialRole.equals(ACL.LABEL_WRITER)) {
				if (role.equals("none")) ACLUpdates.add(ACLOperation.removeWriterOperation(plk));
				else if (role.equals(ACL.LABEL_READER)) {
					ACLUpdates.add(ACLOperation.removeWriterOperation(plk));
					ACLUpdates.add(ACLOperation.addReaderOperation(plk));
				}
				else if (role.equals(ACL.LABEL_MANAGER)) ACLUpdates.add(ACLOperation.addManagerOperation(plk));
			}
			else if (initialRole.equals(ACL.LABEL_MANAGER)) {
				if (role.equals("none")) ACLUpdates.add(ACLOperation.removeManagerOperation(plk));
				else if (role.equals(ACL.LABEL_READER)) {
					ACLUpdates.add(ACLOperation.removeManagerOperation(plk));
					ACLUpdates.add(ACLOperation.addReaderOperation(plk));
				}
				else if (role.equals(ACL.LABEL_WRITER)) {
					ACLUpdates.add(ACLOperation.removeManagerOperation(plk));
					ACLUpdates.add(ACLOperation.addWriterOperation(plk));
				}
			}

			acm.updateACL(node, ACLUpdates);

			System.out.println("ACL for node: " + nodeName + " updated to assign role " + role + " to principal " + principalName);
		}
		catch (AccessDeniedException ade) {
			System.out.println("You do not have the permission to edit the acl at node: " + nodeName);
			System.exit(1);
		}
		catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.exit(0);
	}

	// creates initial ACL at nodeName for principalName with permission rw+
	public static void initACL(String domain, String userNamespace, String groupNamespace, String principalName) {

		try{
			ContentName domainPrefix = ContentName.fromNative(domain);
			ContentName userNamespaceCN = ContentName.fromNative(userNamespace);
			ContentName groupNamespaceCN = ContentName.fromNative(groupNamespace);

			// Create the ACL for nodeName with principalName as a manager
			ArrayList<Link> ACLcontents = new ArrayList<Link>();
			Link lk = new Link(new ContentName(userNamespaceCN, principalName), ACL.LABEL_MANAGER, null);
			ACLcontents.add(lk);
			ACL domainRootACL = new ACL(ACLcontents);

			// Set user and group storage locations as parameterized names
			ArrayList<ParameterizedName> parameterizedNames = new ArrayList<ParameterizedName>();
			ParameterizedName uName = new ParameterizedName("User", userNamespaceCN, null);
			parameterizedNames.add(uName);
			ParameterizedName gName = new ParameterizedName("Group", groupNamespaceCN, null);
			parameterizedNames.add(gName);

			// Set access control policy marker
			ContentName profileName = ContentName.fromNative(GroupAccessControlManager.PROFILE_NAME_STRING);
			GroupAccessControlManager.create(domainPrefix, profileName, domainRootACL, parameterizedNames, null, SaveType.REPOSITORY, CCNHandle.open());

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.exit(0);
	}

}
