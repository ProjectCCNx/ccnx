/**
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

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
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
