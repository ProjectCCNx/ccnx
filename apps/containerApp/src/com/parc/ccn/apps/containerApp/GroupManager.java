package com.parc.ccn.apps.containerApp;

import java.awt.EventQueue;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.border.BevelBorder;

public class GroupManager extends JDialog {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private JList list_2;
	private JList list_1;
	private JList list;


	/**
	 * Create the dialog
	 * @param frame 
	 * @param path 
	 */
	public GroupManager(String path) {
		super();
		setTitle("Manage Group Members");
		getContentPane().setLayout(null);
		setBounds(100, 100, 523, 439);

		final JButton applyChangesButton = new JButton();
		applyChangesButton.setMargin(new Insets(2, 2, 2, 2));
		applyChangesButton.setBounds(47, 362, 112, 25);
		applyChangesButton.setText("Apply Changes");
		getContentPane().add(applyChangesButton);

		final JButton revertChangesButton = new JButton();
		revertChangesButton.setMargin(new Insets(2, 2, 2, 2));
		revertChangesButton.setText("Revert Changes");
		revertChangesButton.setBounds(218, 362, 112, 25);
		getContentPane().add(revertChangesButton);

		final JButton cancelChangesButton = new JButton();
		cancelChangesButton.setMargin(new Insets(2, 2, 2, 2));
		cancelChangesButton.setText("Cancel Changes");
		cancelChangesButton.setBounds(363, 362, 112, 25);
		getContentPane().add(cancelChangesButton);

		final JButton addButton = new JButton();
		addButton.setText("add ->");
		addButton.setBounds(218, 145, 112, 25);
		getContentPane().add(addButton);

		final JButton removeButton = new JButton();
		removeButton.setText("<- remove");
		removeButton.setBounds(218, 302, 112, 25);
		getContentPane().add(removeButton);

		final JLabel groupMembersLabel = new JLabel();
		groupMembersLabel.setAutoscrolls(true);
		groupMembersLabel.setText("List of Users");
		groupMembersLabel.setBounds(47, 120, 98, 15);
		getContentPane().add(groupMembersLabel);

		final JLabel usersLabel = new JLabel();
		usersLabel.setAutoscrolls(true);
		usersLabel.setText("Group Members");
		usersLabel.setBounds(375, 120, 100, 15);
		getContentPane().add(usersLabel);

		list = new JList();
		list.setBorder(new BevelBorder(BevelBorder.LOWERED));
		list.setBounds(45, 147, 120, 181);
		getContentPane().add(list);

		list_1 = new JList();
		list_1.setBorder(new BevelBorder(BevelBorder.LOWERED));
		list_1.setBounds(375, 147, 120, 181);
		getContentPane().add(list_1);

		list_2 = new JList();
		list_2.setBorder(new BevelBorder(BevelBorder.LOWERED));
		list_2.setBounds(76, 37, 388, 58);
		getContentPane().add(list_2);

		final JLabel groupsLabel = new JLabel();
		groupsLabel.setText("Groups");
		groupsLabel.setBounds(76, 10, 69, 15);
		getContentPane().add(groupsLabel);
	}

}
