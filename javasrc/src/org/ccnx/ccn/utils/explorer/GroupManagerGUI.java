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
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.border.BevelBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.protocol.ContentName;

public class GroupManagerGUI extends JDialog implements ActionListener, ListSelectionListener {

	private static final long serialVersionUID = 1L;
	
	private GroupManager gm;
	private PrincipalEnumerator pEnum;
	ContentName userStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Groups");
	
	private ArrayList<String> userFriendlyNameList = new ArrayList<String>();
	private ArrayList<String> groupFriendlyNameList = new ArrayList<String>();
	private ArrayList<String> groupMembersFriendlyNameList = new ArrayList<String>();
	
	private SortedListModel groupsListModel = null;
	private SortedListModel groupMembershipListModel = null;
	private SortedListModel principalsListModel = null;

	// group updates
	private String selectedGroupFriendlyName;
	private ArrayList<Link> membersToAdd;
	private ArrayList<Link> membersToRemove;
	
	// GUI elements
	private JLabel newGroupLabel; 
	private JLabel groupMembershipLabel;
	private JLabel groupMembersLabel;
	private JList groupsList;
	private JList principalsList;
	private JList groupMembershipList;
	private JTextField newGroupName;
	private JButton createGroupButton;
	private JButton addMemberButton;
	private JButton removeMemberButton;
	private JButton applyChangesButton;
	private JButton cancelChangesButton;
	private JScrollPane scrollPaneGroupMembership;
	private JScrollPane scrollPaneUsers;

	// GUI positions
	private int LEFT_MARGIN = 45;
	private int SCROLL_PANEL_WIDTH = 160;
	private int SCROLL_PANEL_HEIGHT = 180;
	private int VERTICAL_OFFSET = 150;
	

	public GroupManagerGUI(String path) {

		super();
		setTitle("Group Manager");
		getContentPane().setLayout(null);
		setBounds(100, 100, 550, 500);

		// enumerate existing users and groups
		try{
			GroupAccessControlManager acm = new GroupAccessControlManager(null, groupStorage, userStorage, CCNHandle.open());
			gm = acm.groupManager();
		} catch (Exception e) {
			e.printStackTrace();
		}
		pEnum = new PrincipalEnumerator(gm);
		userFriendlyNameList = pEnum.enumerateUserFriendlyName();
		groupFriendlyNameList = pEnum.enumerateGroupFriendlyName();
		groupMembershipListModel = new SortedListModel();	
		
		// group list (single group selection)
		final JLabel groupsLabel = new JLabel();
		groupsLabel.setText("Groups");
		groupsLabel.setBounds(LEFT_MARGIN, 10, 69, 15);
		getContentPane().add(groupsLabel);				

		final JScrollPane scrollPaneGroups = new JScrollPane();
		scrollPaneGroups.setBounds(LEFT_MARGIN, 37, 388, 58);
		getContentPane().add(scrollPaneGroups);
		groupsListModel = new SortedListModel();
		groupsListModel.addAll(groupFriendlyNameList.toArray());
		groupsList = new JList(groupsListModel);
		groupsList.setName("groups");
		scrollPaneGroups.setViewportView(groupsList);
		groupsList.setBorder(new BevelBorder(BevelBorder.LOWERED));
		groupsList.addListSelectionListener(this);

		// create new group
		newGroupLabel = new JLabel();
		newGroupLabel.setText("New group name: ");
		newGroupLabel.setBounds(LEFT_MARGIN, 100, 150, 20);
		getContentPane().add(newGroupLabel);				
		
		newGroupName = new JTextField();
		newGroupName.setBounds(LEFT_MARGIN + 150, 100, 150, 20);
		getContentPane().add(newGroupName);
		
		createGroupButton = new JButton();
		createGroupButton.setText("Create New Group");
		createGroupButton.addActionListener(this);
		createGroupButton.setBounds(LEFT_MARGIN + 100, 100, 200, 20);
		getContentPane().add(createGroupButton);
		
		// principal list
		groupMembersLabel = new JLabel();
		groupMembersLabel.setAutoscrolls(true);
		groupMembersLabel.setText("Principals");
		groupMembersLabel.setBounds(LEFT_MARGIN, VERTICAL_OFFSET, 98, 15);
		getContentPane().add(groupMembersLabel);
		
		scrollPaneUsers = new JScrollPane();
		scrollPaneUsers.setBounds(LEFT_MARGIN, VERTICAL_OFFSET + 40, SCROLL_PANEL_WIDTH, SCROLL_PANEL_HEIGHT);
		getContentPane().add(scrollPaneUsers);
		ArrayList<String> principalFriendlyName = new ArrayList<String>();
		principalFriendlyName.addAll(userFriendlyNameList);
		principalFriendlyName.addAll(groupFriendlyNameList);
		principalsListModel = new SortedListModel();
		principalsListModel.addAll(principalFriendlyName.toArray());
		principalsList = new JList(principalsListModel);
		principalsList.setName("users");
		scrollPaneUsers.setViewportView(principalsList);
		principalsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		principalsList.setBorder(new BevelBorder(BevelBorder.LOWERED));

		// add and remove buttons
		addMemberButton = new JButton();
		addMemberButton.addActionListener(this);
		addMemberButton.setText("add ->");
		addMemberButton.setBounds(225, VERTICAL_OFFSET + 80, 102, 25);
		getContentPane().add(addMemberButton);

		removeMemberButton = new JButton();
		removeMemberButton.addActionListener(this);
		removeMemberButton.setText("<- remove");
		removeMemberButton.setBounds(225, VERTICAL_OFFSET + 150, 102, 25);
		getContentPane().add(removeMemberButton);
		
		
		// group membership list
		groupMembershipLabel = new JLabel();
		groupMembershipLabel.setAutoscrolls(true);
		groupMembershipLabel.setText("Group Members");
		groupMembershipLabel.setBounds(342, VERTICAL_OFFSET, 153, 15);
		getContentPane().add(groupMembershipLabel);
		
		scrollPaneGroupMembership = new JScrollPane();
		scrollPaneGroupMembership.setBounds(348, VERTICAL_OFFSET + 40, SCROLL_PANEL_WIDTH, SCROLL_PANEL_HEIGHT);
		getContentPane().add(scrollPaneGroupMembership);
		
		groupMembershipList = new JList(groupMembershipListModel);
		groupMembershipList.setName("groupMembers");
		scrollPaneGroupMembership.setViewportView(groupMembershipList);
		groupMembershipList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		groupMembershipList.setBorder(new BevelBorder(BevelBorder.LOWERED));
		
		// apply and cancel buttons
		applyChangesButton = new JButton();
		applyChangesButton.addActionListener(this);
		applyChangesButton.setMargin(new Insets(2, 2, 2, 2));
		applyChangesButton.setBounds(LEFT_MARGIN, VERTICAL_OFFSET + 250, 112, 25);
		applyChangesButton.setText("Apply Changes");
		getContentPane().add(applyChangesButton);

		cancelChangesButton = new JButton();
		cancelChangesButton.addActionListener(this);
		cancelChangesButton.setMargin(new Insets(2, 2, 2, 2));
		cancelChangesButton.setText("Cancel Changes");
		cancelChangesButton.setBounds(363, VERTICAL_OFFSET + 250, 112, 25);
		getContentPane().add(cancelChangesButton);
		
		selectGroupView();
	}
	
