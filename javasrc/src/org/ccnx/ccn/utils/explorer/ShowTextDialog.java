package org.ccnx.ccn.utils.explorer;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.swing.JButton;

import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class ShowTextDialog extends JDialog implements ActionListener{
/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
private Name nodeSelected;
private JEditorPane editorPane;
private JButton finishedButton;

	public void actionPerformed(ActionEvent e) {
		if(finishedButton== e.getSource()){
			this.setVisible(false);
			this.dispose();
		}
		
	}
	public ShowTextDialog(Name name,CCNHandle library) {	
		super();
		
		
		getContentPane().setLayout(null);
		this.nodeSelected = name;
		this.setBounds(100, 100, 500, 500);
		final JLabel label = new JLabel();
		label.setBounds(0, 0, 490, 51);
		label.setText("Displaying File: " + nodeSelected.name);
		getContentPane().add(label);
		

		final JScrollPane scrollPane = new JScrollPane();
		scrollPane.setBounds(0, 40, 490, 378);
		getContentPane().add(scrollPane);

		this.editorPane = new JEditorPane();
		scrollPane.setViewportView(editorPane);
		
		System.out.println("File Name is "+ name);
		
		//get the file name as a ContentName
		//write the content to the content pane to be displayed in the UI
		ContentName fileName = null;
		try {
			fileName = ContentName.fromNative(name.path.toString()+"/"+name.name);
			displayText(fileName,library);
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		finishedButton = new JButton();
		finishedButton.setName("buttonDone");
		finishedButton.setBounds(215, 424, 112, 36);
		finishedButton.setText("Finished");
		finishedButton.addActionListener(this);
		getContentPane().add(finishedButton);

		
	}
	
	/**
	 * Creates a CCNFileInputStream to write content to a CCN 
	 * writes the contents from a CCN to the editorPane
	 * 
	 *<p>
	 *@param fileName
	 * a content name object that points to an existing file in the repo to be displayed
	 *@param library
	 * */
	private void displayText(ContentName fileName, CCNHandle library) {
		try {
			
			CCNFileInputStream fis = new CCNFileInputStream(fileName, library);
			
			editorPane.read(fis, fileName);
			
		} catch (FileNotFoundException e) {

			System.out.println("the file was not found...  "+fileName);
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (XMLStreamException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 

	}
}
