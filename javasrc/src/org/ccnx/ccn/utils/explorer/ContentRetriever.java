package org.ccnx.ccn.utils.explorer;

import javax.swing.JEditorPane;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.protocol.ContentName;

public class ContentRetriever implements Runnable {

	private String name = null;
	private JEditorPane htmlPane = null;
	private CCNHandle handle = null;
	
	public ContentRetriever(CCNHandle h, JEditorPane p, String n){
		handle = h;
		htmlPane = p;
		name = n;
	}
	
	public void setFileName(String n){
		name = n;
	}
	
	public void setHTMLPane(JEditorPane pane){
		htmlPane = pane;
	}
	
	public void setCCNHandle(CCNHandle h){
		handle = h;
	}
	
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
		}
			

		try{
			//get the file name as a ContentName
			ContentName fileName = ContentName.fromURI(name);
			//System.out.println("retrieving "+fileName.toString()+" from repo");

			CCNFileInputStream fis = new CCNFileInputStream(fileName, handle);
				
			htmlPane.read(fis, fileName);
			
		} catch (Exception e) {
			System.err.println("Could not retrieve file: "+name);
			htmlPane.setText(name + " is not available at this time.");
			e.printStackTrace();
		}

	}

}
