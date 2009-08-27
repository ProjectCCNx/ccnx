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

    
    public Object getObject() {
		// TODO Auto-generated method stub
		return this;
	}

	public String toString() {
        return new String(name);
    }
	
}
