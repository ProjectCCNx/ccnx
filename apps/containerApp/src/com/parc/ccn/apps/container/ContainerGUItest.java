package com.parc.ccn.apps.container;
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

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;

public class ContainerGUItest extends JFrame
implements BasicNameEnumeratorListener  
{
	
	static CCNNameEnumerator getne;
	protected static CCNLibrary getLibrary = null;
  /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
public static final ImageIcon ICON_COMPUTER = 
    new ImageIcon(getScaledImage((new ImageIcon("Network.png")).getImage(),32,32));
  public static final ImageIcon ICON_DISK = 
	  new ImageIcon(getScaledImage((new ImageIcon("Computer.png")).getImage(),32,32));
	  
  public static final ImageIcon ICON_FOLDER = 
	  new ImageIcon(getScaledImage((new ImageIcon("Folder.png")).getImage(),32,32));
	  
  public static final ImageIcon ICON_EXPANDEDFOLDER = 
	  new ImageIcon(getScaledImage((new ImageIcon("Document.png")).getImage(),32,32));
	
  public static final ImageIcon ICON_DOCUMENT = 
	  new ImageIcon(getScaledImage((new ImageIcon("Document.png")).getImage(),32,32));
  
  private static boolean DEBUG = false;
  private ArrayList<ContentName> names;
  private JEditorPane htmlPane;  
  private URL helpURL;
  public String selectedPrefix;
  protected JTree  tree;
  protected DefaultTreeModel m_model;
  protected JTextField m_display;

  private ContentName currentPrefix = null;
// NEW
  protected JPopupMenu m_popup;
  protected Action m_action;
  protected TreePath m_clickedPath;
  
  private static Image getScaledImage(Image srcImg, int w, int h){
      BufferedImage resizedImg = new BufferedImage(w, h, BufferedImage.BITMASK);
      Graphics2D g2 = resizedImg.createGraphics();
      g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g2.drawImage(srcImg, 0, 0, w, h, null);
      g2.dispose();
      return resizedImg;
  }
  
  public ContainerGUItest()
  {
 super("CCN Tree [Popup Menus]");
 
 try {
	getne = new CCNNameEnumerator(CCNLibrary.open(), this);
	getLibrary = CCNLibrary.open();
} catch (ConfigurationException e1) {
	// TODO Auto-generated catch block
	e1.printStackTrace();
} catch (IOException e1) {
	// TODO Auto-generated catch block
	e1.printStackTrace();
}
    setSize(400, 300);

    DefaultMutableTreeNode top = new DefaultMutableTreeNode(
      new IconData(ICON_COMPUTER, null, "parc.com"));

    DefaultMutableTreeNode node = null;
    //get whatever things I need at this point
    //query ccnd for stuff and stick it into the array    
    
    try {
		node = new DefaultMutableTreeNode(new IconData(ICON_FOLDER, 
		        null, new Name("/files", ContentName.fromNative("/parc.com/files"))));
	} catch (MalformedContentNameStringException e1) {
		// TODO Auto-generated catch block
		System.out.println("Error in the content name");
		e1.printStackTrace();
	}
    top.add(node);
   
    
//    File[] roots = File.listRoots();
//    for (int k=0; k<roots.length; k++)
//    {
//      node = new DefaultMutableTreeNode(new IconData(ICON_DISK, 
//        null, new FileNode(roots[k])));
//      top.add(node);
//      node.add(new DefaultMutableTreeNode( new Boolean(true) ));
//    }

    m_model = new DefaultTreeModel(top);
    tree = new JTree(m_model);

                tree.putClientProperty("JTree.lineStyle", "Angled");

    TreeCellRenderer renderer = new 
      IconCellRenderer();
    tree.setCellRenderer(renderer);

//    tree.addTreeExpansionListener(new 
//      DirExpansionListener());

    tree.addTreeSelectionListener(new 
      DirSelectionListener());

    tree.getSelectionModel().setSelectionMode(
      TreeSelectionModel.SINGLE_TREE_SELECTION); 
    tree.setShowsRootHandles(true); 
    tree.setEditable(false);

    //JScrollPane treeView = new JScrollPane();
    String filename = File.separator+"tmp";
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
            // Show dialog; this method does not return until dialog is closed
            chooser.showOpenDialog(frame);
    
            // Get the selected file
            File file = chooser.getSelectedFile();
            
     System.out.println("Writing a file to the repo "+ file.getAbsolutePath() + " " + file.getName());      
     System.out.println("Selected Node is "+ selectedPrefix);
     CCNLibrary repoguy = null;
			try {
				repoguy = CCNLibrary.open();
			} catch (ConfigurationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
            
            try {
            	System.out.println("Sending file "+selectedPrefix+"/"+file.getName());
				RepositoryOutputStream ros = repoguy.repoOpen(ContentName.fromNative(selectedPrefix+"/"+file.getName()), 
						null, repoguy.getDefaultPublisher());
				FileInputStream fs = new FileInputStream(file);
				byte[] buffer = new byte[fs.available()];
				fs.read(buffer);
				ros.write(buffer);
				try{
				ros.close();
				}catch (IOException ex){}
				
				readFile(ContentName.fromNative(selectedPrefix+"/"+file.getName()));
				getne.registerNameForResponses(ContentName.fromNative(selectedPrefix+"/"+file.getName()));
			} catch (MalformedContentNameStringException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (XMLStreamException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			
			
        }
    };

    Action openAction = new OpenFileAction(this, fc);
    JButton openButton = new JButton(openAction);

//New Stuff
  //Create the scroll pane and add the tree to it. 
    JScrollPane treeView = new JScrollPane(tree);

    //Create the HTML viewing pane.
    htmlPane = new JEditorPane();
    htmlPane.setEditable(false);
    initHelp();
    JScrollPane htmlView = new JScrollPane(htmlPane);

    //Add the scroll panes to a split pane.
    JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    splitPane.setTopComponent(treeView);
    splitPane.setBottomComponent(htmlView);

    Dimension minimumSize = new Dimension(100, 50);
    htmlView.setMinimumSize(minimumSize);
    treeView.setMinimumSize(minimumSize);
    splitPane.setDividerLocation(100); 
    splitPane.setPreferredSize(new Dimension(500, 300));

    //Add the split pane to this panel.
    add(splitPane);

    //End new stuff
    
    
    
//    treeView.getViewport().add(tree);
    getContentPane().add(openButton, BorderLayout.NORTH);
    getContentPane().add(treeView, BorderLayout.CENTER);
    getContentPane().add(splitPane, BorderLayout.SOUTH);
    
    pack();

//    m_display = new JTextField();
//    m_display.setEditable(false);
//    getContentPane().add(m_display, BorderLayout.NORTH);

    tree.setSelectionPath(new TreePath(node.getPath()));
    
// NEW
    m_popup = new JPopupMenu();
    m_action = new AbstractAction() 
    { 
      /**
		 * 
		 */
		private static final long serialVersionUID = -3875007136742502632L;
	
		
	public void actionPerformed(ActionEvent e)
      {
        if (m_clickedPath==null)
          return;
        if (tree.isExpanded(m_clickedPath))
          tree.collapsePath(m_clickedPath);
        else
          tree.expandPath(m_clickedPath);
      }
    };
    m_popup.add(m_action);
    m_popup.addSeparator();

    Action a1 = new AbstractAction("Delete") 
    { 
      /**
		 * 
		 */
		private static final long serialVersionUID = 1L;

	public void actionPerformed(ActionEvent e)
      {
                                tree.repaint();
        JOptionPane.showMessageDialog(ContainerGUItest.this, 
          "Delete option is not implemented",
          "Info", JOptionPane.INFORMATION_MESSAGE);
      }
    };
    m_popup.add(a1);

    Action a2 = new AbstractAction("Rename") 
    { 
      /**
		 * 
		 */
		private static final long serialVersionUID = 1L;

	public void actionPerformed(ActionEvent e)
      {
                                tree.repaint();
        JOptionPane.showMessageDialog(ContainerGUItest.this, 
          "Rename option is not implemented",
          "Info", JOptionPane.INFORMATION_MESSAGE);
      }
    };
    m_popup.add(a2);
    tree.add(m_popup);
    tree.addMouseListener(new PopupTrigger());

    WindowListener wndCloser = new WindowAdapter()
    {
      public void windowClosing(WindowEvent e) 
      {
        System.exit(0);
      }
    };
    addWindowListener(wndCloser);
    
    setVisible(true);
    
  //fix this later so I don't stop the whole show...
    System.out.println("Registering Prefix");
    registerPrefix("/parc.com/files");
  
  }

  public void sendFile(String filename)
  {
	  //doing this to send it to repo and then get it back and read it
	  
	  System.out.println("Writing a file to the repo "+ filename);      
	     System.out.println("Selected Node is "+ selectedPrefix);
	     CCNLibrary repoguy = null;
				try {
					repoguy = CCNLibrary.open();
				} catch (ConfigurationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	            
	            try {
	            	System.out.println("Sending repo a file "+filename);
					RepositoryOutputStream ros = repoguy.repoOpen(ContentName.fromNative(filename), 
							null, repoguy.getDefaultPublisher());
					String content = "Test content for reading a file from the repo";
					byte[] buffer = content.getBytes();
					ros.write(buffer);
					try{
					ros.close();
					}catch (IOException ex){}
					
					readFile(ContentName.fromNative(filename));
					
				} catch (MalformedContentNameStringException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (XMLStreamException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	  
  }
  
  public void readFile(ContentName name) {
	// TODO Auto-generated method stub
		  
	  	//Now read the file and write it out
	  	
	  String[] items = (name.toString()).split("/");
	  String fileName = items[items.length - 1];
	  System.out.println("file name is "+ fileName);
	  
	  	try {
	  		ContentObject testContent = getLibrary.get(new Interest(name), 10000);
	  		
	  		FileOutputStream fs = new FileOutputStream(fileName);
	  		fs.write(testContent.content());
	  	} catch (IOException e) {
	  		// TODO Auto-generated catch block
	  		e.printStackTrace();
	  	}	

	  	//open up the file for testing
	  	
	  	//Desktop desktop = Desktop.getDesktop();	  	
	  	//desktop.open(new File(fileName));
	  
}

private void initHelp() {
      String s = "NathanTreeHelp.html";
      helpURL = getClass().getResource(s);
      
      if (helpURL == null) {
          System.err.println("Couldn't open help file: " + s);
      } else if (DEBUG) {
          System.out.println("Help URL is " + helpURL);
      }

      //displayURL(helpURL);
      displayText(s);
      
}

private void displayText(String name) {
	try {

		
		FileInputStream fs = new FileInputStream(name);
		byte[] cbuf = null;
		try {
			cbuf = new byte[fs.available()];
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		try {
			fs.read(cbuf);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		htmlPane.setText(new String(cbuf));
	} catch (FileNotFoundException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
	
	
}

DefaultMutableTreeNode getTreeNode(TreePath path)
  {
    return (DefaultMutableTreeNode)(path.getLastPathComponent());
  }

  Name getNameNode(DefaultMutableTreeNode node)
  {
    if (node == null)
      return null;
    Object obj = node.getUserObject();
    if (obj instanceof IconData)
      obj = ((IconData)obj).getObject();
    if (obj instanceof Name)
      return (Name)obj;
    else
      return null;
  }
  
  
  FileNode getFileNode(DefaultMutableTreeNode node)
  {
    if (node == null)
      return null;
    Object obj = node.getUserObject();
    if (obj instanceof IconData)
      obj = ((IconData)obj).getObject();
    if (obj instanceof FileNode)
      return (FileNode)obj;
    else
      return null;
  }

// NEW
  class PopupTrigger extends MouseAdapter
  {
    public void mouseReleased(MouseEvent e)
    {
      //if (e.isPopupTrigger()) // apparently doesn't work. http://forums.java.net/jive/thread.jspa?threadID=35178 Swing blows chunks
   if ((e.getButton() != MouseEvent.BUTTON1) && (e.getID() == MouseEvent.MOUSE_PRESSED) )
      {
        int x = e.getX();
        int y = e.getY();
        TreePath path = tree.getPathForLocation(x, y);
        if (path != null)
        {
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

//    // Make sure expansion is threaded and updating the tree model
//    // only occurs within the event dispatching thread.
//    class DirExpansionListener implements TreeExpansionListener
//    {
//        public void treeExpanded(TreeExpansionEvent event)
//        {
//            final DefaultMutableTreeNode node = getTreeNode(
//                event.getPath());
//            final FileNode fnode = getFileNode(node);
//
//            Thread runner = new Thread() 
//            {
//              public void run() 
//              {
//                if (fnode != null && fnode.expand(node)) 
//                {
//                  Runnable runnable = new Runnable() 
//                  {
//                    public void run() 
//                    {
//                       m_model.reload(node);
//                    }
//                  };
//                  SwingUtilities.invokeLater(runnable);
//                }
//              }
//            };
//            runner.start();
//        }
//
//        public void treeCollapsed(TreeExpansionEvent event) {}
//    }

  class DirSelectionListener 
    implements TreeSelectionListener 
  {
    public void valueChanged(TreeSelectionEvent event)
    {
    	
    	//fix this later so I don't stop the whole show...
        //System.out.println("Registering Prefix");
        
        //register a prefix
        //show a waiting thingy until it returns
      DefaultMutableTreeNode node = getTreeNode(
        event.getPath());
      
      //prefix.toString()+cn.toString();
      
      Name fnode = getNameNode(node);
      if (((fnode.toString()).split("\\.")).length > 1)
    		  {
    	  //populate the repo and get it back
    	  sendFile("/parc.com/files/test.txt");
    	  displayText("test.txt");
    		  }else
    		  {
    			  displayText("NathanTreeHelp.html");
    		  }
      String p = fnode.path.toString()+fnode.name;
      System.out.println("Registering Prefix: "+p);
      registerPrefix(p);
      selectedPrefix = p;
    }
  }

  public static void main(String argv[]) 
  {
	  nameEnumerationTest();
	  new ContainerGUItest();
    
  }

  private void addTreeNodes(ArrayList<ContentName> n,ContentName prefix)
  {
//	  DefaultMutableTreeNode top = new DefaultMutableTreeNode(
//		      new IconData(ICON_COMPUTER, null, "parc.com/files"));
	
	  DefaultMutableTreeNode selectednode = (DefaultMutableTreeNode)
      tree.getLastSelectedPathComponent();
	
	  selectednode.removeAllChildren();
	  
	  DefaultMutableTreeNode node;
	//while we are getting things, wait for stuff to happen
	    System.out.println("Getting Content Names");    
	    for (ContentName cn: n)
	    {	    	
//	    	FileNode nd = (FileNode)v.elementAt(i);
//	        IconData idata = new IconData(FileTree2.ICON_FOLDER, 
//	          FileTree2.ICON_EXPANDEDFOLDER, nd);
//	        DefaultMutableTreeNode node = new 
//	          DefaultMutableTreeNode(idata);
//	        parent.add(node);
	    	
	    	if(((cn.toString()).split("\\.")).length > 1)
	    	{
	    		node = new DefaultMutableTreeNode(new IconData(ICON_DOCUMENT, 
	    		        null, new Name(cn.toString(),prefix)));
	    	}else{
	      node = new DefaultMutableTreeNode(new IconData(ICON_FOLDER, 
	        null, new Name(cn.toString(),prefix)));
	    	}
	      node.add(new DefaultMutableTreeNode( new Boolean(true) ));
	      selectednode.add(node);
	    }
	    System.out.println("Done Getting Content Names");
  }
  
  public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> n) {
			  	
		System.out.println("got a callback!");
		this.names = n;
		System.out.println("here are the returned names: ");
		for(ContentName cn: this.names)
			System.out.println(cn.toString()+" ("+prefix.toString()+cn.toString()+")");				
		this.addTreeNodes(n,prefix);
		
		return 0;
				
		//File[] roots = File.listRoots();		
	    
	}

  public void cancelPrefix(ContentName prefix)
  {
	  System.out.println("registering prefix: "+prefix);
	  getne.cancelPrefix(prefix);
	  	
  }
  
  public void registerPrefix(String prefix){
				 		
		System.out.println("registering prefix: "+prefix);
		
		if(currentPrefix != null){
		cancelPrefix(currentPrefix);
		}
		try{
			currentPrefix = ContentName.fromNative(prefix);
			getne.registerPrefix(currentPrefix);
		}
		catch(IOException e){
			System.err.println("error registering prefix");
			e.printStackTrace();
			
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			System.err.println("error with prefix string :" + prefix);
			e.printStackTrace();
		}
		
		
	}  
  
private static void nameEnumerationTest()
{
	System.out.println("Starting CCNNameEnumerator Test");
	GUINameEnumerator net = new GUINameEnumerator();
	try {
		net.setLibraries(CCNLibrary.open(), CCNLibrary.open());
		//net.setLibrary(CCNLibrary.open());
		Library.logger().setLevel(Level.FINEST);
		//net.nameEnumeratorSetup();
	} catch (ConfigurationException e) {
		e.printStackTrace();
	} catch (IOException e) {
		e.printStackTrace();
	}	
	
	
	//put names into the repository
	//todo - parse items based off of types
	//on click display names 
	net.RegisterName();
	//net.registerPrefix();
	//net.getCallback();
	//net.getCallbackDirty();	
	
}
}

class IconCellRenderer 
  extends    JLabel 
  implements TreeCellRenderer
{
  /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
protected Color m_textSelectionColor;
  protected Color m_textNonSelectionColor;
  protected Color m_bkSelectionColor;
  protected Color m_bkNonSelectionColor;
  protected Color m_borderSelectionColor;

  protected boolean m_selected;

  public IconCellRenderer()
  {
    super();
    m_textSelectionColor = UIManager.getColor(
      "Tree.selectionForeground");
    m_textNonSelectionColor = UIManager.getColor(
      "Tree.textForeground");
    m_bkSelectionColor = UIManager.getColor(
      "Tree.selectionBackground");
    m_bkNonSelectionColor = UIManager.getColor(
      "Tree.textBackground");
    m_borderSelectionColor = UIManager.getColor(
      "Tree.selectionBorderColor");
    setOpaque(false);
  }

  public Component getTreeCellRendererComponent(JTree tree, 
    Object value, boolean sel, boolean expanded, boolean leaf, 
    int row, boolean hasFocus) 
    
  {
    DefaultMutableTreeNode node = 
      (DefaultMutableTreeNode)value;
    Object obj = node.getUserObject();
    setText(obj.toString());

    //Example of setting text to show that data is being retrieved.
    
                if (obj instanceof Boolean)
                  setText("Retrieving data...");

    if (obj instanceof IconData)
    {
      IconData idata = (IconData)obj;
      if (expanded)
        setIcon(idata.getExpandedIcon());
      else
        setIcon(idata.getIcon());
    }
    else
      setIcon(null);

    setFont(tree.getFont());
    setForeground(sel ? m_textSelectionColor : 
      m_textNonSelectionColor);
    setBackground(sel ? m_bkSelectionColor : 
      m_bkNonSelectionColor);
    m_selected = sel;
    return this;
  }


 
  public void paintComponent(Graphics g) 
  {
    Color bColor = getBackground();
    Icon icon = getIcon();

    g.setColor(bColor);
    int offset = 0;
    if(icon != null && getText() != null) 
      offset = (icon.getIconWidth() + getIconTextGap());
    g.fillRect(offset, 0, getWidth() - 1 - offset,
      getHeight() - 1);
    
    if (m_selected) 
    {
      g.setColor(m_borderSelectionColor);
      g.drawRect(offset, 0, getWidth()-1-offset, getHeight()-1);
    }

    super.paintComponent(g);
    }
}

class IconData
{
  protected Icon   m_icon;
  protected Icon   m_expandedIcon;
  protected Object m_data;

  public IconData(Icon icon, Object data)
  {
    m_icon = icon;
    m_expandedIcon = null;
    m_data = data;
  }

  public IconData(Icon icon, Icon expandedIcon, Object data)
  {
    m_icon = icon;
    m_expandedIcon = expandedIcon;
    m_data = data;
  }

  public Icon getIcon() 
  { 
    return m_icon;
  }

  public Icon getExpandedIcon() 
  { 
    return m_expandedIcon!=null ? m_expandedIcon : m_icon;
  }

  public Object getObject() 
  { 
    return m_data;
  }

  public String toString() 
  { 
    return m_data.toString();
  }
}

class Name {
    public String name;
    public ContentName path;
    public URL fileURL;

    public Name(String nameString, ContentName prefix) {
        name = nameString;
        path = prefix;
        //fileURL = getClass().getResource(filename);
//        if (fileURL == null) {
//            System.err.println("Couldn't find file: "
//                               + filename);
//        }
    }

    public Object getObject() {
		// TODO Auto-generated method stub
		return this;
	}

	public String toString() {
        return name;
    }   
    
}

class FileNode
{
  protected File m_file;

  public FileNode(File file)
  {
    m_file = file;
  }

  public File getFile() 
  { 
    return m_file;
  }

  public String toString() 
  { 
    return m_file.getName().length() > 0 ? m_file.getName() : 
      m_file.getPath();
  }

  public boolean expand(DefaultMutableTreeNode parent)
  {
    DefaultMutableTreeNode flag = 
      (DefaultMutableTreeNode)parent.getFirstChild();
    if (flag==null)    // No flag
      return false;
    Object obj = flag.getUserObject();
    if (!(obj instanceof Boolean))
      return false;      // Already expanded

    parent.removeAllChildren();  // Remove Flag

    File[] files = listFiles();
    if (files == null)
      return true;

    Vector v = new Vector();

    for (int k=0; k<files.length; k++)
    {
      File f = files[k];
      if (!(f.isDirectory()))
        continue;

      FileNode newNode = new FileNode(f);
      
      boolean isAdded = false;
      for (int i=0; i<v.size(); i++)
      {
        FileNode nd = (FileNode)v.elementAt(i);
        if (newNode.compareTo(nd) < 0)
        {
          v.insertElementAt(newNode, i);
          isAdded = true;
          break;
        }
      }
      if (!isAdded)
        v.addElement(newNode);
    }

    for (int i=0; i<v.size(); i++)
    {
      FileNode nd = (FileNode)v.elementAt(i);
      IconData idata = new IconData(ContainerGUItest.ICON_FOLDER, 
        ContainerGUItest.ICON_EXPANDEDFOLDER, nd);
      DefaultMutableTreeNode node = new 
        DefaultMutableTreeNode(idata);
      parent.add(node);
        
      if (nd.hasSubDirs())
        node.add(new DefaultMutableTreeNode( 
          new Boolean(true) ));
    }

    return true;
  }

  public boolean hasSubDirs()
  {
    File[] files = listFiles();
    if (files == null)
      return false;
    for (int k=0; k<files.length; k++)
    {
      if (files[k].isDirectory())
        return true;
    }
    return false;
  }
  
  public int compareTo(FileNode toCompare)
  { 
    return  m_file.getName().compareToIgnoreCase(
      toCompare.m_file.getName() ); 
  }

  protected File[] listFiles()
  {
    if (!m_file.isDirectory())
      return null;
    try
    {
      return m_file.listFiles();
    }
    catch (Exception ex)
    {
      JOptionPane.showMessageDialog(null, 
        "Error reading directory "+m_file.getAbsolutePath(),
        "Warning", JOptionPane.WARNING_MESSAGE);
      return null;
    }
  }
}