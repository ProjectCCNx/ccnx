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

import java.net.URL;

import org.ccnx.ccn.protocol.ContentName;

public class Name {
	//public String name;
	public byte[] name;
    public ContentName path;
    public URL fileURL;
    public boolean isDirectory;

    public Name(byte[] cn, ContentName prefix, boolean type) {
        name = cn;
        path = prefix;
        isDirectory = type;
        //fileURL = getClass().getResource(filename);
//        if (fileURL == null) {
//            System.err.println("Couldn't find file: "
//                               + filename);
//        }
    }

    
    public boolean setIsDirectory(boolean b){
    	isDirectory = b;
    	return isDirectory;
    }
    
    public Object getObject() {
		// TODO Auto-generated method stub
		return this;
	}

	public String toString() {
		if(name==null)
			return new String("/");
        return new String(name);
    }
	
}
