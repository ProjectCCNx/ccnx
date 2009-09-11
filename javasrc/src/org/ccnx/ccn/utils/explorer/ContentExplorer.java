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


//import FileNode;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.event.*;
import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.utils.explorer.IconCellRenderer;
import org.ccnx.ccn.utils.explorer.IconData;
import org.ccnx.ccn.utils.explorer.Name;





public class ContentExplorer extends JFrame implements BasicNameEnumeratorListener,ActionListener{

	private static ContentName root;
	private static boolean accessControlOn = false;
	
	private CCNNameEnumerator _nameEnumerator = null;
	protected static CCNHandle _library = null;
    private static boolean useSystemLookAndFeel = false;
    
	private static final long serialVersionUID = 1L;
	public static final ImageIcon ICON_COMPUTER = new ImageIcon(getScaledImage(
			(new ImageIcon("./src/org/ccnx/ccn/utils/explorer/Network.png")).getImage(), 32, 32));
	public static final ImageIcon ICON_DISK = new ImageIcon(getScaledImage(
			(new ImageIcon("./src/org/ccnx/ccn/utils/explorer/Computer.png")).getImage(), 32, 32));

	public static final ImageIcon ICON_FOLDER = new ImageIcon(getScaledImage(
			(new ImageIcon("./src/org/ccnx/ccn/utils/explorer/Folder.png")).getImage(), 32, 32));

	public static final ImageIcon ICON_EXPANDEDFOLDER = new ImageIcon(
			getScaledImage((new ImageIcon("./src/org/ccnx/ccn/utils/explorer/Document.png")).getImage(), 32, 32));

	public static final ImageIcon ICON_DOCUMENT = new ImageIcon(getScaledImage(
			(new ImageIcon("./src/org/ccnx/ccn/utils/explorer/Document.png")).getImage(), 32, 32));

	private static boolean DEBUG = false;
	private ArrayList<ContentName> names;
	private JEditorPane htmlPane;
	private URL helpURL;
	public String selectedPrefix;
	public String selectedPath;
	protected JTree tree;
	protected DefaultTreeModel m_model;
	protected JTextField m_display;

	private JButton openACL = null;
	private JButton openGroup = null;
	
	private ContentName currentPrefix = null;
	// NEW
	protected JPopupMenu m_popup;
	protected Action m_action;
	protected TreePath m_clickedPath;
	private DefaultMutableTreeNode usableRoot = null;
	
	
	
