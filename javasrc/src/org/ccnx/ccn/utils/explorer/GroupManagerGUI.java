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

import java.awt.Color;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.border.BevelBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;

public class GroupManagerGUI extends JDialog implements ActionListener, ListSelectionListener {

	private static final long serialVersionUID = 1L;
	
	private GroupManager gm;
	private PrincipalEnumerator pEnum;
	ContentName userStorage = new ContentName(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = new ContentName(UserConfiguration.defaultNamespace(), "Groups");
	
	private ArrayList<ContentName> usersContentNameList = new ArrayList<ContentName>();
	private ArrayList<ContentName> groupsContentNameList = new ArrayList<ContentName>();
	private ArrayList<ContentName> groupMembersContentNameList = new ArrayList<ContentName>();
	
	private SortedListModel groupEditorModel = null;
	private SortedListModel groupMembershipListModel = null;
	private SortedListModel userSelectorModel = null;
	private SortedListModel groupSelectorModel = null;

	// group updates
	private String selectedGroupFriendlyName;
	private ArrayList<Link> membersToAdd;
	private ArrayList<Link> membersToRemove;
	
	// GUI elements
	private JPanel membershipPanel;
	private JLabel newGroupLabel; 
	private JList groupEditor;
	private JList userSelector;
	private JList groupSelector;
	private JList groupMembershipList;
	private JTextField newGroupName;
	private JButton createGroupButton;
	private JButton addMemberButton;
	private JButton removeMemberButton;
	private JButton applyChangesButton;
	private JButton cancelChangesButton;
	private JScrollPane scrollPaneGroupMembership;
	private JScrollPane scrollPaneUsers;
	private JScrollPane scrollPaneGroups;

	// GUI positions
	private int LEFT_MARGIN = 30;
	private int GROUP_EDITOR_HEIGHT = 100;
	private int VERTICAL_OFFSET = GROUP_EDITOR_HEIGHT + 110;
	private int SELECTOR_WIDTH = 200;
	private int SELECTOR_HEIGHT = 300;
	

	public GroupManagerGUI(String path, GroupAccessControlManager acm) {

		super();
		setTitle("Group Manager");
		getContentPane().setLayout(null);
		setBounds(100, 100, (SELECTOR_WIDTH * 2) + 180, VERTICAL_OFFSET + SELECTOR_HEIGHT + 180);

		// enumerate existing users and groups
		try{
			gm = acm.groupManager();
		} catch (Exception e) {
			e.printStackTrace();
		}
		pEnum = new PrincipalEnumerator(gm);
		usersContentNameList = pEnum.enumerateUsers();
		groupsContentNameList = pEnum.enumerateGroups();
		groupMembershipListModel = new SortedListModel();	
		
		// group list (single group selection)
		final JLabel groupEditorLabel = new JLabel();
		groupEditorLabel.setText("Select an existing group:");
		groupEditorLabel.setBounds(LEFT_MARGIN, 10, 200, 15);
		getContentPane().add(groupEditorLabel);				

		final JScrollPane scrollPaneGroupEditor = new JScrollPane();
		scrollPaneGroupEditor.setBounds(LEFT_MARGIN, 37, 388, GROUP_EDITOR_HEIGHT);
		getContentPane().add(scrollPaneGroupEditor);
		groupEditorModel = new SortedListModel();
		groupEditorModel.addAll(groupsContentNameList.toArray());
		groupEditor = new JList(groupEditorModel);
		groupEditor.setName("groups");
		scrollPaneGroupEditor.setViewportView(groupEditor);
		groupEditor.setBorder(new BevelBorder(BevelBorder.LOWERED));
		groupEditor.addListSelectionListener(this);

		// create new group
		newGroupLabel = new JLabel();
		newGroupLabel.setText("New group name: ");
		newGroupLabel.setBounds(LEFT_MARGIN, GROUP_EDITOR_HEIGHT + 60, 150, 20);
		getContentPane().add(newGroupLabel);				
		
		newGroupName = new JTextField();
		newGroupName.setBounds(LEFT_MARGIN + 150, GROUP_EDITOR_HEIGHT + 60, 150, 20);
		getContentPane().add(newGroupName);
		
		createGroupButton = new JButton();
		createGroupButton.setText("Create New Group");
		createGroupButton.addActionListener(this);
		createGroupButton.setBounds(LEFT_MARGIN, GROUP_EDITOR_HEIGHT + 60, 200, 20);
		getContentPane().add(createGroupButton);
		
		// Membership panel
		membershipPanel = new JPanel();
		membershipPanel.setLayout(null);
		membershipPanel.setBounds(LEFT_MARGIN, VERTICAL_OFFSET, (SELECTOR_WIDTH * 2) + 110, SELECTOR_HEIGHT + 140);
		getContentPane().add(membershipPanel);
		
		// user selector
		userSelectorModel = new SortedListModel();
		userSelectorModel.addAll(usersContentNameList.toArray());
		userSelector = new JList(userSelectorModel);
		userSelector.setName("users");
		userSelector.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
//		userSelector.setBorder(new BevelBorder(BevelBorder.LOWERED));
		scrollPaneUsers = new JScrollPane();
		scrollPaneUsers.setViewportView(userSelector);
		scrollPaneUsers.setBounds(10, 30, SELECTOR_WIDTH, SELECTOR_HEIGHT / 2);
		scrollPaneUsers.setBorder(BorderFactory.createTitledBorder("Users"));
		membershipPanel.add(scrollPaneUsers);

		// group selector
		groupSelectorModel = new SortedListModel();
		groupSelectorModel.addAll(groupsContentNameList.toArray());
		groupSelector = new JList(groupSelectorModel);
		groupSelector.setName("group_selector");
		groupSelector.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
//		groupSelector.setBorder(new BevelBorder(BevelBorder.LOWERED));
		scrollPaneGroups = new JScrollPane();
		scrollPaneGroups.setViewportView(groupSelector);
		scrollPaneGroups.setBounds(10, 40 + (SELECTOR_HEIGHT/2), SELECTOR_WIDTH, SELECTOR_HEIGHT / 2);
		scrollPaneGroups.setBorder(BorderFactory.createTitledBorder("Groups"));
		membershipPanel.add(scrollPaneGroups);
		
		
		// add and remove buttons
		addMemberButton = new JButton();
		addMemberButton.addActionListener(this);
		addMemberButton.setText("->");
		addMemberButton.setBounds(SELECTOR_WIDTH + 20, 80, 52, 25);
		membershipPanel.add(addMemberButton);

		removeMemberButton = new JButton();
		removeMemberButton.addActionListener(this);
		removeMemberButton.setText("<-");
		removeMemberButton.setBounds(SELECTOR_WIDTH + 20, 150, 52, 25);
		membershipPanel.add(removeMemberButton);
		
		
		// group membership list
		groupMembershipList = new JList(groupMembershipListModel);
		groupMembershipList.setName("groupMembers");
		groupMembershipList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
//		groupMembershipList.setBorder(new BevelBorder(BevelBorder.LOWERED));
		scrollPaneGroupMembership = new JScrollPane();
		scrollPaneGroupMembership.setViewportView(groupMembershipList);
		scrollPaneGroupMembership.setBounds(SELECTOR_WIDTH + 90, 30, SELECTOR_WIDTH, SELECTOR_HEIGHT + 10);
		scrollPaneGroupMembership.setBorder(BorderFactory.createTitledBorder("Group Members"));
		membershipPanel.add(scrollPaneGroupMembership);
				
		// apply and cancel buttons
		applyChangesButton = new JButton();
		applyChangesButton.addActionListener(this);
		applyChangesButton.setMargin(new Insets(2, 2, 2, 2));
		applyChangesButton.setBounds(LEFT_MARGIN, SELECTOR_HEIGHT + 90, 112, 25);
		applyChangesButton.setText("Apply Changes");
		membershipPanel.add(applyChangesButton);

		cancelChangesButton = new JButton();
		cancelChangesButton.addActionListener(this);
		cancelChangesButton.setMargin(new Insets(2, 2, 2, 2));
		cancelChangesButton.setText("Cancel Changes");
		cancelChangesButton.setBounds(320, SELECTOR_HEIGHT + 90, 112, 25);
		membershipPanel.add(cancelChangesButton);
		
		selectGroupView();
	}
	
	/**
	 * Display the basic view in which the user can select a group to edit.
	 */
	public void selectGroupView() {
		createGroupButton.setVisible(true);
		newGroupLabel.setVisible(false);
		newGroupName.setVisible(false);
		membershipPanel.setVisible(false);
	}
	
	/**
	 * Display the view which allows a user to edit the membership of a group.
	 */
	public void editGroupMembershipView() {
		userSelector.clearSelection();
		groupSelector.clearSelection();
		groupMembershipList.clearSelection();

		createGroupButton.setVisible(true);
		newGroupLabel.setVisible(false);
		newGroupName.setVisible(false);

		applyChangesButton.setText("Apply Changes");
		cancelChangesButton.setText("Cancel Changes");
		membershipPanel.setBorder(
				BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.BLACK, 2), 
						"Group: " + selectedGroupFriendlyName));
		membershipPanel.setVisible(true);
	}
	
	/**
	 * Display the view which allows the user to create a new group.
	 */
	public void createNewGroupView() {
		userSelector.clearSelection();
		groupSelector.clearSelection();
		groupMembershipList.clearSelection();

		createGroupButton.setVisible(false);
		newGroupLabel.setVisible(true);
		newGroupName.setVisible(true);

		applyChangesButton.setText("Create Group");
		cancelChangesButton.setText("Cancel");
		membershipPanel.setBorder(
				BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.BLACK, 2), "New Group"));
		membershipPanel.setVisible(true);
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
		if (selectedGroupFriendlyName != null) {
			// we are applying changes to an existing group
			try {
				System.out.println("Members to add:");
				for (Link l: membersToAdd) {
					System.out.println(l.targetName());
				}
				System.out.println("Members to remove:");
				for (Link l: membersToRemove) {
					System.out.println(l.targetName());
				}
				Group g = gm.getGroup(selectedGroupFriendlyName, SystemConfiguration.getDefaultTimeout());
				g.modify(membersToAdd, membersToRemove);
			} catch (AccessDeniedException ade) {
				JOptionPane.showMessageDialog(this, "You do not have the access right to edit this group.");
				ade.printStackTrace();
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
		else {
			// we are creating a new group
			String newName = newGroupName.getText();
			if (validateNewGroupName(newName)) {
				try {
					System.out.println("Members to add:");
					for (Link l: membersToAdd) {
						System.out.println(l.targetName());
					}
					gm.createGroup(newName, membersToAdd, SystemConfiguration.getDefaultTimeout());
				} catch (Exception e) {
					e.printStackTrace();
				}
				groupsContentNameList = pEnum.enumerateGroups();
				groupEditorModel.clear();
				groupEditorModel.addAll(groupsContentNameList.toArray());
				selectGroupView();
			}
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
		// add selected users
		ArrayList<Object> usersToAdd = new ArrayList<Object>();
		int[] selectedUsers = userSelector.getSelectedIndices();
		for (int index: selectedUsers) {
			Object obj = userSelector.getModel().getElementAt(index);
			usersToAdd.add(obj);
		}
		for (Object obj: usersToAdd) {
			((SortedListModel) groupMembershipList.getModel()).add(obj);
			((SortedListModel) userSelector.getModel()).removeElement(obj);
			ContentName userContentName = (ContentName) obj;
			Link lk = new Link(userContentName);
			if (membersToRemove.contains(lk)) membersToRemove.remove(lk);
			else membersToAdd.add(lk);
		}
		userSelector.clearSelection();
		
		// add selected groups
		ArrayList<Object> groupsToAdd = new ArrayList<Object>();
		int[] selectedGroups = groupSelector.getSelectedIndices();
		for (int index: selectedGroups) {
			Object obj = groupSelector.getModel().getElementAt(index);
			groupsToAdd.add(obj);
		}
		for (Object obj: groupsToAdd) {
			((SortedListModel) groupMembershipList.getModel()).add(obj);
			((SortedListModel) groupSelector.getModel()).removeElement(obj);
			ContentName groupContentName = (ContentName) obj;
			Link lk = new Link(groupContentName);
			if (membersToRemove.contains(lk)) membersToRemove.remove(lk);
			else membersToAdd.add(lk);
		}
		groupSelector.clearSelection();
		
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
			ContentName principalContentName = (ContentName) obj;
			if (usersContentNameList.contains(principalContentName)) ((SortedListModel) userSelector.getModel()).add(obj);
			else if (groupsContentNameList.contains(principalContentName)) ((SortedListModel) groupSelector.getModel()).add(obj);
			else System.out.println("Warning: the principal " + principalContentName + " is neither a known group or a know user.");
			((SortedListModel) groupMembershipList.getModel()).removeElement(obj);
			Link lk = new Link(principalContentName);
			if (membersToAdd.contains(lk)) membersToAdd.remove(lk);
			else membersToRemove.add(lk);
		}
		groupMembershipList.clearSelection();
	}
	
	
	/**
	 * Create a new group
	 */
	private void createNewGroup() {
		groupEditor.clearSelection();
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
		groupMembersContentNameList = pEnum.enumerateGroupMembers(selectedGroupFriendlyName);
		groupMembershipListModel.addAll(groupMembersContentNameList.toArray());
	}

	
	/**
	 * Display the list of principals (users and groups) which are not already included
	 * in the membership list of selectedGroupFriendlyName.
	 */
	public void populatePrincipalsList() {
		userSelectorModel.clear();		
		ArrayList<ContentName> usersNotInGroup = new ArrayList<ContentName>();
		usersNotInGroup.addAll(usersContentNameList);
		usersNotInGroup.removeAll(groupMembersContentNameList);
		userSelectorModel.addAll(usersNotInGroup.toArray());

		groupSelectorModel.clear();
		ArrayList<ContentName> groupsNotInGroup = new ArrayList<ContentName>();
		groupsNotInGroup.addAll(groupsContentNameList);
		groupsNotInGroup.removeAll(groupMembersContentNameList);
		groupSelectorModel.addAll(groupsNotInGroup.toArray());
	}

	
	/**
	 * Display the membership list and the list of principals that can be added
	 * to the selected group.
	 */
	public void valueChanged(ListSelectionEvent e) {
		JList list = (JList) e.getSource();		
		if(list.getSelectedValue() != null){
			ContentName groupContentName = (ContentName) list.getSelectedValue();
			selectedGroupFriendlyName = Component.printNative(groupContentName.lastComponent());
			membersToAdd = new ArrayList<Link>();
			membersToRemove = new ArrayList<Link>();
			populateGroupMembershipList();
			populatePrincipalsList();
			editGroupMembershipView();
		}
	}
	
	/**
	 * Checks the correctness of the name selected for a new group
	 * @param name the name to validate
	 * @return true if the name is valid, false otherwise.
	 */
	private boolean validateNewGroupName(String name) {
		if (name.equals("")) {
			JOptionPane.showMessageDialog(this, "The new group needs a name.");
			return false;
		}
		return true;
	}

}
