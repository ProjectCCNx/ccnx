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
    protected JTextArea textArea;
    final static String newline = "\n";
    protected CCNLibrary library;
    
    /* Model */
    protected Collection currentDirectory;
    
    protected String collections;
    
    protected static final String DIRECTORY_NAME = "/parc.com/ContainerApp/Directory";
    protected static final long DEFAULT_TIMEOUT = 500;
    protected ContentName directoryName = null;
    
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
    	currentDirectory = library.getCollection(directoryName, DEFAULT_TIMEOUT);
    	
    	if (null == currentDirectory) {
    		ContentObject currentObject = library.put(directoryName, new LinkReference[0]);
    		currentDirectory = library.decodeCollection(currentObject);
     	}
//   		Interest interest = Interest.latestVersionInterest(directoryName);
		Interest interest = new Interest(directoryName);
		interest.orderPreference(new Integer(Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME));
		
   		// When I want to get some content 
		// library.expressInterest(interest, this);
    }

    public void actionPerformed(ActionEvent evt) {
        String text = textField.getText();
        textArea.append(text + newline);
        
        ContentName name = null;
		try {
			name = ContentName.fromNative(directoryName + "/"+ text);
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        //put content into ccnd
		Library.logger().info(name.toString());
        currentDirectory.add(name);
        textField.selectAll();

        //Make sure the new text is visible, even if there
        //was a selection in the text area.
        textArea.setCaretPosition(textArea.getDocument().getLength());
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
		for (ContentObject result : results) {
			try {
				newCollection = library.decodeCollection(result);
				String text = "New directory: " + result.name();
				Library.logger().info("New directory: " + result.name());
				
				//textArea.append(text + newline);
			} catch (IOException e) {
				continue;
			}
		}
		if (null != newCollection) {
			currentDirectory = newCollection;
						
			// Call view to tell it model has changed
			//String text = "Content Added to CCND";
			//textArea.append(text + newline);
		}
		return interest;
	}
}
