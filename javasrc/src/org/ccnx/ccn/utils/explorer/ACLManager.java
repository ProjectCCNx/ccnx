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
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.ListSelectionModel;
import javax.swing.border.BevelBorder;

public class ACLManager extends JDialog {

	private static final long serialVersionUID = 1L;
	
	private JList userList;	
	private SortedListModel userPool = null;	

	public ACLManager(String path) {

		super();
		
		setBounds(100, 100, 615, 442);
		setTitle("Manage Access Controls for "+path);
		getContentPane().setLayout(null);
		
		final JLabel userAndGroupLabel = new JLabel();
		userAndGroupLabel.setBounds(0, 0, 600, 15);
		userAndGroupLabel.setText("User and Group Permissions for " + path);
		getContentPane().add(userAndGroupLabel);

		final JLabel usersLabel = new JLabel();
		usersLabel.setBounds(8, 44, 123, 20);
		usersLabel.setText("Users/Groups");
		getContentPane().add(usersLabel);
				
		//List Models for the List Objects
		userPool = new SortedListModel();
//		userPool.addAll(getUserList(path,null));
										
		final JScrollPane scrollPane_2 = new JScrollPane();
		scrollPane_2.setBounds(8, 70, 169, 255);
		getContentPane().add(scrollPane_2);
		
		userList = new JList(userPool);
		userList.setName("userList");
		scrollPane_2.setViewportView(userList);
		userList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		userList.setBorder(new BevelBorder(BevelBorder.LOWERED));
		
	}
	
}