	/**
	 * Display the basic view in which the user can select a group to edit.
	 */
	public void selectGroupView() {
		createGroupButton.setVisible(true);
		newGroupLabel.setVisible(false);
		newGroupName.setVisible(false);
		groupMembersLabel.setVisible(false);
		groupMembershipLabel.setVisible(false);
		scrollPaneUsers.setVisible(false);
		scrollPaneGroupMembership.setVisible(false);
		addMemberButton.setVisible(false);
		removeMemberButton.setVisible(false);
		applyChangesButton.setVisible(false);
		cancelChangesButton.setVisible(false);		
	}
	
	/**
	 * Display the view which allows a user to edit the membership of a group.
	 */
	public void editGroupMembershipView() {
		createGroupButton.setVisible(true);
		newGroupLabel.setVisible(false);
		newGroupName.setVisible(false);
		groupMembersLabel.setVisible(true);
		groupMembershipLabel.setVisible(true);
		scrollPaneUsers.setVisible(true);
		scrollPaneGroupMembership.setVisible(true);
		addMemberButton.setVisible(true);
		removeMemberButton.setVisible(true);
		applyChangesButton.setText("Apply Changes");
		applyChangesButton.setVisible(true);
		cancelChangesButton.setText("Cancel Changes");
		cancelChangesButton.setVisible(true);
	}
	
	/**
	 * Display the view which allows the user to create a new group.
	 */
	public void createNewGroupView() {
		createGroupButton.setVisible(false);
		newGroupLabel.setVisible(true);
		newGroupName.setVisible(true);
		groupMembersLabel.setVisible(true);
		groupMembershipLabel.setVisible(true);
		scrollPaneUsers.setVisible(true);
		scrollPaneGroupMembership.setVisible(true);
		addMemberButton.setVisible(true);
		removeMemberButton.setVisible(true);
		applyChangesButton.setText("Create Group");
		applyChangesButton.setVisible(true);
		cancelChangesButton.setText("Cancel");
		cancelChangesButton.setVisible(true);		
	}
	
	public void actionPerformed(ActionEvent e) {
		if (applyChangesButton == e.getSource()) applyChanges();
		else if (cancelChangesButton == e.getSource()) cancelChanges();
		else if (addMemberButton == e.getSource()) addPrincipals();
		else if (removeMemberButton == e.getSource()) removePrincipals();
		else if (createGroupButton == e.getSource()) createNewGroup();
	}
	

