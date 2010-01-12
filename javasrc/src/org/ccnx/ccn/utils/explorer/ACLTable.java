package org.ccnx.ccn.utils.explorer;

import java.util.ArrayList;

import javax.swing.table.AbstractTableModel;

public class ACLTable extends AbstractTableModel {

	private static final long serialVersionUID = 1L;
	
	private String[] columnNames = {"Principals", "Read", "Write", "Manage"};
	private int ACL_LENGTH = 3;
	private ArrayList<String> principals;
	private Object[][] acl;
	
	public ACLTable(String type, ArrayList<String> principals) {
		columnNames[0] = type;
		this.principals = principals;
		acl = new Object[principals.size()][3];
		for (int c=0; c<3; c++) {
			for (int r=0; r<principals.size(); r++) {
				acl[r][c] = new Boolean(false);
			}
		}
	}
	
	public int getColumnCount() {
		return columnNames.length;
	}
	
	public String getColumnName(int col) {
		return columnNames[col];
	}

	public int getRowCount() {
		return principals.size();
	}

	public Class getColumnClass(int col) {
		return getValueAt(0, col).getClass();
	}
	
	public Object getValueAt(int row, int col) {
		if (col == 0) return principals.get(row);
		else if (col < 4) return acl[row][col-1];
		else return null;
	}
	
	public void setValueAt(Object value, int row, int col) {
		Boolean v = (Boolean) value;
		if (v.equals(Boolean.TRUE)) {
			for (int c=0; c<col; c++) {
				acl[row][c] = v;
				fireTableCellUpdated(row, c+1);
			}
		}
		else {
			for (int c=col-1; c < ACL_LENGTH; c++) {
				acl[row][c] = v;
				fireTableCellUpdated(row, c+1);
			}
		}
	}
	
	public boolean isCellEditable(int row, int col) {
		if (col == 0 ) return false;
		else return true;
	}
}
