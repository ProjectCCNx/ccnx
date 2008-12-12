package com.parc.ccn.apps.container;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.security.SignatureException;
import java.util.ArrayList;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;

public class ContainerGUItest extends JPanel implements ActionListener, CCNInterestListener {
    /**
	 * 
	 */
	private static final long serialVersionUID = 7198145969014262490L;
	protected JTextField textField;
    protected static JTextArea textArea;
    final static String newline = "\n";
    protected CCNLibrary library;
    
    /* Model */
    protected Collection currentDirectory;
    
    protected ArrayList<ContentName> namedContent = new ArrayList<ContentName>();
    
    protected static final String DIRECTORY_NAME = "/parc.com/ContainerApp/Directory";
    protected static final String CONTENT_PREFIX = "/parc.com/ContainerApp/Content";
    protected static final long DEFAULT_TIMEOUT = 500;
    protected ContentName directoryName = null;
    protected ContentName contentPrefix = null;
    
    public ContainerGUItest() throws ConfigurationException, IOException, MalformedContentNameStringException, InterruptedException, SignatureException {    	
    	super(new GridBagLayout());
   		
   		// have View load up currentDirectory
    	
        textField = new JTextField(20);
        textField.addActionListener(this);

        textArea = new JTextArea(5, 20);
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);

        //Add Components to this panel.
        GridBagConstraints c = new GridBagConstraints();
        c.gridwidth = GridBagConstraints.REMAINDER;

        c.fill = GridBagConstraints.HORIZONTAL;
        add(textField, c);

        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1.0;
        c.weighty = 1.0;
        add(scrollPane, c);
        
        
      //CCN Stuff
    	library = CCNLibrary.open();
    	directoryName = ContentName.fromNative(DIRECTORY_NAME);
    	contentPrefix = ContentName.fromNative(CONTENT_PREFIX);
    	
    	ContentObject directoryObject = library.getLatest(directoryName, DEFAULT_TIMEOUT);
    	   	
     	if (null == directoryObject) {
    		directoryObject = library.put(directoryName, new LinkReference[0]);
    	}
		currentDirectory = library.decodeCollection(directoryObject);

		namedContent.clear();
		for (LinkReference lr : currentDirectory.contents()) {
			namedContent.add(lr.targetName());
		}
        //repopulate the list with the 
        populateList();
        Library.logger().info("Initial directory contents: " + currentDirectory.size() + " items.");
    			
   		// When I want to get some content, not quite sure how these interest thingy works 
		try {
			// DKS temporary hack to say "last (latest version) object *after* this one"
			Interest interest = Interest.next(directoryObject, null);
			interest.orderPreference(Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME);
			Library.logger().info("Expressing initial interest: " + interest.name());
			library.expressInterest(interest, this);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

    public void actionPerformed(ActionEvent evt) {
        String text = textField.getText();
        
        
        ContentName name = null;
		try {
			name = ContentName.fromNative(contentPrefix + "/"+ text);
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        //put content into ccnd
		
		//Library.logger().info(name.toString());
        currentDirectory.add(name);
        try {
			ContentObject newDirectory = library.addToCollection(directoryName, new ContentName[]{name}, 0);
    		currentDirectory = library.decodeCollection(newDirectory);
			library.put(name, text.getBytes());
			Library.logger().info("Put new directory (" + currentDirectory.size() + " items): " + newDirectory.name());
		} catch (SignatureException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
/*
		try {
			ContentObject result = library.get(interest, 100000);
			Library.logger().info(result.name().toString());
			namedContent.add(result.name());
	        //repopulate the list with the 
	        populateList();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		*/
        //add to model - actually we should be getting this from the listener
        //namedContent.add(name);
        //repopulate the list with the 
        //populateList();
        
        textField.selectAll();

        //Make sure the new text is visible, even if there
        //was a selection in the text area.
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }
    
    private void populateList()
    {
    	textArea.setText("");
    	for(ContentName text:namedContent)
    	{
    		textArea.append(text.toString() + newline);	
    	}
    	
    }
    /**
     * Create the GUI and show it.  For thread safety,
     * this method should be invoked from the
     * event dispatch thread.
     */
    private static void createAndShowGUI() {
        //Create and set up the window.
        JFrame frame = new JFrame("Testing The Containers");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        //Add contents to the window.
        try {
			frame.add(new ContainerGUItest());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
        //Display the window.
        frame.pack();
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        //Schedule a job for the event dispatch thread:
        //creating and showing this application's GUI.
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                createAndShowGUI();
            }
        });
    
    }

	@Override
	public Interest handleContent(ArrayList<ContentObject> results,
			Interest interest) {
		Collection newCollection = null;
		ContentObject collectionObject = null;
		for (ContentObject result : results) {
			try {
				newCollection = library.decodeCollection(result);
				
				Library.logger().info("New directory: " + result.name());
				
				collectionObject = result;	
				
				//String text = "New directory: " + result.name();
				//textArea.append(text + newline);
			} catch (IOException e) {
				continue;
			}
		}
		if (null != newCollection) {
			currentDirectory = newCollection;
			//add to model - actually we should be getting this from the listener
			namedContent.clear();
			for (LinkReference lr : currentDirectory.contents()) {
				namedContent.add(lr.targetName());
			}
	        //repopulate the list with the 
	        populateList();
	        									
			// Call view to tell it model has changed
			//String text = "Content Added to CCND";
			//textArea.append(text + newline);
		}
		
		Interest newInterest = null;
		if (null != collectionObject) {
			newInterest = Interest.next(collectionObject, null);
			newInterest.orderPreference(Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME);
			Library.logger().info("Reexpressing incremented interest: " + newInterest);
		} else {
			Library.logger().info("Reexpressing vanilla interest: " + newInterest);
			newInterest = Interest.last(directoryName);
		}
		return newInterest;
	}
}