	/**
	 * Apply all batched operations (addition or removal of principals)
	 */
	private void applyChanges() {
		try{
			if (selectedGroupFriendlyName != null) {
				// we are applying changes to an existing group
				Group g = gm.getGroup(selectedGroupFriendlyName);
				g.modify(membersToAdd, membersToRemove);
			}
			else {
				// we are creating a new group
				selectedGroupFriendlyName = newGroupName.getText();
				gm.createGroup(selectedGroupFriendlyName, membersToAdd);
				groupFriendlyNameList = pEnum.enumerateGroupFriendlyName();
				groupsListModel.clear();
				groupsListModel.addAll(groupFriendlyNameList.toArray());
				selectGroupView();
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	
	/**
	 * Cancel all batched operations (addition or removal of principals)
	 */
	private void cancelChanges() {
		membersToAdd = new ArrayList<Link>();
		membersToRemove = new ArrayList<Link>();
		populateGroupMembershipList();
		populatePrincipalsList();
		if (selectedGroupFriendlyName == null) selectGroupView();
	}

	
	/**
	 * Add selected principals (users of groups) to the group 
	 * identified by selectedGroupFriendlyName.
	 * Note that addition (and removal) operations are batched and only applied when the 
	 * method applyChanges() is called.
	 */	
	private void addPrincipals() {
		ArrayList<Object> principalsToAdd = new ArrayList<Object>();
		
		int[] selectedPrincipals = principalsList.getSelectedIndices();
		for (int index: selectedPrincipals) {
			Object obj = principalsList.getModel().getElementAt(index);
			principalsToAdd.add(obj);
		}
		
		for (Object obj: principalsToAdd) {
			((SortedListModel) groupMembershipList.getModel()).add(obj);
			((SortedListModel) principalsList.getModel()).removeElement(obj);
			String principalFriendlyName = (String) obj;
			ContentName cn = ContentName.fromNative(userStorage, principalFriendlyName);
			Link lk = new Link(cn);
			membersToAdd.add(lk);
		}
		principalsList.clearSelection();
	}

	
	/**
	 * Remove selected principals (users of groups) from the group 
	 * identified by selectedGroupFriendlyName.
	 * Note that removal (and addition) operations are batched and only applied when the 
	 * method applyChanges() is called.
	 */
	private void removePrincipals() {
		ArrayList<Object> principalsToRemove = new ArrayList<Object>();
		
		int[] selectedPrincipals = groupMembershipList.getSelectedIndices();		
		for (int index: selectedPrincipals) {
			Object obj = groupMembershipList.getModel().getElementAt(index);
			principalsToRemove.add(obj);
		}
		
		for (Object obj: principalsToRemove) {
			((SortedListModel) principalsList.getModel()).add(obj);
			((SortedListModel) groupMembershipList.getModel()).removeElement(obj);
			String principalFriendlyName = (String) obj;
			ContentName cn = ContentName.fromNative(userStorage, principalFriendlyName);
			Link lk = new Link(cn);
			membersToRemove.add(lk);
		}
		groupMembershipList.clearSelection();
	}
	
	
	/**
	 * Create a new group
	 */
	private void createNewGroup() {
		groupsList.clearSelection();
		newGroupName.setText("");
		selectedGroupFriendlyName = null;
		populateGroupMembershipList();
		populatePrincipalsList();
		membersToAdd = new ArrayList<Link>();
		membersToRemove = new ArrayList<Link>();
		createNewGroupView();
	}

	
	/**
	 * Display the members of selectedGroupFriendlyName.
	 * If selectedGroupFriendlyName is null, the membership list is empty (e.g. we are creating a new group)
	 */
	public void populateGroupMembershipList() {
		groupMembershipListModel.clear();
		groupMembersFriendlyNameList = pEnum.enumerateGroupMembers(selectedGroupFriendlyName);
		groupMembershipListModel.addAll(groupMembersFriendlyNameList.toArray());
	}

	
	/**
	 * Display the list of principals (users and groups) which are not already included
	 * in the membership list of selectedGroupFriendlyName.
	 */
	public void populatePrincipalsList() {
		principalsListModel.clear();
		ArrayList<String> principalFriendlyName = new ArrayList<String>();
		principalFriendlyName.addAll(userFriendlyNameList);
		principalFriendlyName.addAll(groupFriendlyNameList);
		principalsListModel.addAll(principalFriendlyName.toArray());
		principalsListModel.removeElementArrayList(groupMembersFriendlyNameList);
	}

	
	/**
	 * Display the membership list and the list of principals that can be added
	 * to the selected group.
	 */
	public void valueChanged(ListSelectionEvent e) {
		JList list = (JList) e.getSource();		
		if(list.getSelectedValue() != null){
			selectedGroupFriendlyName = list.getSelectedValue().toString();
			membersToAdd = new ArrayList<Link>();
			membersToRemove = new ArrayList<Link>();
			populateGroupMembershipList();
			populatePrincipalsList();
			editGroupMembershipView();
		}
	}
	

}
