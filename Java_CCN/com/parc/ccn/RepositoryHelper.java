package com.parc.ccn;

import com.parc.ccn.network.RepositoryManager;

public class RepositoryHelper {

	/**
	 * Make it easier to use a CCN, by making it
	 * possible to get things in and out of one.
	 * This interface allows a CCN to be built out
	 * of a filesystem, or saved to one.
	 * 
	 * Uses file naming as names. Content nodes
	 * dumped as files. Link nodes dumped as symlinks
	 * or shortcuts.
	 * @author smetters
	 *
	 */	
	public static void importFolder(String folderName,
									boolean recursive) {
		RepositoryManager manager = RepositoryManager.getCCNRepositoryManager();
	}
	
	public static void exportFolder(String folderName,
								    boolean recursive) {
		
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		

	}

}
