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
