package org.ccnx.ccn.utils.explorer;

import java.util.ArrayList;

import javax.swing.table.AbstractTableModel;

import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.ACL;
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
		this.initialACL = initialACL;
		
		initializeACLTable();		
	}
	
	private void initializeACLTable() {
		aclTable = new Object[principals.length][3];
		for (int c=0; c<3; c++) {
			for (int r=0; r<principals.length; r++) {
				aclTable[r][c] = new Boolean(false);
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

	public Class getColumnClass(int col) {
		return getValueAt(0, col).getClass();
	}
	
	public Object getValueAt(int row, int col) {
		if (col == 0) {
			String friendlyName = ContentName.componentPrintNative(principals[row].lastComponent());
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
		initializeACLTable();
		fireTableDataChanged();
	}
	
	public void setRole(ContentName principal, String role) {
		int pos = -1;
		for (int i=0; i<principals.length; i++) {
			if (principal.compareTo(principals[i]) == 0) {
				pos = i;
				break;
			}
		}
		if (pos > -1) {
			if (role.equals(ACL.LABEL_READER)) {
				aclTable[pos][0] = new Boolean(true);
				aclTable[pos][1] = new Boolean(false);
				aclTable[pos][2] = new Boolean(false);
			}
			if (role.equals(ACL.LABEL_WRITER)) {
				aclTable[pos][0] = new Boolean (true);
				aclTable[pos][1] = new Boolean(true);
				aclTable[pos][2] = new Boolean(false);
			}
			if (role.equals(ACL.LABEL_MANAGER)) {
				aclTable[pos][0] = new Boolean (true);
				aclTable[pos][1] = new Boolean(true);
				aclTable[pos][2] = new Boolean(true);
			}
		}
		else {
			String friendlyName = ContentName.componentPrintNative(principal.lastComponent());
			System.out.println("WARNING: principal name " + friendlyName + " in ACL not a known principal in this table");
		}
	}
	
	public ArrayList<ACL.ACLOperation> computeACLUpdates() {
		ArrayList<ACL.ACLOperation> ACLUpdates = new ArrayList<ACL.ACLOperation>();
		for (int i=0; i<principals.length; i++) {
			ContentName principal = principals[i];
			
			// retrieve initial role
			String initialRole = null;
			for (int j=0; j<initialACL.size(); j++) {
				Link lk = (Link) initialACL.get(j);
				String principalName = ContentName.componentPrintNative(lk.targetName().lastComponent());
				if (principalName.equals(principal)) initialRole = lk.targetLabel();
			}
			
		}
		return ACLUpdates;
	}
	
}
