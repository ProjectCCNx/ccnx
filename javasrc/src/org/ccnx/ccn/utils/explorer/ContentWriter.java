package org.ccnx.ccn.utils.explorer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.swing.JEditorPane;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;

public class ContentWriter implements Runnable{

	private File file = null;
	private ContentName ccnName = null;
	private CCNHandle handle = null;
	private JEditorPane htmlPane = null;
	
	public ContentWriter(CCNHandle h, ContentName name, File f, JEditorPane pane){
		handle = h;
		file = f;
		ccnName = name;
		htmlPane = pane;
	}
	
	public void setHTMLPane(JEditorPane pane){
		htmlPane = pane;
	}
	
	public void setFile(File f){
		file = f;
	}
	
	public void setHTMLPane(ContentName name){
		ccnName = name;
	}
	
	public void setCCNHandle(CCNHandle h){
		handle = h;
	}
	
	public void run() {
		
		if (htmlPane == null) {
			System.err.println("Must set htmlPane to view status messages");
			return;
		}
		
		if (ccnName == null) {
			System.err.println("Must set ContentName for content objects");
			return;
		}
		
		if (file == null) {
			System.err.println("Must set file to write out to CCN");
			return;
		}
		
		if (handle == null) {
			System.err.println("Must set CCNHandle");
			return;
		}
		
		try {
			RepositoryFileOutputStream fos = new RepositoryFileOutputStream(ccnName, handle);
			FileInputStream fs = new FileInputStream(file);
			int bytesRead = 0;
			byte[] buffer = new byte[SegmentationProfile.DEFAULT_BLOCKSIZE];

			while ((bytesRead = fs.read(buffer)) != -1) {
				fos.write(buffer, 0, bytesRead);
			}

			fos.close();
			htmlPane.setText("Finished writing file "+file.getName()+" as "+ccnName);
		} catch (IOException e) {
			htmlPane.setText("Error writing file "+file.getName()+" as "+ccnName+"\n\n"+"Error: "+e.getMessage());
			System.err.println("error writing file to repo: "+e.getMessage());
		}
	}
}
