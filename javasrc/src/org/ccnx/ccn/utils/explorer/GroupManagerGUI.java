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
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.SortedSet;

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
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.profiles.security.access.group.MembershipList;
import org.ccnx.ccn.protocol.ContentName;

public class GroupManagerGUI extends JDialog implements ActionListener, ListSelectionListener {

	private static final long serialVersionUID = 1L;
	
	ContentName userStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Groups");
	private GroupManager gm;
	
	private ArrayList<String> userFriendlyNameList = new ArrayList<String>();
	private ArrayList<String> groupFriendlyNameList = new ArrayList<String>();
	private SortedListModel groupsListModel = null;
	private SortedListModel groupsMembersModel = null;
	private SortedListModel principalsModel = null;

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
	private JList groupMembersList;
	private JTextField newGroupName;
	private JButton createGroupButton;
	private JButton addButton;
	private JButton removeButton;
	private JButton applyChangesButton;
	private JButton cancelChangesButton;
	private JScrollPane scrollPaneGroupMembers;
	private JScrollPane scrollPaneUsers;

	// GUI positions
	private int LEFT_MARGIN = 45;
	private int SCROLL_PANEL_WIDTH = 160;
	private int SCROLL_PANEL_HEIGHT = 180;
	private int VERTICAL_OFFSET = 150;
	
	
	/**
	 * Create the dialog
	 * @param frame 
	 * @param path 
	 */
	public GroupManagerGUI(String path) {

		super();
		setTitle("Group Manager");
		getContentPane().setLayout(null);
		setBounds(100, 100, 550, 500);

		// enumerate existing users and groups
		populateData();
		
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
		principalsModel = new SortedListModel();
		principalsModel.addAll(principalFriendlyName.toArray());
		principalsList = new JList(principalsModel);
		principalsList.setName("users");
		scrollPaneUsers.setViewportView(principalsList);
		principalsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		principalsList.setBorder(new BevelBorder(BevelBorder.LOWERED));

		// add and remove buttons
		addButton = new JButton();
		addButton.addActionListener(this);
		addButton.setText("add ->");
		addButton.setBounds(225, VERTICAL_OFFSET + 80, 102, 25);
		getContentPane().add(addButton);

		removeButton = new JButton();
		removeButton.addActionListener(this);
		removeButton.setText("<- remove");
		removeButton.setBounds(225, VERTICAL_OFFSET + 150, 102, 25);
		getContentPane().add(removeButton);
		
		
		// group membership list
		groupMembershipLabel = new JLabel();
		groupMembershipLabel.setAutoscrolls(true);
		groupMembershipLabel.setText("Group Members");
		groupMembershipLabel.setBounds(342, VERTICAL_OFFSET, 153, 15);
		getContentPane().add(groupMembershipLabel);
		
		scrollPaneGroupMembers = new JScrollPane();
		scrollPaneGroupMembers.setBounds(348, VERTICAL_OFFSET + 40, SCROLL_PANEL_WIDTH, SCROLL_PANEL_HEIGHT);
		getContentPane().add(scrollPaneGroupMembers);
		
		groupMembersList = new JList(groupsMembersModel);
		groupMembersList.setName("groupMembers");
		scrollPaneGroupMembers.setViewportView(groupMembersList);
		groupMembersList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		groupMembersList.setBorder(new BevelBorder(BevelBorder.LOWERED));
		
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
		
		basicView();
	}
	
	public void basicView() {
		createGroupButton.setVisible(true);
		newGroupLabel.setVisible(false);
		newGroupName.setVisible(false);
		groupMembersLabel.setVisible(false);
		groupMembershipLabel.setVisible(false);
		scrollPaneUsers.setVisible(false);
		scrollPaneGroupMembers.setVisible(false);
		addButton.setVisible(false);
		removeButton.setVisible(false);
		applyChangesButton.setVisible(false);
		cancelChangesButton.setVisible(false);		
	}
	
	public void editView() {
		createGroupButton.setVisible(true);
		newGroupLabel.setVisible(false);
		newGroupName.setVisible(false);
		groupMembersLabel.setVisible(true);
		groupMembershipLabel.setVisible(true);
		scrollPaneUsers.setVisible(true);
		scrollPaneGroupMembers.setVisible(true);
		addButton.setVisible(true);
		removeButton.setVisible(true);
		applyChangesButton.setText("Apply Changes");
		applyChangesButton.setVisible(true);
		cancelChangesButton.setText("Cancel Changes");
		cancelChangesButton.setVisible(true);
	}
	
	public void createView() {
		createGroupButton.setVisible(false);
		newGroupLabel.setVisible(true);
		newGroupName.setVisible(true);
		groupMembersLabel.setVisible(true);
		groupMembershipLabel.setVisible(true);
		scrollPaneUsers.setVisible(true);
		scrollPaneGroupMembers.setVisible(true);
		addButton.setVisible(true);
		removeButton.setVisible(true);
		applyChangesButton.setText("Create Group");
		applyChangesButton.setVisible(true);
		cancelChangesButton.setText("Cancel");
		cancelChangesButton.setVisible(true);		
	}
	
	
	private void populateData() {		
		try{
			// enumerate users
			userFriendlyNameList = listPrincipals(userStorage);

			// enumerate groups
			GroupAccessControlManager acm = new GroupAccessControlManager(null, groupStorage, userStorage, CCNHandle.open());
			gm = acm.groupManager();
			groupFriendlyNameList = listPrincipals(groupStorage);
		} 
		catch (Exception e) {
			e.printStackTrace();
		}
		groupsMembersModel = new SortedListModel();		
	}

	
	public void actionPerformed(ActionEvent e) {

		if (applyChangesButton == e.getSource()) applyChanges();
		else if (cancelChangesButton == e.getSource()) cancelChanges();
		else if (addButton == e.getSource()) addPrincipals();
		else if (removeButton == e.getSource()) removePrincipals();
		else if (createGroupButton == e.getSource()) createNewGroup();
	}
	

