/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008, 2009, 2012 Palo Alto Research Center, Inc.
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

import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Set;
import java.util.logging.Level;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * The ContentExplorer is an experimental app still under development. This
 * application explores ContentObjects that are available in a GUI. The
 * ContentExplorer uses CCNNameEnumeration to populate the GUI and can open .txt
 * and .text files in a preview pane or separate window. The ContentExplorer can
 * also be used to store files in a repository. Finally, the ContentExplorer is
 * intended to be used as a first test of AccessControl functionality with CCN.
 * This is in an extremely early state and will be updated in future releases.
 *
 */
public class ContentExplorer extends JFrame implements BasicNameEnumeratorListener, ActionListener {

	private static ContentName root;

	private static boolean accessControlOn = false;
	protected static boolean showVersions = false;
	protected static boolean debugMode = false;
	private static GroupAccessControlManager gacm = null;
	private static String userName = null;
	private static boolean previewTextFiles = true;

	private CCNNameEnumerator _nameEnumerator = null;
	protected static CCNHandle _handle = null;
	private static boolean useSystemLookAndFeel = false;

	private static final long serialVersionUID = 1L;

	static java.net.URL netURL = ContentExplorer.class.getResource("Network.png");
	public static final ImageIcon ICON_COMPUTER = new ImageIcon(getScaledImage(
			(new ImageIcon(netURL)).getImage(), 32, 32));

	static java.net.URL compURL = ContentExplorer.class.getResource("Computer.png");
	public static final ImageIcon ICON_DISK = new ImageIcon(getScaledImage(
			(new ImageIcon(compURL)).getImage(), 32, 32));

	static java.net.URL imageURL = ContentExplorer.class.getResource("Folder.png");
	public static final ImageIcon ICON_FOLDER = new ImageIcon(getScaledImage(
			(new ImageIcon(imageURL)).getImage(), 32, 32));

	static java.net.URL docURL = ContentExplorer.class.getResource("Document.png");
	public static final ImageIcon ICON_EXPANDEDFOLDER = new ImageIcon(getScaledImage(
			(new ImageIcon(docURL)).getImage(), 32, 32));

	public static final ImageIcon ICON_DOCUMENT = new ImageIcon(getScaledImage(
			(new ImageIcon(docURL)).getImage(), 32, 32));

	private final JEditorPane htmlPane;
	public String selectedPrefix;
	public String selectedPath;
	protected JTree tree;
	protected DefaultTreeModel m_model;
	//protected JTextField m_display;
	private final JTextField textArea;

	private JButton openACL = null;
	private JButton openGroup = null;

	private ContentName currentPrefix = null;

	private DefaultMutableTreeNode usableRoot = null;

	private DirExpansionListener dirExpansionListener = null;
	private DirSelectionListener dirSelectionListener = null;

	protected JPopupMenu tree_popup;
	protected Action tree_popupaction;

	protected boolean vlcSupported = false;

