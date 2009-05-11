package com.parc.ccn.apps.containerApp;

import java.net.URL;

import com.parc.ccn.data.ContentName;

public class Name {
	public String name;
    public ContentName path;
    public URL fileURL;
    public boolean isDirectory;

    public Name(String nameString, ContentName prefix, boolean type) {
        name = nameString;
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
        return name;
    }
}
