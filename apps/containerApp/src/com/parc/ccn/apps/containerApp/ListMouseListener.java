package com.parc.ccn.apps.containerApp;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Array;
import java.util.ArrayList;

import javax.swing.JList;

public class ListMouseListener implements MouseListener {

	private ArrayList<JList> lists;
	private String selectedListName;
	
	public ListMouseListener(ArrayList<JList> listsArray) {
		// TODO Auto-generated constructor stub
		this.lists = listsArray;
	}

	public void mouseClicked(MouseEvent e) {
		// TODO Auto-generated method stub
		
		
            JList list = (JList)e.getSource();
            selectedListName = list.getName();
            
//            if (e.getClickCount() == 1) {          // Single Click Selection
//                // Get item index
//                int index = list.locationToIndex(e.getPoint());
//            }

            //unselect all the items in the other lists
            for(JList item:lists)
            {
            	if(!(item.getName().equalsIgnoreCase(selectedListName)))
            	{
         
            		System.out.println("Item Name: "+item.getName()+" selected name: "+selectedListName);
            		item.clearSelection();
            		
            	}
            }

	}

	public void mouseEntered(MouseEvent e) {
		// TODO Auto-generated method stub

	}

	public void mouseExited(MouseEvent e) {
		// TODO Auto-generated method stub

	}

	public void mousePressed(MouseEvent e) {
		// TODO Auto-generated method stub

	}

	public void mouseReleased(MouseEvent e) {
		// TODO Auto-generated method stub

	}

}
