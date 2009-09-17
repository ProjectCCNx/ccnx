package org.ccnx.ccn.utils.explorer;

import javax.swing.JEditorPane;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.protocol.ContentName;

/**
 * Class used by the ContentExplorer to retrieve content and display
 * .txt and .text files in the GUI preview pane.  This will be updated
 * in a future release to handle more content types.
 * 
 */
public class HTMLPaneContentRetriever implements Runnable {

	private String name = null;
	private JEditorPane htmlPane = null;
	private CCNHandle handle = null;
	
	/**
	 * Constructor for the HTMLPaneContentRetriever.
	 * 
	 * @param h CCNHandle to use for downloading the content
	 * @param p JEditorPane to use for status messages
	 * @param n String representation of the content object name to download
	 */
	public HTMLPaneContentRetriever(CCNHandle h, JEditorPane p, String n){
		handle = h;
		htmlPane = p;
		name = n;
	}
	
	/**
	 * Set method for the ContentObject to download.
	 * 
	 * @param n String representation of the content
	 * @return void
	 */
	public void setFileName(String n){
		name = n;
	}
	
	/**
	 * Set method for the preview pane used for status messages.
	 * 
	 * @param pane JEditorPane used for status updates
	 * @return void
	 */
	public void setHTMLPane(JEditorPane pane){
		htmlPane = pane;
	}
	
	/**
	 * Set method for the CCNHandle.
	 * 
	 * @param h CCNHandle used to retrieve the content
	 * @return void
	 */
	public void setCCNHandle(CCNHandle h){
		handle = h;
	}
	
	/**
	 * Run method for the thread that will be used to download the content.
	 * As bytes for the object are retrieved, they will be displayed in the
	 * preview pane.
	 * 
	 * @return void
	 * 
	 * @see CCNFileInputStream
	 */
	public void run() {
		
		if (name == null) {
			System.err.println("Must set file name for retrieval");
			return;
		}
		
		if (htmlPane == null) {
			System.err.println("Must set htmlPane");
			return;
		}
		
		if (handle == null) {
			System.err.println("Must set CCNHandle");
			return;
		}
			

		try{
			//get the file name as a ContentName
			ContentName fileName = ContentName.fromURI(name);

			CCNFileInputStream fis = new CCNFileInputStream(fileName, handle);
				
			htmlPane.read(fis, fileName);
		} catch (Exception e) {
			System.err.println("Could not retrieve file: "+name);
			htmlPane.setText(name + " is not available at this time.");
			e.printStackTrace();
		}

	}

}
