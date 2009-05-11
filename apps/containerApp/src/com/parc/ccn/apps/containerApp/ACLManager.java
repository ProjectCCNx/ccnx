package com.parc.ccn.apps.containerApp;

import java.awt.EventQueue;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.ListSelectionModel;
import javax.swing.border.BevelBorder;

import com.parc.ccn.data.ContentName;

public class ACLManager extends JDialog implements ActionListener{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private JList userList;
	private JList readWriteList;
	private JList readOnlyList;

	private JButton buttonApply;
	private JButton buttonDefault;
	private JButton buttonCancel;
	private JButton buttonAssignReadOnly;
	private JButton buttonRemoveReadOnly;
	private JButton buttonAssingReadWrite;
	private JButton buttonRemoveReadWrite;
	
	private JButton buttonModify2View;
	private JButton buttonView2Modify;	
	private JFrame frame;
	private String path;
private ArrayList<JList> listsArray=null;	
	//private ArrayList<String> readOnlyPrincipals;
	//private ArrayList<String> readWritePrincipals;
private ValuesChanged changedEntries;

private SortedListModel readOnlyPrincipals = null;
private SortedListModel readWritePrincipals = null;
private SortedListModel userPool = null;

//Default Items
private SortedListModel readOnlyPrincipalsDefault = null;
private SortedListModel readWritePrincipalsDefault = null;
private SortedListModel userPoolDefault = null;

/**
	 * Launch the application
 * @param path2 
	 * @param args
	 */
	
	//Get the list of users
	public String[] getUserList(String path2,String permissions)
	{
		if(permissions ==null){
		
			//get all users/groups
			String principals[] = {"Paulo","Noel","Eliza","Chico","Alex","Aaron","Kelly"};
			return principals;			
		}
		else {
			//Use the path to look up the permissions
			if(permissions.equalsIgnoreCase("read"))
			{
				String principals[] = {"Mary","Cathy","Janet","Tracy","Natasha","Natalie","Tanya"};
				return principals;
			}
			else if (permissions.equalsIgnoreCase("write"))
			{
				String principals[] = {"Jim","Fred","Bob","Frank","Matsumoto","Jorge","Frederick","Jane"};
				return principals;
			}
		
		}
		String empty[] ={""};
		return empty;
	}

//TODO Pending Changes Modal Dialog on close
//TODO Add or remove between lists across the screen (from read only to read/write and not just users)
//TODO have only one thing selected at a time
	//TODO Buttons (Apply, Cancel, Default)
	//On Close or Apply do this
	//Applies new permissions to User Items
	
	public void setACLChanges()
	{
		
	}
	
	public void setReadOnlyUsersList()
	{
		// grab the content object
		// apply the changes to the object
		
	}
	
	//On Close or Apply do this
	//Applies new permissions to User Items
	public void setReadWriteUsersList()
	{
		// grab the content object
		// apply the changes to the object		
	}
	
