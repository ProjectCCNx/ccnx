package com.parc.ccn.apps.container;

/**
 * This application that requires the following additional files:
 *   NathanTreeHelp.html
 *    arnold.html
 *    bloch.html
 *    chan.html
 *    jls.html
 *    swingtutorial.html
 *    tutorial.html
 *    tutorialcont.html
 *    vm.html
 */
//import FileNode;

import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeSelectionModel;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;

import java.net.URL;
import java.util.ArrayList;
import java.util.logging.Level;
import java.io.IOException;
import java.awt.Dimension;
import java.awt.GridLayout;

public class ContainerGUItest extends JPanel
                      implements BasicNameEnumeratorListener {
    /**
	 * 
	 */
	private static final long serialVersionUID = 8291979948494547958L;
	private JEditorPane htmlPane;
    private JTree tree;
    private URL helpURL;
    private static boolean DEBUG = false;

    //Optionally play with line styles.  Possible values are
    //"Angled" (the default), "Horizontal", and "None".
    private static boolean playWithLineStyle = false;
    private static String lineStyle = "Horizontal";
    
    //Optionally set the look and feel.
    private static boolean useSystemLookAndFeel = false;
    protected JTextField m_display;

    private ArrayList<ContentName> names;
    
    public ContainerGUItest() {
        super(new GridLayout(1,0));

        //Create the nodes.
        DefaultMutableTreeNode top =
            new DefaultMutableTreeNode("parc.com");
        createNodes(top);

        //Create a tree that allows one selection at a time.
        tree = new JTree(top);
        tree.getSelectionModel().setSelectionMode
                (TreeSelectionModel.SINGLE_TREE_SELECTION);

        //Listen for when the selection changes.
       // tree.addTreeSelectionListener(this);

        if (playWithLineStyle) {
            System.out.println("line style = " + lineStyle);
            tree.putClientProperty("JTree.lineStyle", lineStyle);
        }

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
	  /** Required by TreeSelectionListener interface. */
	    public void valueChanged(TreeSelectionEvent e) {
	        DefaultMutableTreeNode node = (DefaultMutableTreeNode)
	                           tree.getLastSelectedPathComponent();

	        if (node == null) return;

	        Object nodeInfo = node.getUserObject();
	        if (node.isLeaf()) {
	            Name name = (Name)nodeInfo;
	            displayURL(name.fileURL);
	            if (DEBUG) {
	                System.out.print(name.fileURL + ":  \n    ");
	            }
	        } else {
	            displayURL(helpURL); 
	        }
	        if (DEBUG) {
	            System.out.println(nodeInfo.toString());
	        }
	    }
	  /*
public void valueChanged(TreeSelectionEvent event)
    {
      DefaultMutableTreeNode node = getTreeNode(
        event.getPath());
      FileNode fnode = getFileNode(node);
      if (fnode != null)
        m_display.setText(fnode.getFile().
          getAbsolutePath());
      else
        m_display.setText("");
    }
    */
  }
     
    private class Name {
        public String name;
        public URL fileURL;

        public Name(String nameString, String filename) {
            name = nameString;
            fileURL = getClass().getResource(filename);
            if (fileURL == null) {
                System.err.println("Couldn't find file: "
                                   + filename);
            }
        }

        public String toString() {
            return name;
        }
    }

    private void initHelp() {
        String s = "NathanTreeHelp.html";
        helpURL = getClass().getResource(s);
        if (helpURL == null) {
            System.err.println("Couldn't open help file: " + s);
        } else if (DEBUG) {
            System.out.println("Help URL is " + helpURL);
        }

        displayURL(helpURL);
    }

    private void displayURL(URL url) {
        try {
            if (url != null) {
                htmlPane.setPage(url);
            } else { //null url
		htmlPane.setText("File Not Found");
                if (DEBUG) {
                    System.out.println("Attempted to display a null URL.");
                }
            }
        } catch (IOException e) {
            System.err.println("Attempted to read a bad URL: " + url);
        }
    }

    private void createNodes(DefaultMutableTreeNode top) {
        DefaultMutableTreeNode category = null;
        DefaultMutableTreeNode name = null;



        category = new DefaultMutableTreeNode("registerTest");
        top.add(category);
        

        name = new DefaultMutableTreeNode(new Name
            ("name1",
            "testcontent.html"));
        category.add(name);


        name = new DefaultMutableTreeNode(new Name
        		("name2",
                "testcontent.html"));
        category.add(name);


        name = new DefaultMutableTreeNode(new Name
        		("name3",
                "testcontent.html"));
        category.add(name);


        name = new DefaultMutableTreeNode(new Name
        		("name4",
                "testcontent.html"));
        category.add(name);

        category = new DefaultMutableTreeNode("registerTest2");
        top.add(category);
        
        name = new DefaultMutableTreeNode(new Name
        		("name11",
                "testcontent.html"));
        category.add(name);


        name = new DefaultMutableTreeNode(new Name
        		("name22",
                "testcontent.html"));
        category.add(name);




        name = new DefaultMutableTreeNode(new Name
        		("name33",
                "testcontent.html"));
        category.add(name);


        name = new DefaultMutableTreeNode(new Name
        		("name44",
                "testcontent.html"));
        category.add(name);
    }
        
    /**
     * Create the GUI and show it.  For thread safety,
     * this method should be invoked from the
     * event dispatch thread.
     */
    private static void createAndShowGUI() {
        if (useSystemLookAndFeel) {
            try {
                UIManager.setLookAndFeel(
                    UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                System.err.println("Couldn't use system look and feel.");
            }
        }

        //Create and set up the window.
        JFrame frame = new JFrame("Container GUI Test");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        //Add content to the window.
        frame.add(new ContainerGUItest());

        //Display the window.
        frame.pack();
        frame.setVisible(true);
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
		net.registerPrefix();
		net.getCallback();
		net.getCallbackDirty();	
    	
    }
   
    	
    public static void main(String[] args) {
        //Schedule a job for the event dispatch thread:
        //creating and showing this application's GUI.
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                nameEnumerationTest();
            	createAndShowGUI();
                
            }
        });
    }

	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> n) {
		
		System.out.println("got a callback!");
		this.names = n;
		System.out.println("here are the returned names: ");
		for(ContentName cn: this.names)
			System.out.println(cn.toString()+" ("+prefix.toString()+cn.toString()+")");		
		return 0;
	}
	
	
}
