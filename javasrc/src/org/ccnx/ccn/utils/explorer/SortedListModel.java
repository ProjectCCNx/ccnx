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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.swing.AbstractListModel;

class SortedListModel extends AbstractListModel {
	  /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	SortedSet<Object> model;

	  public SortedListModel() {
	    model = new TreeSet<Object>();
	  }

	  public int getSize() {
	    return model.size();
	  }

	  public Object getElementAt(int index) {
	    return model.toArray()[index];
	  }
	  
	  public Object[] getAllElements() {
		    return model.toArray();
		  }

	  public void add(Object element) {
	    if (model.add(element)) {
	      fireContentsChanged(this, 0, getSize());
	  }
	}
	  public void addAll(Object elements[]) {
	    Collection<Object> c = Arrays.asList(elements);
	    model.addAll(c);
	    fireContentsChanged(this, 0, getSize());
	  }

	  public void clear() {
	    model.clear();
	    fireContentsChanged(this, 0, getSize());
	  }

	  public boolean contains(Object element) {
	    return model.contains(element);
	  }

	  public Object firstElement() {
	    return model.first();
	  }

	  public Iterator<Object> iterator() {
	    return model.iterator();
	  }

	  public Object lastElement() {
	    return model.last();
	  }

	  public boolean removeElementArray(ArrayList<Object> elements)
	  {
		  boolean removed = false;
			for(Object item : elements)
			{
				removed = model.remove(item);
				
			}
			if (removed) {
			      fireContentsChanged(this, 0, getSize());
			    }
			return removed;
	  }
	  
	  public boolean removeElementArrayList(ArrayList<String> elements)
	  {
		  boolean removed = false;
			for(String item : elements)
			{
				removed = model.remove(item);
				
			}
			if (removed) {
			      fireContentsChanged(this, 0, getSize());
			    }
			return removed;
	  }
	  
	  public boolean removeElement(Object element) {
	    boolean removed = model.remove(element);
	    if (removed) {
	      fireContentsChanged(this, 0, getSize());
	    }
	    return removed;
	  }
	}
