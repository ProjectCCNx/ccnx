package com.parc.ccn.apps.containerApp;

import java.awt.Color;
import java.util.Hashtable;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public class GroupListSelectionListener implements ListSelectionListener {

	JList membersList = null;
	Hashtable<String, String[]> members = null;
	SortedListModel memberModel = null;
	JLabel membersLabel = null;
	public GroupListSelectionListener(JList groupMembersList, Hashtable<String, String[]> groupMembers, SortedListModel groupsMembersModel, JLabel usersLabel) {
		
		this.membersList = groupMembersList;
		this.members = groupMembers;
		this.memberModel = groupsMembersModel;
		this.membersLabel = usersLabel;
	}

	public void valueChanged(ListSelectionEvent e) {
		
		JList list = (JList)e.getSource();
		
		memberModel.clear();
		if(list.getSelectedValue() != null){
			membersList.setEnabled(true);
			membersList.setBackground(Color.white);
			String item = list.getSelectedValue().toString();
		memberModel.addAll((String[])members.get(item));
		this.membersLabel.setText(item +" Group Members");
		System.out.println("SELECTED VALUE IS " + list.getSelectedValue().toString());
		
		}
	}

}
