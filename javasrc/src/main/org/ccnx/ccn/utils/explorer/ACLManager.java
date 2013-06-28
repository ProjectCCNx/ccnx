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

import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLObject;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.protocol.ContentName;

public class ACLManager extends JDialog implements ActionListener {

	private static final long serialVersionUID = 1L;
	
	private GroupAccessControlManager acm;
	private GroupManager gm;
	private PrincipalEnumerator pEnum;
	private ContentName node;
	ContentName userStorage = new ContentName(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = new ContentName(UserConfiguration.defaultNamespace(), "Groups");
	
	private ContentName[] userList;
	private ContentName[] groupList;
	private ACLObject currentACLObject;
	private ACL currentACL;
	private ACLTable userACLTable;
	private ACLTable groupACLTable;
	
	// GUI elements
	private JButton applyChangesButton;
	private JButton cancelChangesButton;
	
	
	public ACLManager(String path, GroupAccessControlManager gacm) {

		super();
		setBounds(100, 100, 400, 500);
		setTitle("Manage Access Controls for "+path);
		getContentPane().setLayout(null);
		
		// enumerate existing users and groups
		try{
			acm = gacm;
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
		try {
			currentACL = currentACLObject.acl();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
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
		cancelChangesButton.setText("Cancel");
		cancelChangesButton.setBounds(200, 400, 112, 25);
		getContentPane().add(cancelChangesButton);
		
	}

	public boolean hasACL() {
		if (currentACLObject != null) return true;
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
			currentACLObject = acm.getEffectiveACLObject(node);
		}
		catch (IllegalStateException ise) {
			System.out.println("Fatal error: the repository has no root ACL.");
			ise.printStackTrace();
		}
		catch (Exception e) {
			e.printStackTrace();
		}		
	}	

	public void actionPerformed(ActionEvent ae) {
		if (applyChangesButton == ae.getSource()) applyChanges();
		else if (cancelChangesButton == ae.getSource()) closeACLManagerWindow();	
	}
	
	private void applyChanges() {
		ArrayList<ACLOperation> userUpdates = userACLTable.computeACLUpdates();
		ArrayList<ACLOperation> groupUpdates = groupACLTable.computeACLUpdates();
		System.out.println("User updates:");
		for (ACLOperation aclo: userUpdates) System.out.println(aclo.targetName() + " ---> " + aclo.targetLabel());
		System.out.println("Group updates:");
		for (ACLOperation aclo: groupUpdates) System.out.println(aclo.targetName() + " ---> " + aclo.targetLabel());
		try {			
			if (! currentACLObject.getBaseName().equals(GroupAccessControlProfile.aclName(node))) {
				// There is no actual ACL at this node.
				// So we copy the effective ACL to this node before updating it.
				acm.setACL(node, currentACL);
			}
			if (userUpdates.size() > 0) acm.updateACL(node, userUpdates);
			if (groupUpdates.size() > 0) acm.updateACL(node, groupUpdates);
		} catch (AccessDeniedException ade) {
			JOptionPane.showMessageDialog(this, "You do not have the access right to edit the ACL at this node.");
			closeACLManagerWindow();
			ade.printStackTrace();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		closeACLManagerWindow();
	}
	
	private void closeACLManagerWindow() {
		this.setVisible(false);
		this.dispose();
	}
	
}