	/**
	 * Create the dialog
	 * @param path 
	 */
	public ACLManager(String path) {

		super();
		this.path = path;
		
		//window listener
		this.setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		changedEntries = new ValuesChanged(false);
		this.addWindowListener(new ChangedEntriesConfirm(this,changedEntries));
		
		listsArray = new ArrayList<JList>();
		//listsArray.add(groupsList);
		
		readOnlyPrincipals = new SortedListModel();
		readWritePrincipals = new SortedListModel();
		userPool = new SortedListModel();
		
		readOnlyPrincipalsDefault = new SortedListModel();
		readWritePrincipalsDefault = new SortedListModel();
		userPoolDefault = new SortedListModel();
		
		//List Models for the List Objects
		userPool.addAll(getUserList(path,null));
		readOnlyPrincipals.addAll(getUserList(path,"read"));
		readWritePrincipals.addAll(getUserList(path,"write"));
		
		//Default List Items
		userPoolDefault.addAll(getUserList(path,null));
		readOnlyPrincipalsDefault.addAll(getUserList(path,"read"));
		readWritePrincipalsDefault.addAll(getUserList(path,"write"));
		
		setBounds(100, 100, 615, 442);
		setTitle("Manage Access Controls for "+path);
		getContentPane().setLayout(null);
				
		buttonApply = new JButton();
		buttonApply.addActionListener(this);
		buttonApply.setBounds(73, 367, 141, 25);
		buttonApply.setText("Apply Changes");
		getContentPane().add(buttonApply);

		buttonDefault = new JButton();
		buttonDefault.addActionListener(this);
		buttonDefault.setBounds(261, 367, 123, 25);
		buttonDefault.setText("Revert Default");
		getContentPane().add(buttonDefault);

		buttonCancel = new JButton();
		buttonCancel.addActionListener(this);
		buttonCancel.setBounds(421, 367, 133, 25);
		buttonCancel.setText("Cancel Changes");
		getContentPane().add(buttonCancel);

		final JScrollPane scrollPane_1 = new JScrollPane();
		scrollPane_1.setBounds(304, 209, 278, 106);
		getContentPane().add(scrollPane_1);
		
		readWriteList = new JList(readWritePrincipals);
		readWriteList.setName("modifyList");
		scrollPane_1.setViewportView(readWriteList);
		readWriteList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		readWriteList.setBorder(new BevelBorder(BevelBorder.LOWERED));
		
		listsArray.add(readWriteList);
		//readWriteList.setBounds(462, 70, 120, 240);		
		//getContentPane().add(readWriteList);

		
		final JScrollPane scrollPane_2 = new JScrollPane();
		scrollPane_2.setBounds(8, 70, 169, 255);
		getContentPane().add(scrollPane_2);
		
		userList = new JList(userPool);
		userList.setName("userList");
		scrollPane_2.setViewportView(userList);
		userList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		userList.setBorder(new BevelBorder(BevelBorder.LOWERED));
		
		listsArray.add(userList);
//		userList.setBounds(233, 70, 120, 240);
//		getContentPane().add(userList);

		buttonAssignReadOnly = new JButton();
		buttonAssignReadOnly.addActionListener(this);
		buttonAssignReadOnly.setMargin(new Insets(2, 2, 2, 2));
		buttonAssignReadOnly.setIconTextGap(2);
		buttonAssignReadOnly.setBounds(194, 69, 79, 25);
		buttonAssignReadOnly.setText("assign ->");
		getContentPane().add(buttonAssignReadOnly);

		buttonRemoveReadOnly = new JButton();
		buttonRemoveReadOnly.addActionListener(this);
		buttonRemoveReadOnly.setMargin(new Insets(2, 2, 2, 2));
		buttonRemoveReadOnly.setBounds(194, 116, 79, 25);
		buttonRemoveReadOnly.setText("<- remove");
		getContentPane().add(buttonRemoveReadOnly);

		buttonAssingReadWrite = new JButton();
		buttonAssingReadWrite.addActionListener(this);
		buttonAssingReadWrite.setMargin(new Insets(2, 2, 2, 2));
		buttonAssingReadWrite.setBounds(194, 225, 79, 25);
		buttonAssingReadWrite.setText("assign ->");
		getContentPane().add(buttonAssingReadWrite);

		buttonRemoveReadWrite = new JButton();
		buttonRemoveReadWrite.addActionListener(this);
		buttonRemoveReadWrite.setMargin(new Insets(2, 2, 2, 2));
		buttonRemoveReadWrite.setBounds(194, 291, 79, 25);
		buttonRemoveReadWrite.setText("<- remove");
		getContentPane().add(buttonRemoveReadWrite);

		final JLabel userAndGroupLabel = new JLabel();
		userAndGroupLabel.setBounds(0, 0, 600, 15);
		userAndGroupLabel.setText("User and Group Permissions for " + path);
		getContentPane().add(userAndGroupLabel);

		final JLabel usersLabel = new JLabel();
		usersLabel.setBounds(8, 44, 123, 20);
		usersLabel.setText("Users/Groups");
		getContentPane().add(usersLabel);

		final JLabel modifyPermissionsLabel = new JLabel();
		modifyPermissionsLabel.setBounds(304, 321, 132, 20);
		modifyPermissionsLabel.setText("Modify Permissions");
		getContentPane().add(modifyPermissionsLabel);

		final JSeparator separator_3 = new JSeparator();
		separator_3.setBounds(0, 20, 576, 20);
		getContentPane().add(separator_3);

		final JLabel viewPermissionsLabel = new JLabel();
		viewPermissionsLabel.setBounds(304, 44, 122, 20);
		viewPermissionsLabel.setText("View Permissions");
		getContentPane().add(viewPermissionsLabel);
		
		final JScrollPane scrollPane = new JScrollPane();
		scrollPane.setBounds(304, 70, 278, 106);
		getContentPane().add(scrollPane);

		readOnlyList = new JList(readOnlyPrincipals);
		readOnlyList.setName("viewList");
		scrollPane.setViewportView(readOnlyList);
		readOnlyList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		readOnlyList.setBorder(new BevelBorder(BevelBorder.LOWERED));

		listsArray.add(readOnlyList);
		
		buttonModify2View = new JButton();
		buttonModify2View.addActionListener(this);
		buttonModify2View.setText("^");
		buttonModify2View.setBounds(349, 178, 55, 25);
		getContentPane().add(buttonModify2View);

		buttonView2Modify = new JButton();
		buttonView2Modify.addActionListener(this);
		buttonView2Modify.setText("v");
		buttonView2Modify.setBounds(479, 178, 49, 25);
		getContentPane().add(buttonView2Modify);
		
		readOnlyList.addMouseListener(new ListMouseListener(listsArray));
		readWriteList.addMouseListener(new ListMouseListener(listsArray));
		userList.addMouseListener(new ListMouseListener(listsArray));
	}

