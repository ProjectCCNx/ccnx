package com.parc.ccn.apps.containerApp;

import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.swing.JButton;

import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JScrollPane;

public class ShowTextDialog extends JDialog implements ActionListener{
/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
private Name nodeSelected;
private JEditorPane editorPane;
private JButton finishedButton;

	public void actionPerformed(ActionEvent e) {
		// TODO Auto-generated method stub
		if(finishedButton== e.getSource()){
			this.setVisible(false);
			this.dispose();
		}
		
	}
	public ShowTextDialog(Name name) {	
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
		
		System.out.println("File Name is "+ nodeSelected.name);
		displayText(nodeSelected.name);
		
		finishedButton = new JButton();
		finishedButton.setName("buttonDone");
		finishedButton.setBounds(215, 424, 112, 36);
		finishedButton.setText("Finished");
		finishedButton.addActionListener(this);
		getContentPane().add(finishedButton);

		
	}
	private void displayText(String name) {
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
			editorPane.setText(new String(cbuf));
//			editorPane.setText("Testing 1 2 3");
		} catch (FileNotFoundException e) {

			System.out.println("the file was not found...  "+name);
			e.printStackTrace();
		}

	}
}
