package com.parc.ccn.apps.containerApp;

import java.awt.EventQueue;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSeparator;
import javax.swing.border.BevelBorder;

public class ACLManager extends JDialog implements ActionListener{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private JList list_2;
	private JList list_1;
	private JList list;

	private JFrame frame;
	private String path;
	/**
	 * Launch the application
	 * @param args
	 */
	
	//Get the list of users
	public void getAvailUserList()
	{
		
	}

	public void getReadOnlyUsersList()
	{
		
	}
	
	public void getReadWriteUsersList()
	{
		
	}

	//On Close or Apply do this
	//Applies new permissions to User Items
	public void setReadOnlyUsersList()
	{
		
	}
	
	//On Close or Apply do this
	//Applies new permissions to User Items
	public void setReadWriteUsersList()
	{
		
	}
	
	/**
	 * Create the dialog
	 * @param frame 
	 * @param path 
	 */
	public ACLManager(String path) {
		super();
		setBounds(100, 100, 679, 452);
		setTitle("Manage Access Controls for "+path);
		getContentPane().setLayout(null);
				
		final JButton button = new JButton();
		button.setBounds(73, 367, 141, 25);
		button.setText("Apply Changes");
		getContentPane().add(button);

		final JButton button_1 = new JButton();
		button_1.setBounds(261, 367, 123, 25);
		button_1.setText("Revert Default");
		getContentPane().add(button_1);

		final JButton button_2 = new JButton();
		button_2.setBounds(421, 367, 133, 25);
		button_2.setText("Cancel Changes");
		getContentPane().add(button_2);

		list = new JList();
		list.setBorder(new BevelBorder(BevelBorder.LOWERED));
		list.setBounds(10, 70, 120, 240);
		getContentPane().add(list);

		list_1 = new JList();
		list_1.setBorder(new BevelBorder(BevelBorder.LOWERED));
		list_1.setBounds(462, 70, 120, 240);
		getContentPane().add(list_1);

		list_2 = new JList();
		list_2.setBorder(new BevelBorder(BevelBorder.LOWERED));
		list_2.setBounds(233, 70, 120, 240);
		getContentPane().add(list_2);

		final JButton button_3 = new JButton();
		button_3.setMargin(new Insets(2, 2, 2, 2));
		button_3.setIconTextGap(2);
		button_3.setBounds(136, 66, 84, 25);
		button_3.setText("<- assign");
		getContentPane().add(button_3);

		final JButton button_4 = new JButton();
		button_4.setMargin(new Insets(2, 2, 2, 2));
		button_4.setBounds(135, 290, 79, 25);
		button_4.setText("remove ->");
		getContentPane().add(button_4);

		final JButton button_5 = new JButton();
		button_5.setMargin(new Insets(2, 2, 2, 2));
		button_5.setBounds(370, 65, 79, 25);
		button_5.setText("assign ->");
		getContentPane().add(button_5);

		final JButton button_6 = new JButton();
		button_6.setMargin(new Insets(2, 2, 2, 2));
		button_6.setBounds(370, 290, 79, 25);
		button_6.setText("<- remove");
		getContentPane().add(button_6);

		final JLabel userAndGroupLabel = new JLabel();
		userAndGroupLabel.setBounds(0, 0, 600, 15);
		userAndGroupLabel.setText("User and Group Permissions for " + path);
		getContentPane().add(userAndGroupLabel);

		final JLabel viewPermissionsLabel = new JLabel();
		viewPermissionsLabel.setBounds(8, 44, 122, 20);
		viewPermissionsLabel.setText("View Permissions");
		getContentPane().add(viewPermissionsLabel);

		final JLabel usersLabel = new JLabel();
		usersLabel.setBounds(267, 44, 69, 20);
		usersLabel.setText("Users");
		getContentPane().add(usersLabel);

		final JLabel modifyPermissionsLabel = new JLabel();
		modifyPermissionsLabel.setBounds(462, 44, 132, 20);
		modifyPermissionsLabel.setText("Modify Permissions");
		getContentPane().add(modifyPermissionsLabel);

		final JSeparator separator_3 = new JSeparator();
		separator_3.setBounds(0, 20, 576, 20);
		getContentPane().add(separator_3);
	}

	public void actionPerformed(ActionEvent e) {
		// TODO Auto-generated method stub
		
	}

	

}