	/**
	 * Constructor for ContentExplorer application.  This sets up the swing elements and listeners for the GUI.
	 * The constructor also initializes the CCNHandle and name enumeration.
	 */
	public ContentExplorer() {
		super("CCN Content Explorer");
		if (userName != null) this.setTitle("CCN Content Explorer for " + userName);

		//vlcSupported = checkVLCsupport();

		setupNameEnumerator();

		setSize(400, 300);

		ContentName slash = null;
		slash = ContentName.ROOT;

		DefaultMutableTreeNode top = new DefaultMutableTreeNode(new IconData(
				ICON_FOLDER, null, new Name(slash.component(0), null, true)));

		DefaultMutableTreeNode node = top;
		DefaultMutableTreeNode newNode = null;
		// add each component of the root
		Log.fine("root = " + root.toString());
		for (int i = 0; i < root.count(); i++) {
			Log.fine("adding component: " + root.stringComponent(i));
			// add each component to the tree
			newNode = new DefaultMutableTreeNode(new IconData(ICON_FOLDER,
					null, new Name(root.component(i), root.cut(i), true)));
			node.add(newNode);
			node = newNode;
		}

		usableRoot = top;

		m_model = new DefaultTreeModel(top);
		tree = new JTree(m_model);

		tree.putClientProperty("JTree.lineStyle", "Angled");

		TreeCellRenderer renderer = new IconCellRenderer();
		tree.setCellRenderer(renderer);

		dirExpansionListener = new DirExpansionListener();
		tree.addTreeExpansionListener(dirExpansionListener);
		dirSelectionListener = new DirSelectionListener();
		tree.addTreeSelectionListener(dirSelectionListener);

		tree_popup = new JPopupMenu();
		tree_popupaction = new AbstractAction() {

			private static final long serialVersionUID = 9114007083621952181L;

			public void actionPerformed(ActionEvent e){
				TreePath p = (TreePath)(tree_popupaction.getValue("PATH"));
				if(p==null) {
					//System.err.println("path is null");
					return;
				}
				else if(tree.isExpanded(p)) {
					tree.collapsePath(p);
				}
				else {
					if (e.getActionCommand().equals("Expand")) {
						TreeExpansionEvent t = new TreeExpansionEvent(tree, p);
						dirExpansionListener.treeExpanded(t);
						tree.expandPath(p);
					} else if (e.getActionCommand().equals("Select")) {
						tree.setSelectionPath(p);

					}
				}

			}
		};
		tree_popup.add(tree_popupaction);
		tree_popup.addSeparator();


		Action displayName = new AbstractAction("Display Full Prefix") {

			private static final long serialVersionUID = 6373543410642021178L;

			public void actionPerformed(ActionEvent e){
				tree.repaint();

				TreePath p = (TreePath)(tree_popupaction.getValue("PATH"));

				Name node = getNameNode((DefaultMutableTreeNode) p.getLastPathComponent());

				ContentName n = null;

				if(node.name == null && node.path == null)
					n = new ContentName();
				else
					n = new ContentName(node.path, node.name);

				htmlPane.setText(n.toString());
			}
		};

		tree_popup.add(displayName);

		Action saveFile = new AbstractAction("Save File") {

			private static final long serialVersionUID = -3770094703319020441L;

			public void actionPerformed(ActionEvent e){
				tree.repaint();

				TreePath p = (TreePath)(tree_popupaction.getValue("PATH"));

				Name node = getNameNode((DefaultMutableTreeNode) p.getLastPathComponent());

				ContentName name = null;

				if(node.name == null && node.path == null)
					name = new ContentName();
				else
					name = new ContentName(node.path, node.name);
				LocalSaveContentRetriever localsave = new LocalSaveContentRetriever(_handle, name, htmlPane);
				Thread t = new Thread(localsave);
				t.start();

			}
		};

		tree_popup.add(saveFile);

		Action playFile = new AbstractAction("Play File") {

			private static final long serialVersionUID = -2932828512965050415L;

			public void actionPerformed(ActionEvent e){
				tree.repaint();
				htmlPane.setText("play file with VLC not implemented yet");

			}
		};
		if(vlcSupported)
			tree_popup.add(playFile);

		Action showVersions = new AbstractAction("Show Versions") {

			private static final long serialVersionUID = -827879841202976452L;

			public void actionPerformed(ActionEvent e){
				tree.repaint();
				htmlPane.setText("display versions of file not implemented yet");
				String toDisplay = "";
				TreePath p = (TreePath)(tree_popupaction.getValue("PATH"));

				Name node = getNameNode((DefaultMutableTreeNode) p.getLastPathComponent());
				Set<ContentName> versions = node.getVersions();
				synchronized(versions) {
					for(ContentName c: versions) {
						toDisplay = toDisplay+c.toString()+"\n";
					}
				}

				if (toDisplay.equals("")) {
					htmlPane.setText("Version numbers are currently not available for this name. \nThis can occur if the node was not previously selected.");
				} else {
					htmlPane.setText(toDisplay);
				}
			}
		};

		tree_popup.add(showVersions);

		tree.add(tree_popup);

		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.setShowsRootHandles(true);
		tree.setEditable(false);

		String filename = "~/";
		JFileChooser fc = new JFileChooser(new File(filename));

		class OpenFileAction extends AbstractAction {

			private static final long serialVersionUID = 1L;
			JFrame frame;

			JFileChooser chooser;

			OpenFileAction(JFrame frame, JFileChooser chooser) {
				super("Send to Repo...");
				this.chooser = chooser;
				this.frame = frame;
			}

			public void actionPerformed(ActionEvent evt) {

				// if we don't have a file then we should show a file chooser
				// otherwise give an error message "Please select a folder"

				//commented out this behavior.  we have many things that do have a . in them.  also
				//when showing versions, they may have characters that are interpreted as a .

				/*if (((selectedPrefix.toString()).split("\\.")).length > 2) {
				*	JOptionPane.showMessageDialog(this.frame,
				*			"Please Select a Directory to add a file",
				  *			"Select Directory", JOptionPane.ERROR_MESSAGE);
				 * } else {
				 *
				 */
					// Show dialog; this method does not return until dialog is
					// closed

					int returnVal = chooser.showOpenDialog(frame);

					// Get the selected file
					File file = chooser.getSelectedFile();
					if (file == null || file.getName().equals("")) {
						Log.fine("the user did not select a file");
						return;
					}

					// what if the user hits cancel...
					if (returnVal != JFileChooser.APPROVE_OPTION) {
						Log.fine("user cancelled the send to repo option...  returning");
						return;
					}

					Log.info("Writing a file to the repo " + file.getAbsolutePath() + " " + file.getName());
					Log.fine("Selected Node is " + selectedPrefix);

					try {
						ContentName contentName = ContentName.fromURI(selectedPrefix).append(file.getName());
						ContentName temp = null;
						while (temp==null) {
							String name = JOptionPane.showInputDialog("Send File to Repo As:", contentName.toString());
							if (name == null) {
								Log.fine("user selected cancel, returning");
								return;
							}

							Log.info("user entered [{0}]", name);
							//System.out.println("user entered ["+name+"]");


							try {
								if (name.startsWith("ccnx:/"))
									name = name.replaceFirst("ccnx:/", "/");
								temp = ContentName.fromURI(name);
								//temp = ContentName.fromNative(name);
								contentName = temp;
								Log.info("saving as [{0}]", contentName);
								//System.out.println("saving as ["+contentName+"]");

							}
							catch (Exception e) {
								Log.fine("User entered invalid name for save: {0}", e.getMessage());
								if(name.equals(""))
									JOptionPane.showMessageDialog(chooser, "Please enter a CCNx name for the content that starts with \"/\".");
								else
									JOptionPane.showMessageDialog(chooser, (name + " is not a valid CCNx name.  Please be sure it starts with \"/\""));
							}
						}

						sendFile(file, contentName);
					} catch (MalformedContentNameStringException e) {
						Log.logException("could not create content name for selected file: "+file.getName(), e);
					//}
				}
			}
		}
		;

		Action openAction = new OpenFileAction(this, fc);
		JButton openButton = new JButton(openAction);

		openACL = new JButton("Manage Permissions");
		openACL.addActionListener(this);

		openGroup = new JButton("Open Group Manager");
		openGroup.addActionListener(this);

		// Create the scroll pane and add the tree to it.
		JScrollPane treeView = new JScrollPane(tree);

		// Create the HTML viewing pane.
		htmlPane = new JEditorPane();
		htmlPane.setEditable(false);
		initHelp();
		JScrollPane htmlView = new JScrollPane(htmlPane);

		textArea = new JTextField();
		textArea.setEditable(false);
		textArea.setText("here is where names will appear");

		//Component c1 = new ExplorerPanel();
		//Component c2 = new TextPanel();

		// Add the scroll panes to a split pane.
		JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
		splitPane.setContinuousLayout(true);
		splitPane.setOneTouchExpandable(true);
		splitPane.setTopComponent(treeView);
		splitPane.setBottomComponent(htmlView);
		//splitPane.setBottomComponent(textArea);
		Dimension minimumSize = new Dimension(100, 50);
		htmlView.setMinimumSize(minimumSize);
		treeView.setMinimumSize(minimumSize);
		splitPane.setDividerLocation(250);
		splitPane.setPreferredSize(new Dimension(500, 300));

		setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();

		c.fill = GridBagConstraints.BOTH;
		c.gridx = 0;
		c.gridy = 1;
		if (accessControlOn)
			getContentPane().add(openACL, c);

		c.fill = GridBagConstraints.BOTH;
		c.gridx = 1;
		c.gridy = 1;
		if (accessControlOn)
			getContentPane().add(openGroup, c);

		c.fill = GridBagConstraints.BOTH;
		c.gridwidth = 2;
		c.gridx = 0;
		c.gridy = 0;
		getContentPane().add(openButton, c);

		c.weightx = 1;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		c.gridwidth = 2;
		c.gridx = 0;
		c.gridy = 2;
		getContentPane().add(splitPane, c);

		pack();

		Log.fine("setting selectionPath: " + node.getPath()+ " node: " + node.toString());
		tree.expandPath(new TreePath(node.getPath()));
		tree.setSelectionPath(new TreePath(node.getPath()));

		tree.addMouseListener(new MouseActions());

		WindowListener wndCloser = new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		};
		addWindowListener(wndCloser);

