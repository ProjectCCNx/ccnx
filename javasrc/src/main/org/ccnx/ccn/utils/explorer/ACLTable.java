/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import javax.swing.table.AbstractTableModel;

import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;

public class ACLTable extends AbstractTableModel {

	private static final long serialVersionUID = 1L;
	
	private String[] columnNames = {"Principals", "Read", "Write", "Manage"};
	private int ACL_LENGTH = 3;
	private ContentName[] principals;
	private Object[][] aclTable;
	private ACL initialACL;
	
	public ACLTable(String type, ContentName[] principals, ACL initialACL) {
		columnNames[0] = type;
		this.principals = principals;
		if (initialACL != null) initializeACLTable(initialACL);
		else System.out.println("Fatal error: initial ACL cannot be null");
	}
	
	public void initializeACLTable(ACL initialACL) {
		this.initialACL = initialACL;
		aclTable = new Object[principals.length][ACL_LENGTH];
		for (int c=0; c<ACL_LENGTH; c++) {
			for (int r=0; r<principals.length; r++) {
				aclTable[r][c] = Boolean.FALSE;
			}
		}
	
		for (int i=0; i<initialACL.size(); i++) {
			Link lk = (Link) initialACL.get(i);
			ContentName principal = lk.targetName();
			String role = lk.targetLabel();
			this.setRole(principal, role);
		}		
	}
	
	public int getColumnCount() {
		return columnNames.length;
	}
	
	public String getColumnName(int col) {
		return columnNames[col];
	}

	public int getRowCount() {
		return principals.length;
	}

	public Class<?> getColumnClass(int col) {
		return getValueAt(0, col).getClass();
	}
	
	public Object getValueAt(int row, int col) {
		if (col == 0) {
			String friendlyName = Component.printNative(principals[row].lastComponent());
			return friendlyName;
		}
		else if (col < 4) return aclTable[row][col-1];
		else return null;
	}
	
	public void setValueAt(Object value, int row, int col) {
		Boolean v = (Boolean) value;
		if (v.equals(Boolean.TRUE)) {
			for (int c=0; c<col; c++) {
				aclTable[row][c] = v;
				fireTableCellUpdated(row, c+1);
			}
		}
		else {
			for (int c=col-1; c < ACL_LENGTH; c++) {
				aclTable[row][c] = v;
				fireTableCellUpdated(row, c+1);
			}
		}
	}
	
	public boolean isCellEditable(int row, int col) {
		if (col == 0 ) return false;
		else return true;
	}
	
	public void cancelChanges() {
		initializeACLTable(initialACL);
		fireTableDataChanged();
	}
	
	public int getIndexOfPrincipal(ContentName principal) {
		int pos = -1;
		for (int i=0; i<principals.length; i++) {
			if (principal.compareTo(principals[i]) == 0) {
				pos = i;
				break;
			}
		}
		return pos;
	}
	
	public void setRole(ContentName principal, String role) {
		int pos = getIndexOfPrincipal(principal);
		if (pos > -1) {
			if (role.equals(ACL.LABEL_READER)) {
				aclTable[pos][0] = Boolean.TRUE;
				aclTable[pos][1] = Boolean.FALSE;
				aclTable[pos][2] = Boolean.FALSE;
			}
			if (role.equals(ACL.LABEL_WRITER)) {
				aclTable[pos][0] = Boolean.TRUE;
				aclTable[pos][1] = Boolean.TRUE;
				aclTable[pos][2] = Boolean.FALSE;
			}
			if (role.equals(ACL.LABEL_MANAGER)) {
				aclTable[pos][0] = Boolean.TRUE;
				aclTable[pos][1] = Boolean.TRUE;
				aclTable[pos][2] = Boolean.TRUE;
			}
		}
	}
	
	public String getRole(ContentName principal) {
		String role = null;
		int pos = getIndexOfPrincipal(principal);
		if (pos > -1) {
			if ((Boolean) aclTable[pos][2]) role = ACL.LABEL_MANAGER;
			else if ((Boolean) aclTable[pos][1]) role = ACL.LABEL_WRITER;
			else if ((Boolean) aclTable[pos][0]) role = ACL.LABEL_READER;			
		}
		return role;
	}
	
	public ArrayList<ACLOperation> computeACLUpdates() {
		ArrayList<ACLOperation> ACLUpdates = new ArrayList<ACLOperation>();
		
		for (int i=0; i<principals.length; i++) {
			ContentName principal = principals[i];
			Link plk = new Link(principal);
			
			// get initial role
			String initialRole = null;
			for (int j=0; j<initialACL.size(); j++) {
				Link lk = (Link) initialACL.get(j);
				if (principal.compareTo(lk.targetName()) == 0) {
					initialRole = lk.targetLabel();
				}
			}
			
			// get final role
			String finalRole = getRole(principal);
			
			// compare initial and final role
			if (initialRole == null) {
				if (finalRole == null) continue;
				if (finalRole.equals(ACL.LABEL_READER)) ACLUpdates.add(ACLOperation.addReaderOperation(plk));
				else if (finalRole.equals(ACL.LABEL_WRITER)) ACLUpdates.add(ACLOperation.addWriterOperation(plk));
				else if (finalRole.equals(ACL.LABEL_MANAGER)) ACLUpdates.add(ACLOperation.addManagerOperation(plk));
			}
			else if (initialRole.equals(ACL.LABEL_READER)) {
				if (finalRole == null) ACLUpdates.add(ACLOperation.removeReaderOperation(plk));
				else if (finalRole.equals(ACL.LABEL_WRITER)) ACLUpdates.add(ACLOperation.addWriterOperation(plk));
				else if (finalRole.equals(ACL.LABEL_MANAGER)) ACLUpdates.add(ACLOperation.addManagerOperation(plk));
			}
			else if (initialRole.equals(ACL.LABEL_WRITER)) {
				if (finalRole == null) ACLUpdates.add(ACLOperation.removeWriterOperation(plk));
				else if (finalRole.equals(ACL.LABEL_READER)) {
					ACLUpdates.add(ACLOperation.removeWriterOperation(plk));
					ACLUpdates.add(ACLOperation.addReaderOperation(plk));
				}
				else if (finalRole.equals(ACL.LABEL_MANAGER)) ACLUpdates.add(ACLOperation.addManagerOperation(plk));
			}
			else if (initialRole.equals(ACL.LABEL_MANAGER)) {
				if (finalRole == null) ACLUpdates.add(ACLOperation.removeManagerOperation(plk));
				else if (finalRole.equals(ACL.LABEL_READER)) {
					ACLUpdates.add(ACLOperation.removeManagerOperation(plk));
					ACLUpdates.add(ACLOperation.addReaderOperation(plk));
				}
				else if (finalRole.equals(ACL.LABEL_WRITER)) {
					ACLUpdates.add(ACLOperation.removeManagerOperation(plk));
					ACLUpdates.add(ACLOperation.addWriterOperation(plk));
				}
			}			
		}
		
		return ACLUpdates;
	}
	
}