	private void applyChanges() {
		try{
			if (selectedGroupFriendlyName != null) {
				Group g = gm.getGroup(selectedGroupFriendlyName);
				g.modify(membersToAdd, membersToRemove);
			}
			else {
				selectedGroupFriendlyName = newGroupName.getText();
				gm.createGroup(selectedGroupFriendlyName, membersToAdd);
				populateData();
				groupsListModel.clear();
				groupsListModel.addAll(groupFriendlyNameList.toArray());
				basicView();
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	
	private void cancelChanges() {
		membersToAdd = new ArrayList<Link>();
		membersToRemove = new ArrayList<Link>();
		populateParticipantLists();
		if (selectedGroupFriendlyName == null) basicView();
	}

	private void addPrincipals() {
		ArrayList<Object> principalsToAdd = new ArrayList<Object>();
		
		int[] selectedPrincipals = principalsList.getSelectedIndices();
		for (int index: selectedPrincipals) {
			Object obj = principalsList.getModel().getElementAt(index);
			principalsToAdd.add(obj);
		}
		
		for (Object obj: principalsToAdd) {
			((SortedListModel) groupMembersList.getModel()).add(obj);
			((SortedListModel) principalsList.getModel()).removeElement(obj);
			String principalFriendlyName = (String) obj;
			ContentName cn = ContentName.fromNative(userStorage, principalFriendlyName);
			Link lk = new Link(cn);
			membersToAdd.add(lk);
		}
		principalsList.clearSelection();
	}
	
	private void removePrincipals() {
		ArrayList<Object> principalsToRemove = new ArrayList<Object>();
		
		int[] selectedPrincipals = groupMembersList.getSelectedIndices();		
		for (int index: selectedPrincipals) {
			Object obj = groupMembersList.getModel().getElementAt(index);
			principalsToRemove.add(obj);
		}
		
		for (Object obj: principalsToRemove) {
			((SortedListModel) principalsList.getModel()).add(obj);
			((SortedListModel) groupMembersList.getModel()).removeElement(obj);
			String principalFriendlyName = (String) obj;
			ContentName cn = ContentName.fromNative(userStorage, principalFriendlyName);
			Link lk = new Link(cn);
			membersToRemove.add(lk);
		}
		groupMembersList.clearSelection();
	}
	
	private void createNewGroup() {
		groupsList.clearSelection();
		newGroupName.setText("");
		selectedGroupFriendlyName = null;
		populateParticipantLists();
		membersToAdd = new ArrayList<Link>();
		membersToRemove = new ArrayList<Link>();
		createView();
	}

	private ArrayList<String> listPrincipals(ContentName path) throws Exception {
		ArrayList<String> principalList = new ArrayList<String>();
		
		EnumeratedNameList userDirectory = new EnumeratedNameList(path, CCNHandle.open());
		userDirectory.waitForChildren(); // will block
		Thread.sleep(1000);
		
		SortedSet<ContentName> availableChildren = userDirectory.getChildren();
		if ((null == availableChildren) || (availableChildren.size() == 0)) {
			Log.warning("No available user keystore data in directory " + path + ", giving up.");
			throw new IOException("No available user keystore data in directory " + path + ", giving up.");
		}
		for (ContentName child : availableChildren) {
			String friendlyName = ContentName.componentPrintNative(child.lastComponent());
			System.out.println(friendlyName);
			principalList.add(friendlyName);
		}
		return principalList;
	}
	
	public void populateParticipantLists() {
		ArrayList<String> members = new ArrayList<String>();
		if (selectedGroupFriendlyName != null) {
			try{
				Group g = gm.getGroup(selectedGroupFriendlyName);
				MembershipList ml = g.membershipList();
				LinkedList<Link> lll = ml.contents();
				for (Link l: lll) {
					members.add(ContentName.componentPrintNative(l.targetName().lastComponent()));
				}
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
		groupsMembersModel.clear();
		groupsMembersModel.addAll(members.toArray());
		principalsModel.clear();
		ArrayList<String> principalFriendlyName = new ArrayList<String>();
		principalFriendlyName.addAll(userFriendlyNameList);
		principalFriendlyName.addAll(groupFriendlyNameList);
		principalsModel.addAll(principalFriendlyName.toArray());
		principalsModel.removeElementArrayList(members);
	}

	@Override
	public void valueChanged(ListSelectionEvent e) {
		JList list = (JList) e.getSource();		
		if(list.getSelectedValue() != null){
			selectedGroupFriendlyName = list.getSelectedValue().toString();
			membersToAdd = new ArrayList<Link>();
			membersToRemove = new ArrayList<Link>();
			populateParticipantLists();
			editView();
		}
	}
	

}
