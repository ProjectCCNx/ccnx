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
import javax.swing.JScrollPane;
import javax.swing.JTable;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLObject;
import org.ccnx.ccn.protocol.ContentName;

public class ACLManager extends JDialog implements ActionListener {

	private static final long serialVersionUID = 1L;
	
	private GroupAccessControlManager acm;
	private GroupManager gm;
	private PrincipalEnumerator pEnum;
	private ContentName node;
	ContentName userStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Groups");
	
	private ArrayList<String> userFriendlyNameList = new ArrayList<String>();
	private ArrayList<String> groupFriendlyNameList = new ArrayList<String>();
	
	// GUI elements
	private JButton applyChangesButton;
	private JButton cancelChangesButton;
	
	
	public ACLManager(String path) {

		super();
		setBounds(100, 100, 400, 500);
		setTitle("Manage Access Controls for "+path);
		getContentPane().setLayout(null);
		
		// compute CCN node
		try{
			node = ContentName.fromNative(path);
			System.out.println("***node: " + node.toString());
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		
		// enumerate existing users and groups
		try{
			ContentName baseNode = ContentName.fromNative("/");
			acm = new GroupAccessControlManager(baseNode, groupStorage, userStorage, CCNHandle.open());
			gm = acm.groupManager();
		} catch (Exception e) {
			e.printStackTrace();
		}
		pEnum = new PrincipalEnumerator(gm);
		userFriendlyNameList = pEnum.enumerateUserFriendlyName();
		groupFriendlyNameList = pEnum.enumerateGroupFriendlyName();
		
		final JLabel userAndGroupLabel = new JLabel();
		userAndGroupLabel.setBounds(10, 30, 300, 15);
		userAndGroupLabel.setText("User and Group Permissions for " + path);
		getContentPane().add(userAndGroupLabel);
				
		// user table
		JTable usersTable = new JTable(new ACLTable("Users", userFriendlyNameList));
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
		JTable groupsTable = new JTable(new ACLTable("Groups", groupFriendlyNameList));
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


	public void actionPerformed(ActionEvent ae) {
		if (applyChangesButton == ae.getSource()) applyChanges();
		else if (cancelChangesButton == ae.getSource()) cancelChanges();	
	}
	
	private void applyChanges() {
	}
	
	private void cancelChanges() {
		try{
			ACLObject aclo = acm.getEffectiveACLObject(node);
			System.out.println(aclo);
//			for (int i=0; i<acl.size(); i++) {
//				Link l = (Link) acl.get(i);
//				System.out.println(l.targetName());
//				System.out.println(l.targetLabel());
//			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
	
}
