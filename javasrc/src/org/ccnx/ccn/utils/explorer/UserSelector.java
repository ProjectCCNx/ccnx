package org.ccnx.ccn.utils.explorer;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.logging.Level;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.namespace.NamespaceManager;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.protocol.ContentName;

public class UserSelector extends JDialog implements ActionListener {

	private static final long serialVersionUID = 1L;

	public static final String [] USER_NAMES = {"Alice", "Bob", "Carol", "Dave", "Oswald", "Binky",
		"Spot", "Fred", "Eve", "Harold", "Barack", "Newt",
		"Allison", "Zed", "Walter", "Gizmo", "Nick", "Michael",
		"Nathan", "Rebecca", "Diana", "Jim", "Van", "Teresa",
		"Russ", "Tim", "Sharon", "Jessica", "Elaine", "Mark",
		"Weasel", "Ralph", "Junior", "Beki", "Darth", "Cauliflower",
		"Pico", "Eric", "Eric", "Eric", "Erik", "Richard"};
	
	ContentName userStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = ContentName.fromNative(UserConfiguration.defaultNamespace(), "Groups");
	
	private static int nbUsers;
	private JButton[] userButton;
	private static ContentName root;
	private static String userConfigDirBase = null;
	private static GroupAccessControlManager gacm = null;
	
	public UserSelector() {

		super();
		setTitle("User selector");
		getContentPane().setLayout(null);
		setBounds(100, 100, 250, 300);
		
		JLabel pleaseSelect = new JLabel();
		pleaseSelect.setText("Please select a user: ");
		pleaseSelect.setBounds(10, 10, 150, 20);
		getContentPane().add(pleaseSelect);
		
		userButton = new JButton[nbUsers];
		
		for (int i=0; i<nbUsers; i++) {
			userButton[i] = new JButton();
			userButton[i].setText(USER_NAMES[i]);
			userButton[i].addActionListener(this);
			userButton[i].setBounds(10, 60 + 30*i, 200, 20);
			getContentPane().add(userButton[i] );
		}

		setVisible(true);
	}
	
	public void actionPerformed(ActionEvent e) {
		
		for (int i=0; i<nbUsers; i++) {
			if (userButton[i] == e.getSource()) {
				setUser(USER_NAMES[i]);
				break;
			}
		}
		
		// start the content explorer
		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				ContentExplorer.setRoot(root);
				ContentExplorer.setAccessControl(true);
				ContentExplorer.createAndShowGUI();
			}
		});
		
		// dispose of the user selector
		this.setVisible(false);
		this.dispose();
	}
	
	public static void usage() {
		System.out.println("usage: UserSelector -f <file directory for keystores> <user count>");
	}
	
	public static void main(String[] args) {
		Log.setDefaultLevel(Level.WARNING);
		
		if (args.length < 3) {
			usage();
			return;
		}
		
		if (! args[0].equals("-f")) {
			usage();
			return;
		}
		
		userConfigDirBase = args[1];
		nbUsers = Integer.parseInt(args[2]);
				
		root = null;
		try {
			root = ContentName.fromNative("/");
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		new UserSelector();		
	}
	
	private void setUser(String userName) {		
		// Note: the user configuration directory must be set before any handle or group manager is created.
		File userDirectory = new File(userConfigDirBase, userName);
		String userConfigDir = userDirectory.getAbsolutePath();
		System.out.println("User configuration directory: " + userConfigDir);
		UserConfiguration.setUserConfigurationDirectory(userConfigDir);
		UserConfiguration.setUserName(userName);
		try{
			UserConfiguration.setUserNamespacePrefix("/ccnx.org/Users");
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		// create root ACL if it does not already exist
		try{
			ContentName baseNode = ContentName.fromNative("/");
			CCNHandle handle = CCNHandle.open();
			gacm = new GroupAccessControlManager(baseNode, groupStorage, userStorage, handle);
			gacm.getEffectiveACLObject(baseNode).acl();
		}
		catch (IllegalStateException ise) {
			System.out.println("The repository has no root ACL.");
			System.out.println("Attempting to create missing root ACL with user " + userName + " as root manager.");
			ContentName cn = ContentName.fromNative(userStorage, userName);
			Link lk = new Link(cn, ACL.LABEL_MANAGER, null);
			ArrayList<Link> rootACLcontents = new ArrayList<Link>();
			rootACLcontents.add(lk);
			ACL rootACL = new ACL(rootACLcontents);
			try{
				gacm.initializeNamespace(rootACL);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		
		// create and set the group access control manager
		System.out.println("Setting user: " + userName);
		try {
			ContentName baseNode = ContentName.fromNative("/");
			CCNHandle handle = CCNHandle.open();
			gacm = new GroupAccessControlManager(baseNode, groupStorage, userStorage, handle);
			NamespaceManager.registerACM(gacm);
			ContentName myIdentity = ContentName.fromNative(userStorage, userName);
			gacm.publishMyIdentity(myIdentity, handle.keyManager().getDefaultPublicKey());
			System.out.println(myIdentity);
			System.out.println(gacm.haveIdentity(myIdentity));
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		ContentExplorer.setGroupAccessControlManager(gacm);		
	}

}
