/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2010, Palo Alto Research Center, Inc.
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
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.utils.CreateUserData;

public class UserSelector extends JDialog implements ActionListener {

	private static final long serialVersionUID = -1950067955162414507L;
	
	ContentName userStorage = new ContentName(UserConfiguration.defaultNamespace(), "Users");
	ContentName groupStorage = new ContentName(UserConfiguration.defaultNamespace(), "Groups");
	
	private int _nbUsers;
	private JButton[] _userButton;
	private ContentName _root;
	private File _userConfigDir = null;
	private GroupAccessControlManager _gacm = null;
	private String [] _userNames;
	private boolean _publishIdentity;
	
	public UserSelector(File userConfigDir, int numUsers, ContentName root, boolean publishIdentity) {

		super();
		
		_userConfigDir = userConfigDir;
		_nbUsers = numUsers;
		_root = root;
		_publishIdentity = publishIdentity;
		
		_userNames = getUserNames(_userConfigDir);
		if (_userNames.length > _nbUsers) {
			Log.warning("Cannot load {0} users, only {1} available.", _nbUsers, _userNames);
			_nbUsers = _userNames.length;
		}
		
		setTitle("User selector");
		getContentPane().setLayout(null);
		setBounds(100, 100, 250, 300);
		
		JLabel pleaseSelect = new JLabel();
		pleaseSelect.setText("Please select a user: ");
		pleaseSelect.setBounds(10, 10, 150, 20);
		getContentPane().add(pleaseSelect);
		
		_userButton = new JButton[_nbUsers];
		
		for (int i=0; i<_nbUsers; i++) {
			_userButton[i] = new JButton();
			_userButton[i].setText(CreateUserData.USER_NAMES[i]);
			_userButton[i].addActionListener(this);
			_userButton[i].setBounds(10, 60 + 30*i, 200, 20);
			getContentPane().add(_userButton[i] );
		}

		setVisible(true);
	}
	
	public void actionPerformed(ActionEvent e) {
		
		for (int i=0; i<_nbUsers; i++) {
			if (_userButton[i] == e.getSource()) {
				setUser(CreateUserData.USER_NAMES[i]);
				break;
			}
		}
		
		// start the content explorer
		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				ContentExplorer.setRoot(_root);
				ContentExplorer.setAccessControl(true);
				ContentExplorer.setShowVersions(true);
				ContentExplorer.createAndShowGUI();
			}
		});
		
		// dispose of the user selector
		this.setVisible(false);
		this.dispose();
	}
	
	public static String [] getUserNames(File directory) {
		
		if ((null == directory) || (!directory.exists()) || (!directory.isDirectory())) {
			Log.severe("Cannot load users from non-existent directory {0}!", 
					((null == directory) ? "null" : directory.getAbsolutePath()));
			return new String[0]; // return no names, will pop up empty list. may want to do something more sensible.
		}
		return directory.list();
	}
	
	public static void usage() {
		System.out.println("usage: UserSelector [-n] -f <file directory for keystores> <user count>");
	}
	
	public static void main(String[] args) {
		Log.setDefaultLevel(Level.FINEST);
		
		boolean publish = true;
		
		int offset = 0;
		if ((args.length > 1) && (args[offset].equals("-n"))) {
			publish = false;
			offset++;
		}
		
		if (args.length-offset < 3) {
			usage();
			return;
		}
		
		if (! args[offset++].equals("-f")) {
			usage();
			return;
		}
		
		File userConfigDirBase = new File(args[offset++]);
		int nbUsers = Integer.parseInt(args[offset++]);
				
		ContentName root = null;
		try {
			root = ContentName.fromNative("/");
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		new UserSelector(userConfigDirBase, nbUsers, root, publish);		
	}
	
	private void setUser(String userName) {		
		// Note: the user configuration directory must be set before any handle or group manager is created.
		File userDirectory = new File(_userConfigDir, userName);
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
			ContentName baseNode = ContentName.ROOT;
			CCNHandle handle = CCNHandle.open();
			_gacm = new GroupAccessControlManager(baseNode, groupStorage, userStorage, handle);
			// Have to the user first, otherwise we can't make a root acl...
			
			System.out.println("Setting user: " + userName);
			ContentName myIdentity = new ContentName(userStorage, userName);
			if (_publishIdentity) {
				_gacm.publishMyIdentity(myIdentity, handle.keyManager().getDefaultPublicKey());
			}
			System.out.println(myIdentity);
			System.out.println(_gacm.haveIdentity(myIdentity));
			
			_gacm.getEffectiveACLObject(baseNode).acl();
			
			// ACM is ready to use
			handle.keyManager().rememberAccessControlManager(_gacm);
			
		} catch (IllegalStateException ise) {
			System.out.println("The repository has no root ACL.");
			System.out.println("Attempting to create missing root ACL with user " + userName + " as root manager.");
			ContentName cn = new ContentName(userStorage, userName);
			Link lk = new Link(cn, ACL.LABEL_MANAGER, null);
			ArrayList<Link> rootACLcontents = new ArrayList<Link>();
			rootACLcontents.add(lk);
			ACL rootACL = new ACL(rootACLcontents);
			try{
				_gacm.initializeNamespace(rootACL);
			} catch (Exception e) {
				e.printStackTrace();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		// Here is where we'd want to drop in the ability to run different programs,
		// or maybe just have another program run the UserSelector first and then do its own thing.
		ContentExplorer.setGroupAccessControlManager(_gacm);	
		ContentExplorer.setUsername(userName);
		ContentExplorer.setPreviewTextfiles(false);
	}

}
