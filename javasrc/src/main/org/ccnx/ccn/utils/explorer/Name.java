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

import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.ccnx.ccn.protocol.ContentName;

public class Name {
	//public String name;
	public byte[] name;
    public ContentName path;
    public URL fileURL;
    public boolean isDirectory;
    //public ArrayList<ContentName> versions;
    public Set<ContentName> versions;

    public Name(byte[] cn, ContentName prefix, boolean type) {
        name = cn;
        path = prefix;
        isDirectory = type;
        //versions = new ArrayList<ContentName>();
        versions = new HashSet<ContentName>();
    }

    
    public boolean setIsDirectory(boolean b){
    	isDirectory = b;
    	return isDirectory;
    }

	public String toString() {
		if(name==null)
			return new String("/");
		ContentName n = new ContentName(name);
        return n.toString().replaceFirst("/", "");
    }

	public void addVersion(ContentName v) {
		synchronized(versions) {
			versions.add(v);
		}
	}
	
	public Set<ContentName> getVersions() {
		return versions;
	}
	
}