		setVisible(true);

	}

	/**
	 * Method to trigger a thread to retrieve a file. A new thread is created to get the file.
	 * This method displays a message about the file to retrieve in the preview pane and displays
	 * the text of the file when the download is complete.
	 *
	 * @param name Name of the file to retrieve
	 * @param txtPopup True if the file should be opened in a text popup
	 */

	public void retrieveFromRepo(String name, boolean textPopup) {

		htmlPane.setText("Retrieving content... "+name);

		HTMLPaneContentRetriever retriever = new HTMLPaneContentRetriever(_handle, htmlPane, name);
		retriever.setTextPopup(textPopup);

		Thread t = new Thread(retriever);
		t.start();
	}

	/**
	 * Method to trigger a thread to retrieve a file. A new thread is created to get the file.
	 * This method displays a message about the file to retrieve in the preview pane and displays
	 * the text of the file when the download is complete.
	 *
	 * @param name Name of the file to retrieve
	 */

	public void retrieveFromRepo(String name) {
		retrieveFromRepo(name, false);
	}


	/**
	 * Method to store a file in a repository.  A new thread is created for this process.
	 * A message indicating the file the is being written displays in the preview pane.
	 * When the upload is complete, or an error occurs, the preview pane is updated to show
	 * the new state.
	 *
	 * @param file
	 * @param ccnName
	 */
	public void sendFile(File file, ContentName ccnName) {

		htmlPane.setText("Writing " + file.getName() + " to CCN as: " + ccnName);

		ContentWriter writer = new ContentWriter(_handle, ccnName, file, htmlPane);

		Thread t = new Thread(writer);
		t.start();
	}

	/**
	 * Simple instruction message to display when another file or status message is not displayed.
	 */
	private void initHelp() {
		htmlPane.setText("Please expand folder names you would like to enumerate.  You may also select text files to be displayed in this window.");
	}


	/**
	 * Method to get Swing components used for storing the name hierarchy.  Returns null if the
	 * prefix is not found.  Uses the ContentExplorer.find() method to recursively search through the tree.
	 *
	 * @param ccnContentName Prefix to retrieve in the tree.
	 * @return DefaultMutableTreeNode The node in the tree representing the supplied prefix.
	 */
	DefaultMutableTreeNode getTreeNode(ContentName ccnContentName) {
		Log.fine("handling returned names!!! prefix = "+ ccnContentName.toString());

		TreePath prefixPath = new TreePath(usableRoot);

		Log.fine("prefix path: " + prefixPath.toString());

		String[] names = new String[ccnContentName.count()];
		int ind = 0;
		for (byte[] n : ccnContentName) {
			Log.fine("adding n: "+new String(n));
			ContentName newName = new ContentName(n);
			Log.fine("newName = "+newName+" "+newName.toString().replace("/", ""));
			names[ind] = newName.toString();
			ind++;
		}

		DefaultMutableTreeNode p = find(prefixPath, 0, names);
		if(p == null)
			Log.fine("returning null could not find: "+prefixPath.toString());
		return p;
	}

	/**
	 * Method for traversing the tree to find a particular node corresponding to a name.
	 *
	 * @param parent TreePath for the node we are able to explore
	 * @param depth Current depth of the tree (int)
	 * @param names String[] representing the name we are looking for in components
	 * @return DefaultMutableTreeNode Returns the desired node or null if it is not found.
	 */
	private DefaultMutableTreeNode find(TreePath parent, int depth, String[] names) {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) parent.getLastPathComponent();
		String nodeName = node.toString().replace("/", "");
		Log.fine("check nodeName: [" + nodeName + "] node: [" + node.toString()+ "]");
		Log.fine("depth = "+depth +" names.length = "+names.length);

		if (names.length <= depth) {
			// we don't have to look far... this matches the root
			Log.fine("this is the root, and we want the root...  returning the root");

			return node;
		}

		if(node.equals(usableRoot)) {
			Log.fine("this is the usable root");
			node = findMatchingChild(parent, node, names[depth]);
			nodeName = node.toString();
			Log.fine("using root child: "+nodeName);
		}

		String nameToCheck = names[depth].replace("/","");

		Log.fine("names[depth] " + names[depth]);
		Log.fine("nameToCheck: "+nameToCheck);
		// we added an extra empty slash at the top... need to account for this

		if (nameToCheck.equals(nodeName)) {
			Log.fine("we have a match!");

			if (depth == names.length) {
				Log.fine("we are at the right depth! returning this node!");
				return node;
			} else {
				Log.fine("need to keep digging...");
				if (node.getChildCount() > 0) {
					Log.fine("we have children: "	+ node.getChildCount());
					DefaultMutableTreeNode result = null;
					if (names.length > depth + 1)
						result = findMatchingChild(parent, node, names[depth + 1]);
					if (result == null) {
						Log.fine("no matching child... returning this node");
						return node;
					} else {
						Log.fine("result was not null...  we have a matching child");
					}
					TreePath path = parent.pathByAddingChild(result);
					return find(path, depth + 1, names);
				} else {
					Log.fine("did not have any children...");
					return node;
				}
			}
		} else {
			Log.fine("not a match...");
		}

		return null;
	}

	/**
	 * Method to find a child with a specific name.
	 *
	 * @param parent TreePath for the parent we are searching
	 * @param n Node to look for children
	 * @param name Name for the child we are looking for
	 *
	 * @return DefaultMutableTreeNode Returns the child we are looking for,
	 *  or null if it does not exist.
	 */
	DefaultMutableTreeNode findMatchingChild(TreePath parent, DefaultMutableTreeNode n, String name) {
		name = name.replace("/", "");
		if (n.getChildCount() == 0)
			return null;
		else {
			DefaultMutableTreeNode c = null;
			for (int i = 0; i < n.getChildCount(); i++) {
				c = (DefaultMutableTreeNode) n.getChildAt(i);
				Log.fine("child name: " + c.toString() + " name: "	+ name);
				if (c.toString().equals(name)) {
					Log.fine("child names are equal...  returning child");
					return c;
				}
				else
					Log.fine("child names not equal");
			}
		}
		return null;
	}

	/**
	 * Method to get the DefaultMutableTreeNode for a given path.
	 *
	 * @param path TreePath for the node we are looking for
	 *
	 * @return DefaultMutableTreeNode Node we are looking for
	 */
	DefaultMutableTreeNode getTreeNode(TreePath path) {
		return (DefaultMutableTreeNode) (path.getLastPathComponent());
	}

	/**
	 * Method to get the user object as a Name from a DefaultMutableTreeNode.
	 *
	 * @param node The node we need the name of
	 * @return Name The name of the node.  Returns null if the node is null.
	 */
	Name getNameNode(DefaultMutableTreeNode node) {
		if (node == null)
			return null;
		Object obj = node.getUserObject();

		if (obj instanceof IconData)
			obj = ((IconData) obj).getObject();
		if (obj instanceof Name)
			return (Name) obj;
		else
			return null;
	}

	/**
	 * Method to get the user object as a FileNode.
	 *
	 * @param node  The node we want a FileNode from
	 * @return FileNode The user object casted to a FileNode if it is one, null otherwise.
	 */
	FileNode getFileNode(DefaultMutableTreeNode node) {
		if (node == null)
			return null;
		Object obj = node.getUserObject();
		if (obj instanceof IconData)
			obj = ((IconData) obj).getObject();
		if (obj instanceof FileNode)
			return (FileNode) obj;
		else
			return null;
	}

	/**
	 * Experimental code - not tested
	 *
	 */

	class MouseActions implements MouseListener {

		/**
		 * Class to handle mouse events.  This includes a single click to begin enumeration for folders.
		 * If the selected item is a .txt or .text file, it will preview in the lower pane.
		 * @param e MouseEvent
		 */
		public void mousePressed(MouseEvent e) {

			int selRow = tree.getRowForLocation(e.getX(), e.getY());
			TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());

			if (selRow != -1) {
				if (e.getClickCount() == 2) {
					myDoubleClick(selRow, selPath);
				}
			}

			if(e.isPopupTrigger()) {
				popup(selPath, e.getX(), e.getY(), tree.getRowForLocation(e.getX(), e.getY()));
			}

		}

		public void mouseReleased(MouseEvent e) {

			if(e.isPopupTrigger()) {
				popup(tree.getPathForLocation(e.getX(), e.getY()), e.getX(), e.getY(), tree.getRowForLocation(e.getX(), e.getY()));
			}
		}


		public void mouseClicked(MouseEvent e) {

			if(e.isPopupTrigger()) {
				popup(tree.getPathForLocation(e.getX(), e.getY()), e.getX(), e.getY(), tree.getRowForLocation(e.getX(), e.getY()));
			}

		}

		private void popup(TreePath selPath, int x, int y, int row){
			tree_popupaction.putValue("PATH", selPath);
			if(selPath!=null) {
				if(tree.isExpanded(selPath)) {
					tree_popupaction.putValue(Action.NAME, "Collapse");
				} else {

					if(tree.isRowSelected(row)) {

						//if this is the first selection, the node needs to be selected first
						//TreeExpansionEvent t = new TreeExpansionEvent(tree, selPath);
						//dirExpansionListener.treeExpanded(t);
						tree_popupaction.putValue(Action.NAME, "Expand");
					}
					else {
						tree_popupaction.putValue(Action.NAME, "Select");
					}
				}
				tree_popup.show(tree, x, y);
				//tree_clickedpath = selPath;
			}
		}


		/**
		 * Double click action.  If a .txt or .text file is double clicked, it will open in a separate window.
		 * The file is obtained through CCN.  If the file is no longer available, a message will appear in
		 * the preview pane.
		 *
		 * @param selRow Swing row selection
		 * @param selPath Path selected
		 */
		private void myDoubleClick(int selRow, TreePath selPath) {
			final Name node = getNameNode((DefaultMutableTreeNode) selPath.getLastPathComponent());

			if (node.name == null && node.equals(getNameNode(usableRoot))) {
				if (usableRoot.getChildCount() == 0 )
					JOptionPane.showMessageDialog(ContentExplorer.this,
							"Namespace is not available at this time",
							"Cannot Open Namespace",
							JOptionPane.ERROR_MESSAGE);
				return;
			}
			
			if (node.name == null)
				return;		// XXX shouldn't happen?

			ContentName cn = new ContentName(node.name);

			String name = cn.toString();

			if (name.toLowerCase().endsWith(".txt")	|| name.toLowerCase().endsWith(".text")) {
				if (node.path.count() == 0) {
					//retrieveFromRepo("/" + name);
					retrieveFromRepo(name, true);
				}
				else {
					//retrieveFromRepo(node.path.toString() + "/" + name);
					retrieveFromRepo(node.path.toString() + name, true);
				}

			} else {
				// Can't handle filetype
				JOptionPane.showMessageDialog(ContentExplorer.this, "Cannot Open file "
								+ cn + " Currently only opens .txt and .text files.",
								"Only handles .txt and .text files at this time.",
								JOptionPane.ERROR_MESSAGE);
			}
		}


		public void mouseEntered(MouseEvent e) {
			// we do not have actions for this yet
		}

		public void mouseExited(MouseEvent e) {
			// we do not have actions for this yet
		}

	}


	/**
	 * Class to handle directory actions - expand and collapse.
	 *
	 * @see TreeExpansionListener
	 */
	class DirExpansionListener implements TreeExpansionListener {

		/**
		 * Method called when a tree node is expanded, currently not used.
		 *
		 * @param event Swing TreeExpansionEvent object
		 * @return void
		 */
		public void treeExpanded(TreeExpansionEvent event) {
			DefaultMutableTreeNode node = getTreeNode(event.getPath());
			Name fnode = getNameNode(node);
            if (fnode != null) {
            	getNodes(fnode);
            	m_model.reload(node);
            }
		}

		/**
		 * Method to handle tree collapse events.  When a tree is collapsed, all name enumerations under
		 * the collapsed node are canceled.
		 *
		 * @param event TreeExpansionEvent object for the event
		 *
		 * @return void
		 */
		public void treeCollapsed(TreeExpansionEvent event) {
			DefaultMutableTreeNode node = getTreeNode(event.getPath());
			Name nodeName = getNameNode(node);
			Log.fine("nodeName: " + nodeName.toString());
			ContentName prefixToCancel = ContentName.ROOT;
			if (nodeName.path == null) {
				Log.fine("collapsed the tree at the root");
			} else {
				prefixToCancel = new ContentName(nodeName.path, nodeName.name);
				Log.fine("tree collapsed at: " + prefixToCancel.toString());
			}
			Log.fine("cancelling prefix: " + prefixToCancel);
			_nameEnumerator.cancelEnumerationsWithPrefix(prefixToCancel);

		}
	}

	/**
	 * Class to handle tree component selections.
	 *
	 */
	class DirSelectionListener implements TreeSelectionListener {

		/**
		 * Method to handle selection events.  If a node is selected for the first time and is a folder,
		 * name enumeration will begin under that prefix.  This event is also triggered as the tree is collapsed.
		 * In this case, we do not want to reselect nodes that were already canceled since
		 * enumeration will be started again.  To avoid this, the method checks if the path has any parent collapsed,
		 * if so, the event is not processed so name enumeration will not be restarted.
		 *
		 * @param event TreeSelectionEvent object to handle.
		 * @return void
		 */
		public void valueChanged(TreeSelectionEvent event) {

			final DefaultMutableTreeNode node = getTreeNode(event.getPath());

			// if the tree is not collapsed already, it is already being
			// enumerated, so we don't need to reselect it
			// if the row is -1, that means a parent is collapsed and this node
			// is being
			// selected as part of a collapse, so we don't want to re-register
			// it for enumerating

			if (tree.getRowForPath(event.getPath()) > -1) {
				tree.setSelectionPath(event.getPath());
				Thread runner = new Thread() {
					@Override
					public void run() {
						Log.fine("getting name node: " + node.toString());
						Name fnode = getNameNode(node);

						if (fnode == null) {
							Log.fine("fnode path is null...");
							// selected top component, switch to top
							// usable node
							fnode = getNameNode(usableRoot);
						}

						Log.fine("In the tree selection listener with "	+ fnode.name + " and " + node.toString());
						String p = getNodes(fnode);
						selectedPath = p;
						selectedPrefix = p;
					}
				};
				runner.start();
			}
		}
	}

	/**
	 * Main method for the ContentExplorer GUI.  The GUI defaults to exploring "/" but takes a -root option for exploring
	 * alternate namespaces.
	 *
	 * @param args String[] of the arguments for the GUI.  (path to explore and optional experimental access control GUI)
	 */
	public static void main(String[] args) {
		Log.setDefaultLevel(Level.WARNING);
		if (args.length > 0) {
			// we have some options
			String extraUsage = "";
			for (int i = 0; i < args.length; i++) {
				if (i == 0 && args[0].startsWith("[")) {
					extraUsage = args[0];
					continue;
				}
				if (args[i].equals("-h")) {
					usage(extraUsage);
				}
				String s = args[i];
				if (s.equalsIgnoreCase("-root")) {
					i++;
					try {
						root = ContentName.fromURI(args[i]);
					} catch (MalformedContentNameStringException e) {
						Log.warning("Could not parse root path: " + args[i]	+ " (exiting)");
						System.err.println("Could not parse root path: " + args[i] + " (exiting)");
						System.exit(1);
					}
				} else if (s.equals("-accessControl")) {
					accessControlOn = true;
				} else if (s.equals("-showVersions")) {
					showVersions = true;
				} else if (s.equals("-debugMode")) {
					debugMode = true;
				} else {
					usage(extraUsage);
				}
			}
		}

		if (root == null) {
			try {
				root = ContentName.fromNative("/");
			} catch (MalformedContentNameStringException e) {
				Log.warning("Could not parse root path: / (exiting)");
				System.err.println("Could not parse root path: / (exiting)");
				System.exit(1);
			}
		}

		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				createAndShowGUI();
			}
		});
	}

	/**
	 * Usage for ContentExplorer GUI.
	 */
	public static void usage(String extraUsage) {
		System.out.println("Content Explorer usage: " + extraUsage + "[-root /pathToExplore] [-accessControl] [-showVersions] [-debugMode]");
		System.exit(1);
	}

	/**
	 * Method to get the node selected with the SelectionListener
	 *
	 * @param fnode Name node to select
	 * @return String the full name for the selected node
	 */
	public String getNodes(Name fnode) {
		if (fnode.path == null)
			Log.fine("the path is null");
		else
			Log.fine("fnode: " + new ContentName(fnode.name) + " path: " + fnode.path.toString());
		ContentName toExpand = null;
		if (fnode.path == null)
			toExpand = ContentName.ROOT;
		else
			toExpand = new ContentName(fnode.path, fnode.name);

		String p = toExpand.toString();
		Log.fine("toExpand: " + toExpand + " p: " + p);

		if (fnode.name != null && previewTextFiles && (new ContentName(fnode.name).toString().endsWith(".txt") || new ContentName(fnode.name).toString().endsWith(".text"))) {
			// get the file from the repo
			Log.fine("Retrieve from Repo: " + p);
			retrieveFromRepo(p);
		}

		// this is a directory that we want to enumerate...  if it is a text file, we will still want to get the versions
		if (fnode.path == null)
			Log.fine("the path is null");
		else
			Log.fine("this is the path: " + fnode.path.toString() + " this is the name: " + new ContentName(fnode.name));
		Log.info("Registering Prefix: " + p);
		registerPrefix(p);

		initHelp();
		return p;
	}

	/**
	 * Static method to create and display the GUI.
	 */
	public static void createAndShowGUI() {
		if (useSystemLookAndFeel) {
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			} catch (Exception e) {
				Log.warning("Couldn't use system look and feel.");
			}
		}
		new ContentExplorer();
	}


	/**
	 * Method to handle CCNNameEnumeration callbacks.  This implementation assumes the
	 * application handles duplicates. This method creates an instance of the Runnable
	 * AddChildren class to process the names returned through CCNNameEnumeration.
	 *
	 * @param prefix ContentName of the prefix for returned names
	 * @param n ArrayList<ContentNames> of children returned by enumeration.
	 */
	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> n) {

		if (Log.getLevel()==Level.FINE) {
			Log.fine("got a callback! Here are the returned names: ");
			for (ContentName cn : n) {
				if (!prefix.equals(ContentName.ROOT))
					Log.fine(cn.toString() + " (" + prefix.toString() + cn.toString() + ")");
				else
					Log.fine(cn.toString() + " (" + cn.toString() + ")");
			}
		}

		AddChildren adder = new AddChildren(this, n, prefix);
		Thread t = new Thread(adder);
		t.start();

		return 0;
	}

	/**
	 * Method to register a prefix for name enumeration with CCNNameEnumerator
	 *
	 * @param prefix String representation of the name to enumerate
	 */
	public void registerPrefix(String prefix) {

		Log.fine("registering prefix: " + prefix);

		try {
			currentPrefix = ContentName.fromURI(prefix);
			_nameEnumerator.registerPrefix(currentPrefix);
		} catch (IOException e) {
			Log.warning("error registering prefix");
			Log.warningStackTrace(e);
		} catch (MalformedContentNameStringException e) {
			Log.warning("error with prefix string :" + prefix);
			Log.warningStackTrace(e);
		}

	}

	/**
	 *  Method to get an instance of a CCNHandle and CCNNameEnumerator.
	 *
	 *  @return void
	 */
	private void setupNameEnumerator() {
		try {
			_handle = CCNHandle.open();
			_nameEnumerator = new CCNNameEnumerator(_handle, this);
		} catch (ConfigurationException e) {
			Log.warningStackTrace(e);
		} catch (IOException e) {
			Log.warningStackTrace(e);
		}
	}

	/**
	 * Method to get the scaled images for displaying in the GUI.
	 *
	 * @param srcImg
	 * @param w
	 * @param h
	 * @return
	 */
	private static Image getScaledImage(Image srcImg, int w, int h) {
		BufferedImage resizedImg = new BufferedImage(w, h, BufferedImage.BITMASK);
		Graphics2D g2 = resizedImg.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.drawImage(srcImg, 0, 0, w, h, null);
		g2.dispose();
		return resizedImg;
	}

	/**
	 * Experimental code for access control GUI.
	 *
	 * @param e ActionEvent
	 * @return void
	 */
	public void actionPerformed(ActionEvent e) {

		if (openACL == e.getSource()) {
			Log.fine("Path is " + selectedPrefix);
			EventQueue.invokeLater(new Runnable() {
				public void run() {
					try {
						ACLManager dialog = new ACLManager(selectedPrefix, gacm);
						if (dialog.hasACL()) dialog.setVisible(true);
						else {
							dialog.setVisible(false);
							dialog.dispose();
						}
					} catch (Exception e) {
						Log.warningStackTrace(e);
					}
				}
			});
		} else if (openGroup == e.getSource()) {
			Log.fine("Path is " + selectedPrefix);
			EventQueue.invokeLater(new Runnable() {
				public void run() {
					try {
						GroupManagerGUI dialog = new GroupManagerGUI(selectedPrefix, gacm);
						dialog.setVisible(true);
					} catch (Exception e) {
						Log.warningStackTrace(e);
					}
				}
			});
		}
	}

	/**
	 * Method to return the CCNNameEnumerator for the ContentExplorer application
	 *
	 * @return CCNNameEnumerator
	 */
	public CCNNameEnumerator getNameEnumerator() {
		return _nameEnumerator;
	}

	/**
	 * Method to check for CCN Plugin for VLC.  Returns true if the ccn plugin is
	 * installed for VLC.  If it is not found, the "Play File" option is disabled
	 * for files.
	 *
	 * Currently not tested on non-linux platforms.
	 *
	 * @return  boolean True if the text ccn is found in the vlc --list output.
	 */

	public boolean checkVLCsupport() {

		InputStream output = null;
		//InputStream stderr = null;

		boolean check = false;

		String[] cmd = {"/bin/sh", "-c", "vlc --list | grep ccn"};
		try {
			Process p = Runtime.getRuntime().exec(cmd);
			output = p.getInputStream();
			//stderr = p.getErrorStream();

			String line = null;
			BufferedReader brCleanUp = new BufferedReader (new InputStreamReader (output));
			while ((line = brCleanUp.readLine ()) != null) {
				//Log.fine ("[Stdout] " + line);
				if(line.toLowerCase().contains("ccn")) {
					check = true;
					Log.fine("ContentExplorer found CCN VLC plugin, enabling play option");
				}
			}
			brCleanUp.close();

			//brCleanUp = new BufferedReader (new InputStreamReader (stderr));
			//while ((line = brCleanUp.readLine ()) != null) {
			//}
			//brCleanUp.close();


		} catch (IOException e) {
			Log.warning("ContentExplorer could not check for CCN VLC plugin, disabling play file option");
			Log.logException("Error checking for VLC CCN plugin", e);
		}

		return check;
	}

	public static void setRoot(ContentName r) {
		root = r;
	}

	public static void setAccessControl(boolean ac) {
		accessControlOn = ac;
	}

	public static void setShowVersions(boolean sv) {
		showVersions = sv;
	}

	public static void setDebugMode(boolean dm) {
		debugMode = dm;
	}

	public static void setGroupAccessControlManager(GroupAccessControlManager acm) {
		gacm = acm;
	}

	public static void setUsername(String name) {
		userName = name;
	}

	public static void setPreviewTextfiles(boolean ptf) {
		previewTextFiles = ptf;
	}

}
