/**
 * A CCNx command line utility.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.namespace.NamespaceManager;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.protocol.ContentName;

public class ACLManager extends JDialog implements ActionListener {

	private static final long serialVersionUID = 1L;
	
	private GroupAccessControlManager acm;
	private GroupManager gm;
	private PrincipalEnumerator pEnum;
	private ContentName node;
	ContentName userStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Groups");
	
	private ContentName[] userList;
	private ContentName[] groupList;
	private ACL currentACL;
	private ACLTable userACLTable;
	private ACLTable groupACLTable;
	
	// GUI elements
	private JButton applyChangesButton;
	private JButton cancelChangesButton;
	
	
	public ACLManager(String path) {

		super();
		setBounds(100, 100, 400, 500);
		setTitle("Manage Access Controls for "+path);
		getContentPane().setLayout(null);
		
		// enumerate existing users and groups
		try{
			ContentName baseNode = ContentName.fromNative("/");
			acm = new GroupAccessControlManager(baseNode, groupStorage, userStorage, CCNHandle.open());
			NamespaceManager.registerACM(acm);
			gm = acm.groupManager();
		} catch (Exception e) {
			e.printStackTrace();
		}
		pEnum = new PrincipalEnumerator(gm);
		ArrayList<ContentName> temp = pEnum.enumerateUsers();
		userList = temp.toArray(new ContentName[temp.size()]);
		ArrayList<ContentName> temp2 = pEnum.enumerateGroups();
		groupList = temp2.toArray(new ContentName[temp2.size()]);
		
		getNodeName(path);
		getExistingACL();
		
		// title label
		final JLabel userAndGroupLabel = new JLabel();
		userAndGroupLabel.setBounds(10, 30, 300, 15);
		userAndGroupLabel.setText("Permissions for " + path);
		getContentPane().add(userAndGroupLabel);
				
		// user table
		userACLTable = new ACLTable("Users", userList, currentACL);
		JTable usersTable = new JTable(userACLTable);
		usersTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		usersTable.getColumnModel().getColumn(0).setPreferredWidth(200);
		usersTable.getColumnModel().getColumn(1).setPreferredWidth(50);
		usersTable.getColumnModel().getColumn(2).setPreferredWidth(50);
		usersTable.getColumnModel().getColumn(3).setPreferredWidth(50);
		
		final JScrollPane usersScrollPane = new JScrollPane();
		usersScrollPane.setBounds(8, 70, 370, 150);
		usersScrollPane.setViewportView(usersTable);
		getContentPane().add(usersScrollPane);
		
		// group table
		groupACLTable = new ACLTable("Groups", groupList, currentACL);
		JTable groupsTable = new JTable(groupACLTable);
		groupsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		groupsTable.getColumnModel().getColumn(0).setPreferredWidth(200);
		groupsTable.getColumnModel().getColumn(1).setPreferredWidth(50);
		groupsTable.getColumnModel().getColumn(2).setPreferredWidth(50);
		groupsTable.getColumnModel().getColumn(3).setPreferredWidth(50);
		
		final JScrollPane groupsScrollPane = new JScrollPane();
		groupsScrollPane.setBounds(8, 230, 370, 150);
		groupsScrollPane.setViewportView(groupsTable);
		getContentPane().add(groupsScrollPane);

		// apply and cancel buttons
		applyChangesButton = new JButton();
		applyChangesButton.addActionListener(this);
		applyChangesButton.setMargin(new Insets(2, 2, 2, 2));
		applyChangesButton.setBounds(50, 400, 112, 25);
		applyChangesButton.setText("Apply Changes");
		getContentPane().add(applyChangesButton);

		cancelChangesButton = new JButton();
		cancelChangesButton.addActionListener(this);
		cancelChangesButton.setMargin(new Insets(2, 2, 2, 2));
		cancelChangesButton.setText("Cancel Changes");
		cancelChangesButton.setBounds(200, 400, 112, 25);
		getContentPane().add(cancelChangesButton);
		
	}

	public boolean hasACL() {
		if (currentACL != null) return true;
		return false;
	}
	
	private void getNodeName(String path) {
		try{
			node = ContentName.fromNative(path);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private void getExistingACL() {
		try{
			currentACL = acm.getEffectiveACLObject(node).acl();
		}
		catch (IllegalStateException ise) {
			System.out.println("The repository has no root ACL.");
			System.out.println("Attempting to create missing root ACL.");
			createRootACL();
		}
		catch (Exception e) {
			e.printStackTrace();
		}		
	}
	
	private void createRootACL() {
		ContentName cn = ContentName.fromNative(userStorage, "Alice");
		Link lk = new Link(cn, ACL.LABEL_MANAGER, null);
		ArrayList<Link> rootACLcontents = new ArrayList<Link>();
		rootACLcontents.add(lk);
		ACL rootACL = new ACL(rootACLcontents);
		try{
			acm.initializeNamespace(rootACL);
			currentACL = rootACL;
			NamespaceManager.registerACM(acm);
		} 
		catch (ContentNotReadyException cnre) {
			System.out.println("Fatal error: the system assumes the existence of user: " + cn);
			cnre.printStackTrace();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
	

	public void actionPerformed(ActionEvent ae) {
		if (applyChangesButton == ae.getSource()) applyChanges();
		else if (cancelChangesButton == ae.getSource()) cancelChanges();	
	}
	
	private void applyChanges() {
		ArrayList<ACLOperation> userUpdates = userACLTable.computeACLUpdates();
		ArrayList<ACLOperation> groupUpdates = groupACLTable.computeACLUpdates();
		System.out.println("User updates:");
		for (ACLOperation aclo: userUpdates) System.out.println(aclo.targetName() + " ---> " + aclo.targetLabel());
		System.out.println("Group updates:");
		for (ACLOperation aclo: groupUpdates) System.out.println(aclo.targetName() + " ---> " + aclo.targetLabel());
		try {
			// TODO: we set the ACL, then update it, to handle correctly the case
			// where the node had no ACL to start with.
			// It would be more efficient to set and update the ACL in a single step.
			acm.setACL(node, currentACL);
			acm.updateACL(node, userUpdates);
			acm.updateACL(node, groupUpdates);
		} catch (AccessDeniedException ade) {
			JOptionPane.showMessageDialog(this, "You do not have the access right to edit the ACL at this node.");
			ade.printStackTrace();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		// refresh user and group tables with new ACL
		getExistingACL();
		userACLTable.initializeACLTable(currentACL); 
		groupACLTable.initializeACLTable(currentACL); 
	}
	
	private void cancelChanges() {
		userACLTable.cancelChanges();
		groupACLTable.cancelChanges();
	}
	
}
