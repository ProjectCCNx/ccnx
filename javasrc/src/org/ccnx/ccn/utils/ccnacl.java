package org.ccnx.ccn.utils;

import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.protocol.ContentName;

public class ccnacl {

	private static ContentName userStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Users");
	private static ContentName groupStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Groups");
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if ((args == null) || (args.length == 0)) {
			usage();
		}
		else if (args[0].equals("-show")) {
			if (args.length < 2) {
				usage();
				System.exit(1);
			}
			String nodeName = args[1];
			showACL(nodeName);
		}
		else if (args[0].equals("-edit")) {
			if (args.length < 4) {
				usage();
				System.exit(1);
			}
			String nodeName = args[1];
			String principalName = args[2];
			String role = args[3];
			editACL(nodeName, principalName, role);
		}
	}

	public static void usage() {
		System.out.println("usage:");
		System.out.println("ccnacl -show nodeName");
		System.out.println("ccnacl -edit nodeName principalName [null|r|rw|rw+]");
		System.exit(1);
	}
	
	public static void showACL(String nodeName) {
		try{
			ContentName baseNode = ContentName.fromNative("/");
			GroupAccessControlManager acm = new GroupAccessControlManager(baseNode, groupStorage, userStorage, CCNHandle.open());
			ContentName node = ContentName.fromNative(nodeName);
			ACL acl = acm.getEffectiveACLObject(node).acl();
			System.out.println("ACL for node: " + nodeName);
			for (int j=0; j<acl.size(); j++) {
				Link lk = (Link) acl.get(j);
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
			// TODO: we set the ACL, then update it, to handle correctly the case
			// where the node had no ACL to start with.
			// It would be more efficient to set and update the ACL in a single step.
			ACL initialACL = acm.getEffectiveACLObject(node).acl();
			acm.setACL(node, initialACL);
			
			// initial role
			ContentName principal = ContentName.fromNative(principalName);
			Link plk = new Link(principal);
			String initialRole = null;
			for (int j=0; j<initialACL.size(); j++) {
				Link lk = (Link) initialACL.get(j);
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
				if (role == null) ACLUpdates.add(ACLOperation.removeReaderOperation(plk));
				else if (role.equals(ACL.LABEL_WRITER)) ACLUpdates.add(ACLOperation.addWriterOperation(plk));
				else if (role.equals(ACL.LABEL_MANAGER)) ACLUpdates.add(ACLOperation.addManagerOperation(plk));
			}
			else if (initialRole.equals(ACL.LABEL_WRITER)) {
				if (role == null) ACLUpdates.add(ACLOperation.removeWriterOperation(plk));
				else if (role.equals(ACL.LABEL_READER)) {
					ACLUpdates.add(ACLOperation.removeWriterOperation(plk));
					ACLUpdates.add(ACLOperation.addReaderOperation(plk));
				}
				else if (role.equals(ACL.LABEL_MANAGER)) ACLUpdates.add(ACLOperation.addManagerOperation(plk));
			}
			else if (initialRole.equals(ACL.LABEL_MANAGER)) {
				if (role == null) ACLUpdates.add(ACLOperation.removeManagerOperation(plk));
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
		catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.exit(0);
	}
	
}