	public void actionPerformed(ActionEvent e) {
		// TODO Auto-generated method stub
		if(buttonApply == e.getSource()) {
			
			applyChanges();
			changedEntries.changed = false;
			
		}else if(buttonDefault == e.getSource()){
			
			restoreDefaults();
			changedEntries.changed = false;
			
		}else if(buttonCancel == e.getSource()){
			
			cancelChanges();
			
			
		}else if(buttonAssignReadOnly == e.getSource()){
			moveListItems(userList.getSelectedIndices(),userList,readOnlyList);
			changedEntries.changed = true;
			
		}else if(buttonRemoveReadOnly == e.getSource()){			
			moveListItems(readOnlyList.getSelectedIndices(),readOnlyList,userList);			
			changedEntries.changed = true;
		}else if(buttonAssingReadWrite == e.getSource()){
			moveListItems(userList.getSelectedIndices(),userList,readWriteList);
			changedEntries.changed = true;
		}else if(buttonRemoveReadWrite == e.getSource()){
			moveListItems(readWriteList.getSelectedIndices(),readWriteList,userList);
			changedEntries.changed = true;
		}else if(buttonModify2View == e.getSource()){
			
			moveListItems(readWriteList.getSelectedIndices(),readWriteList,readOnlyList);
			changedEntries.changed = true;
			
		}else if(buttonView2Modify == e.getSource()){
			
			moveListItems(readOnlyList.getSelectedIndices(),readOnlyList,readWriteList);
			changedEntries.changed = true;
		}
		
	}
	
	private void applyChanges() {
		// TODO Take all the elements and assign them new acls somehow
		
		
	}

	private void restoreDefaults() {

		//Clear out the old model
		userPool.clear();
		readOnlyPrincipals.clear();
		readWritePrincipals.clear();
		
		//In with the new
		userPool.addAll(userPoolDefault.getAllElements());
		readOnlyPrincipals.addAll(readOnlyPrincipalsDefault.getAllElements());
		readWritePrincipals.addAll(readWritePrincipalsDefault.getAllElements());
		
	}
	
	private void cancelChanges()
	{
		if(changedEntries.changed)
		{
			int answer = JOptionPane.showConfirmDialog(this, "You have pending changes. Are you sure you would like to exit", "Pending Changes",JOptionPane.YES_NO_OPTION);
			switch(answer){
			case JOptionPane.YES_OPTION:
				this.setVisible(false);
				this.dispose();
				break;
			case JOptionPane.NO_OPTION:
				break;
			}
			
		}else
		{
			this.setVisible(false);
			this.dispose();
		}
	}

	private void moveListItems(int selectedIndices[],JList fromList,JList toList)
	{
		//ArrayList of selected items
		ArrayList<Object> itemsSelected = new ArrayList<Object>();
		
		if(selectedIndices.length >= 1){
		for(int i=0;i<selectedIndices.length;i++)
		{
			//remove item from fromList and move to toList
			System.out.println("Index is "+ i+ "selected Index is"+selectedIndices[i]);
			Object selectedItem = fromList.getModel().getElementAt(selectedIndices[i]);
			itemsSelected.add(selectedItem);			
			
		}
		
		//Bulk adding and removal of items
		((SortedListModel)toList.getModel()).addAll(itemsSelected.toArray());		
		((SortedListModel)fromList.getModel()).removeElementArray(itemsSelected);		

		//clear selections from old items
		fromList.clearSelection();
		//select new items?
		//toList.setSelectedIndices(indices);
		}		
		
	}
}
