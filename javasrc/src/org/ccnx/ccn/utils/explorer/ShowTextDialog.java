/*
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

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileNotFoundException;
import java.io.IOException;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JScrollPane;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.protocol.ContentName;


public class ShowTextDialog extends JDialog implements ActionListener{

	private static final long serialVersionUID = 2975715488713355433L;

	private ContentName contentname;
	private JEditorPane editorPane;
	private JButton finishedButton;
	private CCNHandle handle;

	public void actionPerformed(ActionEvent e) {
		if(finishedButton== e.getSource()){
			this.setVisible(false);
			this.dispose();
		}
		
	}
	public ShowTextDialog(ContentName name, CCNHandle h) {	
		
		if (name==null) {
			System.err.println("null filename, returning");
			return;
		}
		
		if (h==null) {
			System.err.println("CCNHandle is null, returning");
			return;
		}
		
		
		contentname = name;
		handle = h;
		
		getContentPane().setLayout(null);
		this.setBounds(100, 100, 500, 500);
		final JLabel label = new JLabel();
		label.setBounds(0, 0, 490, 51);
		label.setText("Displaying File: " + contentname);
		getContentPane().add(label);
		

		final JScrollPane scrollPane = new JScrollPane();
		scrollPane.setBounds(0, 40, 490, 378);
		getContentPane().add(scrollPane);

		this.editorPane = new JEditorPane();
		scrollPane.setViewportView(editorPane);
			
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
	 *@param handle
	 * */
	public void displayText() throws IOException {
		try {
			if(this.contentname==null) {
				System.err.println("fileName was null");
				return;
			}
			
			CCNFileInputStream fis = new CCNFileInputStream(contentname, this.handle);
			
			editorPane.read(fis, contentname);
			
		} catch (FileNotFoundException e) {

			System.err.println("the file was not found...  "+contentname);
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw e;
		} 

	}
	
}