	public ContentExplorer() {
		super("CCN Content Explorer");
		
		setupNameEnumerator();

		setSize(400, 300);

		ContentName slash = null;
		try {
			slash = ContentName.fromNative("/");
			slash = new ContentName();
		} catch (MalformedContentNameStringException e1) {
			Log.logException("could not create slash (\"/\") content name", e1);
			Log.exitApplication();
		}
		
		System.out.println("root has "+root.count()+" components");
		
		//DefaultMutableTreeNode top = new DefaultMutableTreeNode(new IconData(ICON_COMPUTER, null,new Name(root.toString(), slash, true)));

		//usableRoot = top;
		//DefaultMutableTreeNode node = top;
		
		 //attempt to put each component of the root path in its own tree node.
		//DefaultMutableTreeNode top = new DefaultMutableTreeNode(new IconData(ICON_COMPUTER, null,"/"));

		//usableRoot = top;
		
		DefaultMutableTreeNode top = new DefaultMutableTreeNode(new IconData(ICON_FOLDER, null, new Name(slash.component(0), null, true)));
		//newNode = top;
		//node = top;
		
		//DefaultMutableTreeNode top = null;
		DefaultMutableTreeNode node = top;
		DefaultMutableTreeNode newNode = null;
		//add each component of the root
		System.out.println("root = "+root.toString());
		for(int i = 0; i < root.count(); i++){
			System.out.println("adding component: "+root.stringComponent(i));
			//add each component to the tree
			newNode = new DefaultMutableTreeNode(new IconData(ICON_FOLDER, null, new Name(root.component(i), root.copy(i), true)));
			if(top == null){
				top = newNode;
			} else {
				node.add(newNode);
			}
			//usableRoot = node;
			node = newNode;
		}
		
		if(top == null){
			top = new DefaultMutableTreeNode(new IconData(ICON_FOLDER, null, new Name(root.component(0), null, true)));
			newNode = top;
			node = top;
		}
		
		usableRoot = top;
		
		
		m_model = new DefaultTreeModel(top);
		tree = new JTree(m_model);

		tree.putClientProperty("JTree.lineStyle", "Angled");

		TreeCellRenderer renderer = new IconCellRenderer();
		tree.setCellRenderer(renderer);

		tree.addTreeExpansionListener(new DirExpansionListener());
		tree.addTreeSelectionListener(new DirSelectionListener());

		MouseListener ml = new MouseAdapter() {
		     public void mousePressed(MouseEvent e) {
		         int selRow = tree.getRowForLocation(e.getX(), e.getY());
		         TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
	
		         if(selRow != -1) {
//		             if(e.getClickCount() == 1) {
//		                 mySingleClick(selRow, selPath);
//		             }
//		             else 
		            	 if(e.getClickCount() == 2) {
		                 myDoubleClick(selRow, selPath);
		             }
		         }
		     }

			private void myDoubleClick(int selRow, TreePath selPath) {
				final Name node = getNameNode((DefaultMutableTreeNode)selPath.getLastPathComponent());
				
				String name = new String(node.name);
				//String[] items = name.split("\\."); //make this a regex later
				//if(items.length > 1 && items[1].equalsIgnoreCase("txt")){ //have a file
				if(name.toLowerCase().endsWith(".txt") || name.toLowerCase().endsWith(".text")){
					if(node.path.count()==0)
						retrieveFromRepo("/"+name);
					else
						retrieveFromRepo(node.path.toString()+"/"+name);
					EventQueue.invokeLater(new Runnable() {
						public void run() {
							try {
								ShowTextDialog dialog = new ShowTextDialog(node,_library);
								dialog.setVisible(true);
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					});				
				} else {
					//Can't handle filetype
					JOptionPane.showMessageDialog(ContentExplorer.this, "Cannot Open file "+ name+" Currently only opens .txt and .text files.", "Only handles .txt and .text files at this time.", JOptionPane.ERROR_MESSAGE);
				}
			}

			
		 };
		 tree.addMouseListener(ml);

		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.setShowsRootHandles(true);
		tree.setEditable(false);


		//String filename = File.separator + "tmp";
		String filename = "~/";
		JFileChooser fc = new JFileChooser(new File(filename));
		
		
		class OpenFileAction extends AbstractAction {
			/**
		 * 
		 */
			private static final long serialVersionUID = 1L;
			JFrame frame;
			
			JFileChooser chooser;

			OpenFileAction(JFrame frame, JFileChooser chooser) {
				super("Send to Repo...");
				this.chooser = chooser;
				this.frame = frame;
			}

			public void actionPerformed(ActionEvent evt) {
				
				//if we don't have a file then we should show a file chooser
				//otherwise give an error message "Please select a folder"
				
				if (((selectedPrefix.toString()).split("\\.")).length > 2) {
					JOptionPane.showMessageDialog(this.frame, "Please Select a Directory to add a file", "Select Directory",JOptionPane.ERROR_MESSAGE);
				} else {
					// Show dialog; this method does not return until dialog is
					// closed
				
					int returnVal = chooser.showOpenDialog(frame);

					// Get the selected file
					File file = chooser.getSelectedFile();
					if(file == null || file.getName().equals("")){
						System.out.println("the user did not select a file");
						return;
					}
				
					//what if the user hits cancel...
					if(returnVal != JFileChooser.APPROVE_OPTION){
						System.out.println("user cancelled the send to repo option...  returning");
						return;
					}
					
				
					System.out.println("Writing a file to the repo " + file.getAbsolutePath() + " " + file.getName());
					System.out.println("Selected Node is " + selectedPrefix);
				

					try{
						ContentName contentName = ContentName.fromNative(selectedPrefix);
						contentName = ContentName.fromNative(contentName, file.getName());
						sendFile(file, contentName);
					} catch (MalformedContentNameStringException e) {
						e.printStackTrace();
					}
				}
			}
		};

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

		// Add the scroll panes to a split pane.
		JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
		splitPane.setContinuousLayout(true);
		splitPane.setOneTouchExpandable(true);
		splitPane.setTopComponent(treeView);
		splitPane.setBottomComponent(htmlView);
		Dimension minimumSize = new Dimension(100, 300);
		htmlView.setMinimumSize(minimumSize);
		treeView.setMinimumSize(minimumSize);
		splitPane.setDividerLocation(200);
		splitPane.setPreferredSize(new Dimension(500, 300));
		
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
    	 
		c.fill = GridBagConstraints.BOTH;
		c.gridx = 0;
        c.gridy = 1;
        if(accessControlOn)
        	getContentPane().add(openACL,  c);

		c.fill = GridBagConstraints.BOTH;
		c.gridx = 1;
        c.gridy = 1;
        if(accessControlOn)
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

		System.out.println("setting selectionPath: "+node.getPath()+" node: "+node.toString());
		tree.expandPath(new TreePath(node.getPath()));
		tree.setSelectionPath(new TreePath(node.getPath()));

		// POPUP Related - not working currently
		m_popup = new JPopupMenu();
		m_action = new AbstractAction() {
			/**
		 * 
		 */
			private static final long serialVersionUID = -3875007136742502632L;

			public void actionPerformed(ActionEvent e) {
				if (m_clickedPath == null)
					return;
				if (tree.isExpanded(m_clickedPath)){
					tree.collapsePath(m_clickedPath);
					TreePath[] p = (TreePath[]) m_clickedPath.getPath();
					System.out.println("collapsed path: "+p.toString());
				}
				else
					tree.expandPath(m_clickedPath);
			}
		};
		m_popup.add(m_action);
		m_popup.addSeparator();

		Action a1 = new AbstractAction("Delete") {
			/**
		 * 
		 */
			private static final long serialVersionUID = 1L;

			public void actionPerformed(ActionEvent e) {
				tree.repaint();
				JOptionPane.showMessageDialog(ContentExplorer.this,
						"Delete option is not implemented", "Info",
						JOptionPane.INFORMATION_MESSAGE);
			}
		};
		m_popup.add(a1);

		Action a2 = new AbstractAction("Rename") {
			/**
		 * 
		 */
			private static final long serialVersionUID = 1L;

			public void actionPerformed(ActionEvent e) {
				tree.repaint();
				JOptionPane.showMessageDialog(ContentExplorer.this,
						"Rename option is not implemented", "Info",
						JOptionPane.INFORMATION_MESSAGE);
			}
		};
		m_popup.add(a2);
		tree.add(m_popup);
		tree.addMouseListener(new PopupTrigger());

		WindowListener wndCloser = new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		};
		addWindowListener(wndCloser);

		setVisible(true);

	}

//END Popup related
	
	

	public void retrieveFromRepo(String name){
		//TODO this is a long-running operation, it should be a separate thread
		
		
		try{
	
			//get the file name as a ContentName
			ContentName fileName = ContentName.fromNative(name);
			//String localName = new String(fileName.lastComponent());
			System.out.println("retrieving "+fileName.toString()+" from repo");

			CCNFileInputStream fis = new CCNFileInputStream(fileName, _library);
				
			htmlPane.read(fis, fileName);
			
			//should catch a generic exception and print a message in the bottom pane
			//that the selected file is not available
			
		} catch (XMLStreamException e) {
			e.printStackTrace();
		} catch (IOException e) {
			
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			
			e.printStackTrace();
		}
	}
	
	
	
	
	public void sendFile(File file, ContentName ccnName) {
		// doing this to send it to repo and then get it back and read it

		System.out.println("Writing a file to the repo " + file.getName()+" with contentName: "+ccnName.toString());

		
		try {
			
			RepositoryFileOutputStream fos = new RepositoryFileOutputStream(ccnName, _library);
			FileInputStream fs = new FileInputStream(file);
			int bytesRead = 0;
			byte[] buffer = new byte[SegmentationProfile.DEFAULT_BLOCKSIZE];
			
			while ((bytesRead = fs.read(buffer)) != -1){
				fos.write(buffer, 0, bytesRead);
			}

			fos.close();
			
		} catch (IOException e) {

			System.out.println("error writing file to repo");
			e.printStackTrace();
		}

	}


	private void initHelp() {
		//String s = "TreeHelp.html";
		//helpURL = getClass().getResource(s);

		//if (helpURL == null) {
		//	System.err.println("Couldn't open help file: " + s);
		//} else if (DEBUG) {
		//	System.out.println("Help URL is " + helpURL);
		//}

		//displayText(s);
		
		htmlPane.setText("Please expand folder names you would like to enumerate.  You may also select text files to be displayed in this window.");

	}

/*	private void displayText(String name) {
		try {

			FileInputStream fs = new FileInputStream(name);
			byte[] cbuf = null;
			try {
				cbuf = new byte[fs.available()];
			} catch (IOException e1) {

				e1.printStackTrace();
			}
			try {
				fs.read(cbuf);
			} catch (IOException e) {

				e.printStackTrace();
			}
			htmlPane.setText(new String(cbuf));
		} catch (FileNotFoundException e) {

			System.out.println("the file was not found...  "+name);
			e.printStackTrace();
		}

	}
	*/
	
	
	DefaultMutableTreeNode getTreeNode(ContentName ccnContentName){
		System.out.println("handling returned names!!! prefix = "+ccnContentName.toString());
		TreePath prefixPath = new TreePath(usableRoot);
		
		System.out.println("prefix path: "+prefixPath.toString());
		ArrayList<byte[]> nbytes = ccnContentName.components();
		String[] names = new String[nbytes.size()];
		int ind = 0;
		for(byte[] n: nbytes){
			names[ind] = new String(n);
			ind++;
		}
		
		DefaultMutableTreeNode p = find(prefixPath, 0, names);
		
		return p;
	}

//	@SuppressWarnings("unchecked")
	private DefaultMutableTreeNode find(TreePath parent, int depth, String[] names){
		DefaultMutableTreeNode node = (DefaultMutableTreeNode)parent.getLastPathComponent();
		String nodeName = node.toString().replace("/", "");
		System.out.println("check nodeName: "+nodeName);
		if(names.length <= depth){
			//we don't have to look far...  this matches the root
			System.out.println("this is the root, and we want the root...  returning the root");
			return node;
		}
		
		if(node.isRoot()){
			node = findMatchingChild(parent, node, names[depth]);
			nodeName = node.toString();
		}
		
		System.out.println("names[depth] "+ names[depth]);
		
		//we added an extra empty slash at the top...  need to account for this
		
		if(names[depth].equals(nodeName)){
			System.out.println("we have a match!");
			if(depth == names.length){
				System.out.println("we are at the right depth! returning this node!");
				return node;
			}
			else{
				System.out.println("need to keep digging...");
				if(node.getChildCount() > 0){
					System.out.println("we have children: "+node.getChildCount());
					DefaultMutableTreeNode result = null;
					if(names.length > depth+1)
						result = findMatchingChild(parent, node, names[depth+1]);
					if (result == null) {
						System.out.println("no matching child... returning this node");
						return node;
					} else
						System.out.println("result was not null...  we have a matching child");
					TreePath path = parent.pathByAddingChild(result);
					return find(path, depth+1, names);
					//for(DefaultMutableTreeNode n : java.util.Collections.list((Enumeration<DefaultMutableTreeNode>)node.children()) ){
					//	TreePath path = parent.pathByAddingChild(n);
					//	System.out.println("treepath: "+path.toString());
					//	//DefaultMutableTreeNode result = find(path, depth+1, names);
					//	if(result!=null)
					//		return result;
					//	else
					//		System.out.println("result was null...  :(");
					//}
				}
				else{
					System.out.println("did not have any children...");
					return node;
				}
			}
		}
		else{
			System.out.println("not a match...");
		}
		
		
		return null;
	}
	
	
	DefaultMutableTreeNode findMatchingChild(TreePath parent, DefaultMutableTreeNode n, String name){
		name = name.replace("/", "");
		if(n.getChildCount() == 0)
			return null;
		else {
			DefaultMutableTreeNode c = null;
			for(int i = 0; i < n.getChildCount(); i++){
				c = (DefaultMutableTreeNode) n.getChildAt(i);
				System.out.println("child name: "+c.toString()+" name: "+name);
				if(c.toString().equals(name))
					return c;
			}
		}
		return null;	
	}
	
	
	DefaultMutableTreeNode getTreeNode(TreePath path) {
		return (DefaultMutableTreeNode) (path.getLastPathComponent());
	}

	Name getNameNode(DefaultMutableTreeNode node) {
		if (node == null)
			return null;
		Object obj = node.getUserObject();

		if(((IconData)obj).getObject()==null)
		System.out.println("get obj is null");
		
		if (obj instanceof IconData)
			obj = ((IconData) obj).getObject();
		if (obj instanceof Name)
			return (Name) obj;
		else
			return null;
	}

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

	// NEW
	class PopupTrigger extends MouseAdapter {
		public void mouseReleased(MouseEvent e) {
			// if (e.isPopupTrigger()) // apparently doesn't work.
			// http://forums.java.net/jive/thread.jspa?threadID=35178 Swing
			// blows chunks
			if ((e.getButton() != MouseEvent.BUTTON1)
					&& (e.getID() == MouseEvent.MOUSE_PRESSED)) {
				int x = e.getX();
				int y = e.getY();
				TreePath path = tree.getPathForLocation(x, y);
				if (path != null) {
					if (tree.isExpanded(path))
						m_action.putValue(Action.NAME, "Collapse");
					else
						m_action.putValue(Action.NAME, "Expand");
					m_popup.show(tree, x, y);
					m_clickedPath = path;
				}
			}
		}
	}

	 // Make sure expansion is threaded and updating the tree model
	// only occurs within the event dispatching thread.
	class DirExpansionListener implements TreeExpansionListener {
		public void treeExpanded(TreeExpansionEvent event) {

			final DefaultMutableTreeNode node = getTreeNode(event.getPath());

			Thread runner = new Thread() {
				public void run() {
					Runnable runnable = new Runnable() {
						public void run() {
							Name fnode = getNameNode(node);
							if (fnode != null) {
								System.out.println("In the tree expansion listener with node: " + node.toString());
								getNodes(fnode);
								m_model.reload(node);
							} else {
								// node = usableRoot;
								// selected top component, switch to top usuable
								// node.
								System.out.println("In the tree expansion listener with null node and " + node.toString());
								// fnode = getNameNode(usableRoot);
								// getNodes(fnode);
								// m_model.reload(usableRoot);

							}
						}
					};
					SwingUtilities.invokeLater(runnable);
				}

			};
			runner.start();
		}

        public void treeCollapsed(TreeExpansionEvent event) {
        	final DefaultMutableTreeNode node = getTreeNode(event.getPath());
        	Name nodeName = getNameNode(node);
        	System.out.println("nodeName: "+nodeName.toString());
        	ContentName prefixToCancel = new ContentName();
        	if(nodeName.path==null){
        		System.out.println("collapsed the tree at the root");
        	} else {
        		prefixToCancel = ContentName.fromNative(nodeName.path, nodeName.name);
        		System.out.println("tree collapsed at: "+prefixToCancel.toString());	
        	}
        	System.out.println("cancelling prefix: "+prefixToCancel);
        	_nameEnumerator.cancelEnumerationsWithPrefix(prefixToCancel);
        }
    }

	class DirSelectionListener implements TreeSelectionListener {
		public void valueChanged(TreeSelectionEvent event) {

			final DefaultMutableTreeNode node = getTreeNode(event.getPath());
					
			//if the tree is not collapsed already, it is already being enumerated, so we don't need to reselect it
			if (tree.isCollapsed(event.getPath())) {
				//if the row is -1, that means a parent is collapsed and this node is being
				//selected as part of a collapse, so we don't want to re-register it for enumerating
				if (tree.getRowForPath(event.getPath()) > -1) {
				Thread runner = new Thread() {
					public void run() {

						Runnable runnable = new Runnable() {
							public void run() {
								System.out.println("getting name node: " + node.toString());
								Name fnode = getNameNode(node);

								if (fnode == null) {
									System.out.println("fnode path is null...");
									// selected top component, switch to top
									// usable node
									// node = usableRoot;
									fnode = getNameNode(usableRoot);
								}
								if (fnode == null)
									System.out.println("fnode is null");
								if (node == null)
									System.out.println("node is null");
								System.out.println("In the tree selection listener with " + fnode.name + " and " + node.toString());
								String p = getNodes(fnode);
								selectedPath = p;
								selectedPrefix = p;
							}
						};
						SwingUtilities.invokeLater(runnable);
					}
				};
				runner.start();
				}
			} else {
				System.out.println("path is expanded... "+node.toString());
			}
		}
	}

	
	
	public static void main(String[] args) {
		Log.setLevel(Level.FINER);
		
		if (args.length > 0) {
			// we have some options
			for (int i = 0; i < args.length; i++) {
				String s = args[i];
				if (s.equalsIgnoreCase("-root")) {
					i++;
					try {
						root = ContentName.fromNative(args[i]);
					} catch (MalformedContentNameStringException e) {
						System.err.println("Could not parse root path: "
								+ args[i] + " (exiting)");
						System.exit(1);
					}
				} else if (s.equals("-accessControl"))
					accessControlOn = true;
				else {
					usage();
					System.exit(1);
				}
			}
		}

		if (root == null) {
			try {
				root = ContentName.fromNative("/");
			} catch (MalformedContentNameStringException e) {
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

	public static void usage(){
		System.out.println("Content Explorer usage: [-root /pathToExplore] [-accessControl]");
	}
	
    public String getNodes(Name fnode) {
    	if(fnode.path == null)
    		System.out.println("the path is null");
    	else
    		System.out.println("fnode: "+new String(fnode.name)+" path: "+fnode.path.toString());
    	ContentName toExpand = null;
    	if(fnode.path == null)
			toExpand = new ContentName();
    	else
    		toExpand = ContentName.fromNative(fnode.path, fnode.name);

		String p = toExpand.toString();
    	System.out.println("toExpand: "+toExpand+" p: "+p);
 
    	if (fnode.name!=null && (new String(fnode.name).endsWith(".txt") || new String(fnode.name).endsWith(".text"))){
			// get the file from the repo
			
    		System.out.println("Retrieve from Repo: " + p);
			retrieveFromRepo(p);			

			return p;
		} else {
			//this is a directory that we want to enumerate...
			if(fnode.path==null)
				System.out.println("the path is null");
			else
				System.out.println("this is the path: "+ fnode.path.toString()+" this is the name: "+new String(fnode.name));
			//String p = fnode.path.toString() + "/"+fnode.name;
			System.out.println("Registering Prefix: " + p);
			registerPrefix(p);

			initHelp();
			return p;
		}
		
	}

	private static void createAndShowGUI() {
        if (useSystemLookAndFeel) {
            try {
                UIManager.setLookAndFeel(
                    UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                System.err.println("Couldn't use system look and feel.");
            }
        }

        new ContentExplorer();
        //Create and set up the window.
//        JFrame frame = new JFrame("CCN Demo");
//        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        //Add content to the window.
//        frame.add(new ContainerGUI());
      //Set up the content pane.
        //addComponentsToPane(frame.getContentPane());

        //Display the window.
//        frame.pack();
//        frame.setVisible(true);
    }

	//@SuppressWarnings("unchecked")
	private void addTreeNodes(ArrayList<ContentName> n, ContentName prefix) {

		System.out.println("addTreeNodes: prefix = "+prefix+" names: "+n.toString());

		final DefaultMutableTreeNode parentNode = getTreeNode(prefix);
		if(parentNode == null){
			System.out.println("PARENT NODE IS NULL!!!"+ prefix.toString());
			System.out.println("can't add anything to a null parent...  cancel prefix and return");
			_nameEnumerator.cancelPrefix(prefix);
			return;
		}

		System.out.print("the parent has "+parentNode.getChildCount()+" children: ");
		for(DefaultMutableTreeNode temp : java.util.Collections.list((Enumeration<DefaultMutableTreeNode>)parentNode.children())){
			if(temp.getUserObject() instanceof IconData){
				IconData id = (IconData)temp.getUserObject();
				ContentName childName = ContentName.fromNative(new ContentName(), ((Name)id.m_data).name);
				System.out.print(" "+ childName);
			}
		}
		System.out.println();
		
		// while we are getting things, wait for stuff to happen
		System.out.println("Getting Content Names");
		boolean addToParent = true;
		DefaultMutableTreeNode toRemove = null;
		for (ContentName cn : n) {
			addToParent = true;
			
			//check if a version marker
			if(VersioningProfile.containsVersion(cn))
				addToParent = false;
			else
				System.out.println("name is not a version");
			//check if a segment marker
			if(SegmentationProfile.isSegment(cn))
				addToParent = false;
			else
				System.out.println("name is not a segment marker");
		
			if(addToParent && parentNode.getChildCount()>0){
				for(DefaultMutableTreeNode temp : java.util.Collections.list((Enumeration<DefaultMutableTreeNode>)parentNode.children())){
					//check if this name is already in there!
					if(temp.getUserObject() instanceof Boolean){
						toRemove = temp;
					}
					else{
						if(temp.getUserObject() instanceof IconData){
							IconData id = (IconData)temp.getUserObject();
							ContentName nodeName = ContentName.fromNative(new ContentName(), ((Name)id.m_data).name);
							System.out.println("check names: "+nodeName+" "+cn.toString());
							//if(((Name)(id.m_data)).toString().equals(cn.toString())){
							//if(temp.toString().equals(cn.toString().substring(1))){
							//check if already there...
							if(cn.compareTo(nodeName) == 0){
								addToParent = false;
							}
							else
								System.out.println("name not a match");
							}
					}
				}
				if(toRemove!=null){
					m_model.removeNodeFromParent(toRemove);
					toRemove = null;
				}
			}
			final DefaultMutableTreeNode node;
			if(addToParent){
				//name wasn't there, don't add again
				System.out.println("added as child: "+cn.toString());
				if (cn.toString().toLowerCase().endsWith(".txt") || cn.toString().toLowerCase().endsWith(".text")) {
			
					node = new DefaultMutableTreeNode(new IconData(ICON_DOCUMENT,
							null, new Name(cn.component(0), prefix,false)));
				} else {
					
					node = new DefaultMutableTreeNode(new IconData(ICON_FOLDER,
							null, new Name(cn.component(0), prefix,true)));
					//This is the "Retrieving Data" node (gets rendered in IconCell Renderer
					//node.add(new DefaultMutableTreeNode(new Boolean(true)));
				}
				//parentNode.add(node);
				
				//javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
		    //    javax.swing.SwingUtilities.invokeLater(new Runnable() {
		    //       public void run() {
			//			m_model.insertNodeInto(node, parentNode, parentNode.getChildCount());
			//			System.out.println("inserted node...  parent now has "+parentNode.getChildCount());
		    //        }
		    //    });
		        
		        m_model.insertNodeInto(node, parentNode, parentNode.getChildCount());
				System.out.println("inserted node...  parent now has "+parentNode.getChildCount());

			}
		}
		System.out.print("the parent node now has "+parentNode.getChildCount()+" children: ");
		for(DefaultMutableTreeNode temp : java.util.Collections.list((Enumeration<DefaultMutableTreeNode>)parentNode.children())){
			if(temp.getUserObject() instanceof IconData){
				IconData id = (IconData)temp.getUserObject();
				ContentName childName = ContentName.fromNative(new ContentName(), ((Name)id.m_data).name);
				System.out.print(" "+ childName);
			}
		}
		System.out.println();
		System.out.println("Done Getting Content Names");
	}

	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> n) {

		System.out.println("got a callback!");
		this.names = n;
		System.out.println("here are the returned names: ");
		for (ContentName cn : this.names){
			if(!prefix.equals(new ContentName()))
				System.out.println(cn.toString() + " (" + prefix.toString()	+ cn.toString() + ")");
			else
				System.out.println(cn.toString() + " ("+cn.toString()+")");
		}
		this.addTreeNodes(n, prefix);

		return 0;

	}

	public void registerPrefix(String prefix) {

		System.out.println("registering prefix: " + prefix);

		try {
			currentPrefix = ContentName.fromNative(prefix);
			_nameEnumerator.registerPrefix(currentPrefix);
		} catch (IOException e) {
			System.err.println("error registering prefix");
			e.printStackTrace();

		} catch (MalformedContentNameStringException e) {
	
			System.err.println("error with prefix string :" + prefix);
			e.printStackTrace();
		}

	}
	
	
	
	private void setupNameEnumerator(){
		try {
			_library = CCNHandle.open();
			_nameEnumerator = new CCNNameEnumerator(_library, this);
		} catch (ConfigurationException e1) {
	
			e1.printStackTrace();
		} catch (IOException e1) {
	
			e1.printStackTrace();
		}
	}
	
	
	private static Image getScaledImage(Image srcImg, int w, int h) {
		BufferedImage resizedImg = new BufferedImage(w, h,
				BufferedImage.BITMASK);
		Graphics2D g2 = resizedImg.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.drawImage(srcImg, 0, 0, w, h, null);
		g2.dispose();
		return resizedImg;
	}

	public void actionPerformed(ActionEvent e) {

		if(openACL == e.getSource()) {
			System.out.println("Path is "+ selectedPrefix);
			EventQueue.invokeLater(new Runnable() {
				public void run() {
					try {
						ACLManager dialog = new ACLManager(selectedPrefix);
						dialog.setVisible(true);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
		}else if(openGroup == e.getSource()) {
				System.out.println("Path is "+ selectedPrefix);
				EventQueue.invokeLater(new Runnable() {
					public void run() {
						try {
							GroupManager dialog = new GroupManager(selectedPrefix);

							dialog.setVisible(true);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				});	
			}	
	}


	
}
