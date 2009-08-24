package org.ccnx.ccn.utils.explorer;

import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;

import javax.swing.JDialog;
import javax.swing.JOptionPane;

public class ChangedEntriesConfirm implements WindowListener {

	private JDialog form;
	private ValuesChanged changed;
	public ChangedEntriesConfirm(JDialog groupManager, ValuesChanged changedEntries) {
		// TODO Auto-generated constructor stub
		this.form = groupManager;
		this.changed = changedEntries;
		System.out.println("changed is "+ this.changed.changed);
	}

	public void windowActivated(WindowEvent e) {
		// TODO Auto-generated method stub
		System.out.println("Window activated");
	}

	public void windowClosed(WindowEvent e) {
		// TODO Auto-generated method stub
		System.out.println("Window closed event occur");

	}

	public void windowClosing(WindowEvent e) {
		System.out.println("Window closing event occur");
		// TODO Auto-generated method stub
		System.out.println("changed is "+ this.changed.changed);
		if(changed.changed)
		{
			int answer = JOptionPane.showConfirmDialog(this.form, "You have pending changes. Are you sure you would like to exit", "Pending Changes",JOptionPane.YES_NO_OPTION);
			switch(answer){
			case JOptionPane.YES_OPTION:
				form.setVisible(false);
				form.dispose();
				break;
			case JOptionPane.NO_OPTION:
				break;
			}
			
		}else
		{
			form.setVisible(false);
			form.dispose();
		}

	}

	public void windowDeactivated(WindowEvent e) {
		// TODO Auto-generated method stub
		System.out.println("Window deactivated event occur");
	}

	public void windowDeiconified(WindowEvent e) {
		// TODO Auto-generated method stub
		System.out.println("Window deiconified event occur");
	}

	public void windowIconified(WindowEvent e) {
		// TODO Auto-generated method stub
		System.out.println("Window iconified event occur");
	}

	public void windowOpened(WindowEvent e) {
		// TODO Auto-generated method stub
		System.out.println("Window opened event occur");
	}

}
