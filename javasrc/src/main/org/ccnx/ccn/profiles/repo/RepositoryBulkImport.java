/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.ccnx.ccn.profiles.repo;

import static org.ccnx.ccn.protocol.Component.NONCE;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.repo.RepositoryInfo;
import org.ccnx.ccn.impl.repo.RepositoryInfo.RepoInfoType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

public class RepositoryBulkImport {
	
	/**
	 * Import outside file data into repo. The data must be in wire format in the file and the file must have
	 * been placed in {repoDir}/import/name.
	 * 
	 * @param handle
	 * @param name name of the file in repoDir/import
	 * @param timeout
	 * @return true if successful
	 * @throws IOException
	 */
	public static boolean bulkImport(CCNHandle handle, String name, long timeout) throws IOException {
		// Create an Interest
		if (name.contains(UserConfiguration.FILE_SEP))
			throw new IOException("Pathnames for repo bulk import data not allowed");
		CommandMarker argMarker = CommandMarker.getMarker(CommandMarker.COMMAND_MARKER_REPO_ADD_FILE.getBytes());
		ContentObject co = handle.get(new ContentName(argMarker.addArgument(name), NONCE), timeout);
		if (co == null)
			return false;
		RepositoryInfo repoInfo = new RepositoryInfo();
		try {
			repoInfo.decode(co.content());
			if (repoInfo.getType().equals(RepoInfoType.INFO)) {
				String info = repoInfo.getInfo();
				if (info.equals("OK"))
					return true;
				Log.warning(Log.FAC_REPO, "Bulk import had the following error: " + info);
			}
		} catch (ContentDecodingException e) {
			Log.info(Log.FAC_REPO, "ContentDecodingException parsing RepositoryInfo: {0} from content object {1}.",  e.getMessage(), co.name());
		}
		return false;
	}
}
