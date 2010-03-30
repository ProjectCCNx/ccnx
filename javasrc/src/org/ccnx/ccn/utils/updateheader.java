/**
 * A CCNx command line utility.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.utils;

import java.io.IOException;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Header.HeaderObject;
import org.ccnx.ccn.profiles.metadata.MetadataProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

public class updateheader {

	public void usage() {
		System.out.println("usage: updateheader [-log level] <ccnname> [<ccnname>*>]");
		System.exit(1);
	}

	public static void moveHeader(String ccnxName, CCNHandle handle) throws MalformedContentNameStringException, IOException {
		
		ContentName fileName = ContentName.parse(ccnxName);
		
		// Want a versioned name, either this version or latest version
		ContentName fileVersionedName = null;
		
		HeaderObject newHeader = new HeaderObject(MetadataProfile.headerName(fileVersionedName), null, handle);
		newHeader.updateInBackground();
		
		HeaderObject oldHeader = new HeaderObject(MetadataProfile.oldHeaderName(fileVersionedName), null, handle);
		oldHeader.updateInBackground();
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		int arg = 0;
		if ((args.length > 2) && (args[0].equals("-log"))) {
			Log.setDefaultLevel(Level.parse(args[1]));
			arg += 2;
		}
		
		for (int i=arg; i <= args.length; ++i) {
			moveHeader(args[i]);
		}

	}

}
